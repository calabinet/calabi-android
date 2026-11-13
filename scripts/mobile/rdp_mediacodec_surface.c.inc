/* Inserted into the pinned MediaCodec backend by rdp_mediacodec_patch.py.
 * Kirin's single-frame low-latency mode requires an ANativeWindow. Read its
 * ImageReader planes using their actual strides; never assume NV12 layout. */
static int calabi_surface_picture(H264_CONTEXT* h264, H264_CONTEXT_MEDIACODEC* sys,
                                 uint64_t pictureTime)
{
    AImage* image = NULL;
    media_status_t rc = AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE;
    for (int attempt = 0; attempt < 100; attempt++)
    {
        rc = AImageReader_acquireNextImage(sys->imageReader, &image);
        if (rc != AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE) break;
        usleep(2000);
    }
    if (rc != AMEDIA_OK || !image)
    {
        __android_log_print(ANDROID_LOG_WARN, "CalabiRdp", "avc_surface_acquire error=%d", rc);
        return -1;
    }
    int result = -1;
    int32_t imageWidth = 0, imageHeight = 0, count = 0;
    int64_t timestamp = 0;
    AImageCropRect crop = {0};
    if (AImage_getWidth(image, &imageWidth) != AMEDIA_OK ||
        AImage_getHeight(image, &imageHeight) != AMEDIA_OK ||
        AImage_getCropRect(image, &crop) != AMEDIA_OK ||
        AImage_getNumberOfPlanes(image, &count) != AMEDIA_OK || count != 3 ||
        AImage_getTimestamp(image, &timestamp) != AMEDIA_OK ||
        timestamp != (int64_t)(pictureTime * 1000) ||
        crop.left < 0 || crop.top < 0 || crop.right > imageWidth || crop.bottom > imageHeight ||
        crop.right <= crop.left || crop.bottom <= crop.top)
        goto done;
    const UINT32 width = (UINT32)(crop.right - crop.left);
    const UINT32 height = (UINT32)(crop.bottom - crop.top);
    if (width > MEDIACODEC_MAXIMUM_DIMENSION || height > MEDIACODEC_MAXIMUM_DIMENSION)
        goto done;
    const size_t yBytes = (size_t)width * height;
    const UINT32 uvWidth = (width + 1) / 2, uvHeight = (height + 1) / 2;
    const size_t uvBytes = (size_t)uvWidth * uvHeight;
    const size_t capacity = yBytes + 2 * uvBytes;
    if (sys->decodeI420Capacity < capacity)
    {
        BYTE* next = realloc(sys->decodeI420Buffer, capacity);
        if (!next) goto done;
        sys->decodeI420Buffer = next;
        sys->decodeI420Capacity = capacity;
    }
    for (int plane = 0; plane < 3; plane++)
    {
        uint8_t* data = NULL;
        int length = 0, rowStride = 0, pixelStride = 0;
        if (AImage_getPlaneData(image, plane, &data, &length) != AMEDIA_OK || !data || length <= 0 ||
            AImage_getPlaneRowStride(image, plane, &rowStride) != AMEDIA_OK || rowStride <= 0 ||
            AImage_getPlanePixelStride(image, plane, &pixelStride) != AMEDIA_OK || pixelStride <= 0)
            goto done;
        if (!sys->firstFrameReported)
            __android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "avc_plane p=%d row=%d pixel=%d image=%dx%d crop=%d,%d,%d,%d", plane, rowStride, pixelStride, imageWidth, imageHeight, crop.left, crop.top, crop.right, crop.bottom);
        const UINT32 rows = plane ? uvHeight : height;
        const UINT32 columns = plane ? uvWidth : width;
        const UINT32 left = plane ? (UINT32)crop.left / 2 : (UINT32)crop.left;
        const UINT32 top = plane ? (UINT32)crop.top / 2 : (UINT32)crop.top;
        const size_t last = (size_t)(top + rows - 1) * rowStride +
                            (size_t)(left + columns - 1) * pixelStride;
        if (last >= (size_t)length) goto done;
        BYTE* dest = sys->decodeI420Buffer + (plane ? yBytes + (plane - 1) * uvBytes : 0);
        for (UINT32 y = 0; y < rows; y++)
        {
            const BYTE* row = data + (size_t)(top + y) * rowStride + (size_t)left * pixelStride;
            for (UINT32 x = 0; x < columns; x++) dest[(size_t)y * columns + x] = row[(size_t)x * pixelStride];
        }
    }
    h264->pYUVData[0] = sys->decodeI420Buffer;
    h264->pYUVData[1] = sys->decodeI420Buffer + yBytes;
    h264->pYUVData[2] = sys->decodeI420Buffer + yBytes + uvBytes;
    h264->iStride[0] = width;
    h264->iStride[1] = h264->iStride[2] = uvWidth;
    h264->YUVWidth = width;
    h264->YUVHeight = height;
    if (!sys->firstFrameReported)
    {
        sys->firstFrameReported = TRUE;
        __android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "avc_decoded_first %ux%u surface=1", width, height);
    }
    result = 1;
done:
    if (result < 0)
        __android_log_print(ANDROID_LOG_WARN, "CalabiRdp", "avc_surface_layout size=%dx%d planes=%d timestamp=%lld expected=%llu", imageWidth, imageHeight, count, (long long)timestamp, (unsigned long long)(pictureTime * 1000));
    AImage_delete(image);
    return result;
}
