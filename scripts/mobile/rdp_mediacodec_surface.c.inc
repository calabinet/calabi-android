/* Inserted into the pinned MediaCodec backend by rdp_mediacodec_patch.py.
 * Kirin's single-frame low-latency mode requires an ANativeWindow. Read its
 * ImageReader planes using their actual strides; never assume NV12 layout.
 * On Kirin EMUI 10 (vendor AHB format 0x30d), VDEC/gralloc align the luma
 * plane height to 32 rows, while NdkImageReader reports unaligned plane[1]
 * pointers and truncated chroma lengths when desc.height % 32 != 0. */
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
    AHardwareBuffer* lockedHb = NULL;
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
    uint8_t* planeData[3] = {NULL, NULL, NULL};
    int planeLength[3] = {0, 0, 0};
    int planeRowStride[3] = {0, 0, 0};
    int planePixelStride[3] = {0, 0, 0};
    for (int plane = 0; plane < 3; plane++)
    {
        if (AImage_getPlaneData(image, plane, &planeData[plane], &planeLength[plane]) != AMEDIA_OK ||
            !planeData[plane] || planeLength[plane] <= 0 ||
            AImage_getPlaneRowStride(image, plane, &planeRowStride[plane]) != AMEDIA_OK ||
            planeRowStride[plane] <= 0 ||
            AImage_getPlanePixelStride(image, plane, &planePixelStride[plane]) != AMEDIA_OK ||
            planePixelStride[plane] <= 0)
            goto done;
    }
    AHardwareBuffer* hb = NULL;
    AHardwareBuffer_Desc desc = {0};
    const BOOL haveHb = (AImage_getHardwareBuffer(image, &hb) == AMEDIA_OK && hb != NULL);
    if (haveHb)
        AHardwareBuffer_describe(hb, &desc);
    const uint8_t* uvLow = planeData[1] < planeData[2] ? planeData[1] : planeData[2];
    const uint32_t alignedHeight = haveHb ? ((desc.height + 31u) & ~31u) : 0u;
    const BOOL hisiUnalignedChroma =
        haveHb &&
        desc.format == 0x30du &&
        desc.width >= (uint32_t)crop.right &&
        desc.height >= (uint32_t)crop.bottom &&
        desc.height <= (uint32_t)MEDIACODEC_MAXIMUM_DIMENSION &&
        desc.stride == (uint32_t)planeRowStride[0] &&
        planeRowStride[1] == planeRowStride[0] &&
        planeRowStride[2] == planeRowStride[0] &&
        planePixelStride[0] == 1 &&
        planePixelStride[1] == 2 &&
        planePixelStride[2] == 2 &&
        uvLow > planeData[0] &&
        (planeData[2] == planeData[1] + 1 || planeData[1] == planeData[2] + 1) &&
        alignedHeight > desc.height &&
        (size_t)(uvLow - planeData[0]) == (size_t)planeRowStride[0] * desc.height;
    if (!sys->firstFrameReported)
    {
        for (int plane = 0; plane < 3; plane++)
            __android_log_print(ANDROID_LOG_INFO, "CalabiRdp",
                "avc_plane p=%d row=%d pixel=%d len=%d diff=%td image=%dx%d crop=%d,%d,%d,%d",
                plane, planeRowStride[plane], planePixelStride[plane], planeLength[plane],
                planeData[plane] - planeData[0], imageWidth, imageHeight,
                crop.left, crop.top, crop.right, crop.bottom);
        if (haveHb)
            __android_log_print(ANDROID_LOG_INFO, "CalabiRdp",
                "avc_ahb size=%ux%u stride=%u fmt=0x%x aligned_h=%u fix=%d",
                desc.width, desc.height, desc.stride, desc.format, alignedHeight, hisiUnalignedChroma ? 1 : 0);
    }
    if (hisiUnalignedChroma)
    {
        const UINT32 uvLeft = (UINT32)crop.left / 2u;
        const UINT32 uvTop = (UINT32)crop.top / 2u;
        if ((UINT32)crop.left + width > desc.stride ||
            (UINT32)crop.top + height > desc.height ||
            (uvLeft + uvWidth) * 2u > desc.stride ||
            uvTop + uvHeight > (alignedHeight + 1u) / 2u)
            goto done;
        void* raw = NULL;
        if (AHardwareBuffer_lock(hb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, NULL, &raw) != 0 || !raw)
        {
            __android_log_print(ANDROID_LOG_WARN, "CalabiRdp", "avc_ahb_lock_failed");
            goto done;
        }
        lockedHb = hb;
        const BYTE* base = (const BYTE*)raw;
        const size_t stride = desc.stride;
        BYTE* dstY = sys->decodeI420Buffer;
        for (UINT32 y = 0; y < height; y++)
            memcpy(dstY + (size_t)y * width, base + (size_t)((UINT32)crop.top + y) * stride + (UINT32)crop.left, width);
        const BYTE* uvBase = base + (size_t)alignedHeight * stride;
        const size_t uByteOffset = (planeData[1] > planeData[2]) ? 1u : 0u;
        const size_t vByteOffset = (planeData[2] > planeData[1]) ? 1u : 0u;
        BYTE* dstU = sys->decodeI420Buffer + yBytes;
        BYTE* dstV = sys->decodeI420Buffer + yBytes + uvBytes;
        for (UINT32 y = 0; y < uvHeight; y++)
        {
            const BYTE* row = uvBase + (size_t)(uvTop + y) * stride + (size_t)uvLeft * 2u;
            BYTE* outU = dstU + (size_t)y * uvWidth;
            BYTE* outV = dstV + (size_t)y * uvWidth;
            for (UINT32 x = 0; x < uvWidth; x++)
            {
                outU[x] = row[(size_t)x * 2u + uByteOffset];
                outV[x] = row[(size_t)x * 2u + vByteOffset];
            }
        }
    }
    else
    {
        for (int plane = 0; plane < 3; plane++)
        {
            const uint8_t* data = planeData[plane];
            const int length = planeLength[plane];
            const int rowStride = planeRowStride[plane];
            const int pixelStride = planePixelStride[plane];
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
                if (pixelStride == 1)
                    memcpy(dest + (size_t)y * columns, row, columns);
                else
                    for (UINT32 x = 0; x < columns; x++) dest[(size_t)y * columns + x] = row[(size_t)x * pixelStride];
            }
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
    if (lockedHb)
        AHardwareBuffer_unlock(lockedHb, NULL);
    if (result < 0)
        __android_log_print(ANDROID_LOG_WARN, "CalabiRdp", "avc_surface_layout size=%dx%d planes=%d timestamp=%lld expected=%llu", imageWidth, imageHeight, count, (long long)timestamp, (unsigned long long)(pictureTime * 1000));
    AImage_delete(image);
    return result;
}
