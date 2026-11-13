"""Small, reproducible adjustments to the hash-verified FreeRDP source archive."""
import tarfile
from pathlib import Path


def apply(source, archive):
    relative = 'libfreerdp/codec/h264_mediacodec.c'
    with tarfile.open(archive) as bundle:
        text = bundle.extractfile('freerdp-3.33.0/' + relative).read().decode('utf-8')
    changes = [
        ('#include <freerdp/config.h>', '#include <freerdp/config.h>\n#include <stdlib.h>\n#include <unistd.h>\n#include <android/log.h>\n#include <media/NdkImageReader.h>\n#include <android/hardware_buffer.h>'),
        ('\tBOOL decoderStarted;', '\tBOOL decoderStarted;\n\tBOOL firstFrameReported;\n\tuint64_t decodeSequence;\n\tAImageReader* imageReader;'),
        ('static int mediacodec_decompress(H264_CONTEXT* h264, const BYTE* pSrcData, UINT32 SrcSize)',
         Path(__file__).with_name('rdp_mediacodec_surface.c.inc').read_text(encoding='utf-8') +
         '\nstatic int mediacodec_decompress(H264_CONTEXT* h264, const BYTE* pSrcData, UINT32 SrcSize)'),
        ('\t\t\t\tsys->currentOutputBufferIndex = outputBufferId;\n\n\t\t\t\tsize_t outputBufferSize',
         '\t\t\tif (sys->imageReader) {\n'
         '\t\t\t\tif ((uint64_t)bufferInfo.presentationTimeUs != pictureTime ||\n'
         '\t\t\t\t    AMediaCodec_releaseOutputBuffer(sys->decoder, outputBufferId, true) != AMEDIA_OK) return -1;\n'
         '\t\t\t\treturn calabi_surface_picture(h264, sys, pictureTime);\n'
         '\t\t\t}\n'
         '\t\t\tsys->currentOutputBufferIndex = outputBufferId;\n\n\t\t\tsize_t outputBufferSize'),
        ('\trelease_current_outputbuffer(h264);\n\n\tif ((h264->width',
         '\trelease_current_outputbuffer(h264);\n'
         '\tconst uint64_t pictureTime = ++sys->decodeSequence * 33333;\n'
         '\tunsigned nalMask = 0;\n'
         '\tfor (UINT32 i = 0; i + 4 < SrcSize; i++)\n'
         '\t\tif (pSrcData[i] == 0 && pSrcData[i+1] == 0 && pSrcData[i+2] == 1) nalMask |= 1u << (pSrcData[i+3] & 31);\n'
         '\tif (sys->decodeSequence <= 3) __android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "avc_input sequence=%llu size=%u nal_mask=0x%x", (unsigned long long)sys->decodeSequence, SrcSize, nalMask);\n\n\tif ((h264->width'),
        ('sys->decoder, inputBufferId, 0, numberOfBytesToCopy, 0, 0);',
         'sys->decoder, inputBufferId, 0, numberOfBytesToCopy, pictureTime, 0);'),
        ('\t\t\t\t/* The picture starts at the reported offset, not at the buffer base. */',
         '\t\t\t\t/* RDP dirty regions describe this exact picture, never a buffered predecessor. */\n'
         '\t\t\t\tif ((uint64_t)bufferInfo.presentationTimeUs != pictureTime) {\n'
         '\t\t\t\t\t__android_log_print(ANDROID_LOG_WARN, "CalabiRdp", "avc_picture_mismatch expected=%llu actual=%lld", (unsigned long long)pictureTime, (long long)bufferInfo.presentationTimeUs);\n'
         '\t\t\t\t\treturn -1;\n'
         '\t\t\t\t}\n'
         '\t\t\t\t/* The picture starts at the reported offset, not at the buffer base. */'),
        ('"AMediaCodec_dequeueOutputBuffer no buffer ready yet");\n\t\t\t\t\treturn 0;',
         '"AMediaCodec_dequeueOutputBuffer no buffer ready yet");\n'
         '\t\t\t\t\t__android_log_print(ANDROID_LOG_WARN, "CalabiRdp", "avc_picture_timeout sequence=%llu", (unsigned long long)sys->decodeSequence);\n'
         '\t\t\t\t\treturn -1;'),
        ('\tsys->decoder = AMediaCodec_createDecoderByType(CODEC_NAME);',
         '\tconst char* selected = getenv("CALABI_RDP_ANDROID_AVC_DECODER");\n'
         '\tsys->decoder = (selected && selected[0]) ? AMediaCodec_createCodecByName(selected) : nullptr;'),
        ('\tstatus = AMediaCodec_configure(sys->decoder, sys->inputFormat, nullptr, nullptr, 0);',
         '\t/* Kirin Android Q predates the standard low-latency key. Huawei documents\n'
         '\t * this extension for single-frame H.264 decode; other vendors keep the\n'
         '\t * standard key configured by upstream. Always retain timestamp checks. */\n'
         '\tconst BOOL hisi = selected && strncmp(selected, "OMX.hisi.", 9) == 0;\n'
         '\tif (hisi) {\n'
         '\t\tAMediaFormat_setInt32(sys->inputFormat, "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1);\n'
         '\t\tAMediaFormat_setInt32(sys->inputFormat, "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", -1);\n'
         '\t}\n'
         '\tANativeWindow* outputWindow = NULL;\n'
         '\tif (hisi) {\n'
         '\t\tif (AImageReader_newWithUsage(sys->width, sys->height, AIMAGE_FORMAT_YUV_420_888, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, 1, &sys->imageReader) != AMEDIA_OK ||\n'
         '\t\t    AImageReader_getWindow(sys->imageReader, &outputWindow) != AMEDIA_OK) goto EXCEPTION;\n'
         '\t}\n'
         '\tstatus = AMediaCodec_configure(sys->decoder, sys->inputFormat, outputWindow, nullptr, 0);'),
        ('\tsys->decodeI420Buffer = nullptr;\n\tfree(sys->spsPpsData);',
         '\tsys->decodeI420Buffer = nullptr;\n\tif (sys->imageReader) AImageReader_delete(sys->imageReader);\n\tfree(sys->spsPpsData);'),
        ('\tsys->decoderStarted = TRUE;\n\treturn TRUE;',
         '\tif (hisi) {\n'
         '\t\tint32_t ready = -1;\n'
         '\t\tAMediaFormat* query = AMediaCodec_getInputFormat(sys->decoder);\n'
         '\t\tif (query) {\n'
         '\t\t\tAMediaFormat_getInt32(query, "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy", &ready);\n'
         '\t\t\tAMediaFormat_delete(query);\n'
         '\t\t}\n'
         '\t\t__android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "avc_low_latency_hisi ready=%d", ready);\n'
         '\t}\n'
         '\tsys->decoderStarted = TRUE;\n\treturn TRUE;'),
        ('WLog_Print(h264->log, WLOG_DEBUG, "MediaCodec using %s codec [%s]", CODEC_NAME, codec_name);',
         '__android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "avc_decoder=%s", codec_name);'),
        ('\t\t\t\th264->YUVHeight = (UINT32)sys->outputHeight;',
         '\t\t\t\th264->YUVHeight = (UINT32)sys->outputHeight;\n'
         '\t\t\t\tif (!sys->firstFrameReported) {\n'
         '\t\t\t\t\tsys->firstFrameReported = TRUE;\n'
         '\t\t\t\t\t__android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "avc_decoded_first %ux%u format=%d", h264->YUVWidth, h264->YUVHeight, sys->colorFormat);\n'
         '\t\t\t\t}'),
    ]
    for before, after in changes:
        if text.count(before) != 1:
            raise RuntimeError('Pinned MediaCodec patch no longer matches upstream source: ' + repr(before[:100]))
        text = text.replace(before, after)
    target = source / relative
    # Do not touch timestamps on a no-op rebuild.
    if target.read_text(encoding='utf-8') != text:
        target.write_text(text, encoding='utf-8', newline='\n')
    relative = 'libfreerdp/gdi/gfx.c'
    with tarfile.open(archive) as bundle:
        text = bundle.extractfile('freerdp-3.33.0/' + relative).read().decode('utf-8')
    for mode in ('420', '444'):
        before = f'WLog_WARN(TAG, "avc{mode}_decompress failure: %" PRId32 ", ignoring update.", rc);\n\t\treturn CHANNEL_RC_OK;'
        if text.count(before) != 1:
            raise RuntimeError('Pinned AVC error propagation patch no longer matches')
        text = text.replace(before, f'WLog_WARN(TAG, "avc{mode}_decompress failure: %" PRId32, rc);\n\t\treturn ERROR_INTERNAL_ERROR;')
    target = source / relative
    if target.read_text(encoding='utf-8') != text:
        target.write_text(text, encoding='utf-8', newline='\n')
    relative = 'libfreerdp/codec/yuv.c'
    with tarfile.open(archive) as bundle:
        text = bundle.extractfile('freerdp-3.33.0/' + relative).read().decode('utf-8')
    target = source / relative
    if target.read_text(encoding='utf-8') != text:
        target.write_text(text, encoding='utf-8', newline='\n')
