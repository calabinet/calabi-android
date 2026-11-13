// Standalone Android fixture: no network, platform clipboard, or user data.
#include "rdp_clipboard.h"
#include <assert.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <winpr/clipboard.h>

typedef struct { jsize length; const WCHAR* value; } TestString;
static int lists, reads, notices, last_id, last_error;
static size_t last_length, sent_bytes;
static WCHAR received[CALABI_CLIP_MAX + 1];
static BYTE sent[(CALABI_CLIP_MAX + 1) * 2];
static BOOL sent_ok;

static jsize JNICALL string_length(JNIEnv* env, jstring value) { (void)env; return ((TestString*)value)->length; }
static void JNICALL string_region(JNIEnv* env, jstring value, jsize start, jsize length, jchar* out) {
    (void)env; TestString* s = (TestString*)value;
    assert(start >= 0 && length >= 0 && start + length <= s->length);
    if (length) memcpy(out, s->value + start, length * sizeof(WCHAR));
}
static jstring JNICALL new_string(JNIEnv* env, const jchar* value, jsize length) {
    (void)env;
    TestString* s = malloc(sizeof(*s) + (length + 1) * sizeof(WCHAR)); assert(s);
    s->length = length; s->value = (WCHAR*)(s + 1);
    memcpy((void*)s->value, value, length * sizeof(WCHAR));
    return (jstring)s;
}
static void JNICALL delete_local(JNIEnv* env, jobject value) { (void)env; free(value); }
static void JNICALL notify(JNIEnv* env, jobject object, jmethodID method, ...) {
    (void)env; (void)object; (void)method;
    va_list args; va_start(args, method);
    int id = va_arg(args, int); TestString* text = (TestString*)va_arg(args, jstring); int error = va_arg(args, int);
    va_end(args);
    if (!id) return;
    notices++; last_id = id; last_error = error; last_length = text ? text->length : 0;
    assert(last_length <= CALABI_CLIP_MAX);
    if (text) memcpy(received, text->value, last_length * sizeof(WCHAR));
}
static jboolean JNICALL no_exception(JNIEnv* env) { (void)env; return JNI_FALSE; }
static void JNICALL clear_exception(JNIEnv* env) { (void)env; }
static const struct JNINativeInterface java = { .GetStringLength = string_length, .GetStringRegion = string_region,
    .NewString = new_string, .DeleteLocalRef = delete_local, .CallVoidMethod = notify,
    .ExceptionCheck = no_exception, .ExceptionClear = clear_exception };

static UINT caps(CliprdrClientContext* channel, const CLIPRDR_CAPABILITIES* value) {
    (void)channel;
    assert(value->cCapabilitiesSets == 1);
    assert(((CLIPRDR_GENERAL_CAPABILITY_SET*)value->capabilitySets)->generalFlags == CB_USE_LONG_FORMAT_NAMES);
    return CHANNEL_RC_OK;
}
static UINT formats(CliprdrClientContext* channel, const CLIPRDR_FORMAT_LIST* value) {
    (void)channel; lists++;
    assert(value->numFormats == 1 && value->formats[0].formatId == CF_UNICODETEXT);
    return CHANNEL_RC_OK;
}
static UINT ack(CliprdrClientContext* channel, const CLIPRDR_FORMAT_LIST_RESPONSE* value) {
    (void)channel; assert(value->common.msgFlags == CB_RESPONSE_OK); return CHANNEL_RC_OK;
}
static UINT request(CliprdrClientContext* channel, const CLIPRDR_FORMAT_DATA_REQUEST* value) {
    (void)channel; reads++; assert(value->requestedFormatId == CF_UNICODETEXT); return CHANNEL_RC_OK;
}
static UINT data(CliprdrClientContext* channel, const CLIPRDR_FORMAT_DATA_RESPONSE* value) {
    (void)channel; sent_ok = value->common.msgFlags == CB_RESPONSE_OK; sent_bytes = value->common.dataLen;
    assert(sent_bytes <= sizeof(sent)); if (sent_bytes) memcpy(sent, value->requestedFormatData, sent_bytes);
    return CHANNEL_RC_OK;
}
static UINT file_response(CliprdrClientContext* channel, const CLIPRDR_FILE_CONTENTS_RESPONSE* value) {
    (void)channel; assert(value->common.msgFlags == CB_RESPONSE_FAIL && value->streamId == 42 && value->cbRequested == 0);
    return CHANNEL_RC_OK;
}

int main(void) {
    CalabiClipboard clip = {0}; calabi_clip_init(&clip);
    CliprdrClientContext channel = { .ClientCapabilities = caps, .ClientFormatList = formats,
        .ClientFormatListResponse = ack, .ClientFormatDataRequest = request, .ClientFormatDataResponse = data,
        .ClientFileContentsResponse = file_response };
    calabi_clip_connected(&clip, &channel);
    JNIEnv env = &java;
    #define POLL() calabi_clip_poll(&clip, &env, NULL, NULL)
    CLIPRDR_MONITOR_READY ready = {0};
    assert(channel.MonitorReady(&channel, &ready) == CHANNEL_RC_OK); POLL();
    assert(clip.ready && lists == 0 && reads == 0); // Connecting must neither claim nor read clipboard.
    CLIPRDR_FORMAT unicode = {.formatId = CF_UNICODETEXT};
    CLIPRDR_FORMAT_LIST remote = {.numFormats = 1, .formats = &unicode};
    assert(channel.ServerFormatList(&channel, &remote) == CHANNEL_RC_OK); POLL(); assert(reads == 0);
    static const WCHAR sample[] = {'C','N',0x4e2d,0x6587,0xd83d,0xde00,13,10};
    TestString value = {.length = 8, .value = sample};
    BYTE bytes[18] = {0};
    for (size_t i = 0; i < 8; i++) { bytes[i * 2] = sample[i]; bytes[i * 2 + 1] = sample[i] >> 8; }
    CLIPRDR_FORMAT_DATA_RESPONSE response = { .common.msgFlags = CB_RESPONSE_OK, .common.dataLen = sizeof(bytes), .requestedFormatData = bytes };
    assert(calabi_clip_request(&clip, &env, 2, NULL, 1)); POLL(); assert(reads == 1);
    assert(channel.ServerFormatDataResponse(&channel, &response) == CHANNEL_RC_OK); POLL();
    assert(last_id == 1 && last_error == 0 && last_length == 8 && memcmp(received, sample, sizeof(sample)) == 0);
    assert(calabi_clip_request(&clip, &env, 1, (jstring)&value, 2)); POLL(); assert(lists == 1);
    CLIPRDR_FORMAT_DATA_REQUEST ask = {.requestedFormatId = CF_UNICODETEXT};
    assert(channel.ServerFormatDataRequest(&channel, &ask) == CHANNEL_RC_OK);
    assert(sent_ok && sent_bytes == sizeof(bytes) && memcmp(sent, bytes, sizeof(bytes)) == 0);
    ask.requestedFormatId = 999;
    assert(channel.ServerFormatDataRequest(&channel, &ask) == CHANNEL_RC_OK && !sent_ok && sent_bytes == 0);
    CLIPRDR_FORMAT_LIST_RESPONSE accepted = {.common.msgFlags = CB_RESPONSE_OK};
    assert(channel.ServerFormatListResponse(&channel, &accepted) == CHANNEL_RC_OK); POLL(); assert(last_id == 2 && !last_error);
    CLIPRDR_FILE_CONTENTS_REQUEST file = {.streamId = 42};
    assert(channel.ServerFileContentsRequest(&channel, &file) == CHANNEL_RC_OK);
    TestString huge = {.length = CALABI_CLIP_MAX + 1};
    assert(!calabi_clip_request(&clip, &env, 1, (jstring)&huge, 3));
    WCHAR bad_text[] = {0xd800}; TestString bad = {.length = 1, .value = bad_text};
    assert(!calabi_clip_request(&clip, &env, 1, (jstring)&bad, 3));
    bad_text[0] = 0; assert(!calabi_clip_request(&clip, &env, 1, (jstring)&bad, 3));

    // A cancelled request cannot attach its late response to a later request.
    assert(calabi_clip_request(&clip, &env, 2, NULL, 10)); POLL();
    assert(calabi_clip_request(&clip, &env, 3, NULL, 10));
    assert(!calabi_clip_request(&clip, &env, 2, NULL, 11));
    int previous = notices;
    assert(channel.ServerFormatDataResponse(&channel, &response) == CHANNEL_RC_OK); POLL(); assert(notices == previous);
    assert(calabi_clip_request(&clip, &env, 2, NULL, 11)); POLL();
    assert(channel.ServerFormatDataResponse(&channel, &response) == CHANNEL_RC_OK); POLL(); assert(last_id == 11 && !last_error);
    // Timeout and clipboard-ownership changes also quarantine the old wire response.
    assert(calabi_clip_request(&clip, &env, 2, NULL, 20)); POLL(); clip.deadline = 0; POLL();
    assert(last_id == 20 && last_error == 4 && !calabi_clip_request(&clip, &env, 2, NULL, 21));
    assert(channel.ServerFormatDataResponse(&channel, &response) == CHANNEL_RC_OK); POLL();
    assert(calabi_clip_request(&clip, &env, 2, NULL, 30)); POLL();
    assert(channel.ServerFormatList(&channel, &remote) == CHANNEL_RC_OK); POLL(); assert(last_id == 30 && last_error == 5);
    assert(channel.ServerFormatDataResponse(&channel, &response) == CHANNEL_RC_OK); POLL();
    // Reject odd, oversized, unterminated and malformed UTF-16 payloads before exposing text.
    const UINT32 invalid_lengths[] = {3, (CALABI_CLIP_MAX + 2) * 2, 2, 4};
    BYTE invalid[] = {0, 0xd8, 0, 0};
    for (size_t i = 0; i < 4; i++) {
        assert(calabi_clip_request(&clip, &env, 2, NULL, 40 + i)); POLL();
        CLIPRDR_FORMAT_DATA_RESPONSE malformed = {.common.msgFlags = CB_RESPONSE_OK,
            .common.dataLen = invalid_lengths[i], .requestedFormatData = invalid};
        assert(channel.ServerFormatDataResponse(&channel, &malformed) == CHANNEL_RC_OK); POLL(); assert(last_error == 3);
    }
    CLIPRDR_FORMAT_LIST empty = {0};
    assert(channel.ServerFormatList(&channel, &empty) == CHANNEL_RC_OK);
    previous = reads; assert(calabi_clip_request(&clip, &env, 2, NULL, 50)); POLL(); assert(last_error == 2 && reads == previous);
    assert(calabi_clip_request(&clip, &env, 1, (jstring)&value, 60)); POLL(); clip.deadline = 0; POLL();
    assert(last_error == 4 && !calabi_clip_request(&clip, &env, 1, (jstring)&value, 61));
    assert(channel.ServerFormatListResponse(&channel, &accepted) == CHANNEL_RC_OK); POLL();
    assert(calabi_clip_request(&clip, &env, 1, (jstring)&value, 61)); POLL();
    assert(channel.ServerFormatListResponse(&channel, &accepted) == CHANNEL_RC_OK); POLL(); assert(last_id == 61 && !last_error);
    calabi_clip_disconnected(&clip); assert(!clip.ready && clip.offer == NULL && clip.command_text == NULL);
    assert(!calabi_clip_request(&clip, &env, 2, NULL, 70));
    calabi_clip_free(&clip);
    puts("Clipboard native fixtures passed: manual-only, UTF-16, limits, no files, cancellation, timeout, stale responses and disconnect cleanup");
    return 0;
}
