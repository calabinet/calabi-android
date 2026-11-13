#include "rdp_clipboard.h"
#include <stdlib.h>
#include <string.h>
#include <winpr/clipboard.h>
#include <winpr/sysinfo.h>

// No clipboard listener, disk storage, images, files, or unsolicited format-data requests.
static void clear_text(WCHAR** text, size_t* length) {
    if (*text) {
        volatile WCHAR* wipe = *text;
        for (size_t i = 0; i < *length; i++) wipe[i] = 0;
        free(*text);
    }
    *text = NULL; *length = 0;
}

BOOL calabi_clip_text_valid(const WCHAR* text, size_t length) {
    if (length > CALABI_CLIP_MAX || (!text && length)) return FALSE;
    for (size_t i = 0; i < length; i++) {
        UINT16 c = text[i];
        if (c == 0 || (c >= 0xdc00 && c <= 0xdfff)) return FALSE;
        if (c >= 0xd800 && c <= 0xdbff) {
            if (++i >= length || text[i] < 0xdc00 || text[i] > 0xdfff) return FALSE;
        }
    }
    return TRUE;
}

// Called with the clipboard lock; only one manually initiated operation is pending.
static void finish(CalabiClipboard* clip, int error, WCHAR* text, size_t length) {
    if (clip->pending_id > 0) {
        clear_text(&clip->notice_text, &clip->notice_length);
        clip->notice_id = clip->pending_id; clip->notice_error = error;
        clip->notice_text = text; clip->notice_length = length;
        text = NULL;
    }
    if (text) { size_t n = length; clear_text(&text, &n); }
    clip->pending_id = 0; clip->pending_kind = 0;
}

void calabi_clip_init(CalabiClipboard* clip) { pthread_mutex_init(&clip->lock, NULL); }
void calabi_clip_disconnected(CalabiClipboard* clip) {
    pthread_mutex_lock(&clip->lock);
    clip->channel = NULL; clip->ready = FALSE; clip->ready_notice = TRUE;
    clip->read_inflight = FALSE; clip->write_inflight = FALSE;
    clip->remote_unicode = FALSE;
    clear_text(&clip->offer, &clip->offer_length);
    clear_text(&clip->command_text, &clip->command_length);
    clip->command_id = 0;
    finish(clip, 1, NULL, 0);
    pthread_mutex_unlock(&clip->lock);
}
void calabi_clip_free(CalabiClipboard* clip) {
    calabi_clip_disconnected(clip);
    clear_text(&clip->notice_text, &clip->notice_length);
    pthread_mutex_destroy(&clip->lock);
}

static UINT monitor_ready(CliprdrClientContext* channel, const CLIPRDR_MONITOR_READY* ready) {
    (void)ready;
    CalabiClipboard* clip = channel->custom;
    CLIPRDR_GENERAL_CAPABILITY_SET general = { .capabilitySetType = CB_CAPSTYPE_GENERAL,
        .capabilitySetLength = CB_CAPSTYPE_GENERAL_LEN, .version = CB_CAPS_VERSION_2,
        .generalFlags = CB_USE_LONG_FORMAT_NAMES };
    CLIPRDR_CAPABILITIES caps = { .cCapabilitiesSets = 1, .capabilitySets = (CLIPRDR_CAPABILITY_SET*)&general };
    UINT rc = channel->ClientCapabilities(channel, &caps);
    if (rc != CHANNEL_RC_OK) return rc;
    // Do not publish even an empty format list until an explicit Send action:
    // claiming clipboard ownership here could overwrite the computer clipboard.
    pthread_mutex_lock(&clip->lock);
    clip->ready = TRUE; clip->ready_notice = TRUE;
    pthread_mutex_unlock(&clip->lock);
    return CHANNEL_RC_OK;
}

static UINT server_formats(CliprdrClientContext* channel, const CLIPRDR_FORMAT_LIST* list) {
    CalabiClipboard* clip = channel->custom;
    BOOL unicode = FALSE;
    if (list->numFormats <= 1024 && list->formats) {
        for (UINT32 i = 0; i < list->numFormats; i++) if (list->formats[i].formatId == CF_UNICODETEXT) unicode = TRUE;
    }
    pthread_mutex_lock(&clip->lock);
    clip->remote_unicode = unicode; clip->generation++;
    clear_text(&clip->offer, &clip->offer_length);
    if (clip->pending_kind == 2) finish(clip, 5, NULL, 0);
    pthread_mutex_unlock(&clip->lock);
    CLIPRDR_FORMAT_LIST_RESPONSE response = { .common.msgType = CB_FORMAT_LIST_RESPONSE, .common.msgFlags = CB_RESPONSE_OK };
    return channel->ClientFormatListResponse(channel, &response);
}

static UINT server_list_response(CliprdrClientContext* channel, const CLIPRDR_FORMAT_LIST_RESPONSE* response) {
    CalabiClipboard* clip = channel->custom;
    pthread_mutex_lock(&clip->lock);
    if (clip->write_inflight) {
        clip->write_inflight = FALSE;
        if (clip->pending_kind == 1) finish(clip, (response->common.msgFlags & CB_RESPONSE_OK) ? 0 : 1, NULL, 0);
    }
    pthread_mutex_unlock(&clip->lock);
    return CHANNEL_RC_OK;
}

static UINT server_request(CliprdrClientContext* channel, const CLIPRDR_FORMAT_DATA_REQUEST* request) {
    CalabiClipboard* clip = channel->custom;
    BYTE* data = NULL;
    size_t bytes = 0;
    pthread_mutex_lock(&clip->lock);
    if (clip->offer && request->requestedFormatId == CF_UNICODETEXT) {
        bytes = (clip->offer_length + 1) * 2;
        data = calloc(bytes, 1);
        if (data) for (size_t i = 0; i < clip->offer_length; i++) {
            data[i * 2] = (BYTE)clip->offer[i]; data[i * 2 + 1] = (BYTE)(clip->offer[i] >> 8);
        }
    }
    pthread_mutex_unlock(&clip->lock);
    CLIPRDR_FORMAT_DATA_RESPONSE response = { .common.msgType = CB_FORMAT_DATA_RESPONSE,
        .common.msgFlags = data ? CB_RESPONSE_OK : CB_RESPONSE_FAIL,
        .common.dataLen = data ? (UINT32)bytes : 0, .requestedFormatData = data };
    UINT rc = channel->ClientFormatDataResponse(channel, &response);
    if (data) { volatile BYTE* p = data; for (size_t i = 0; i < bytes; i++) p[i] = 0; free(data); }
    return rc;
}

static UINT server_data(CliprdrClientContext* channel, const CLIPRDR_FORMAT_DATA_RESPONSE* response) {
    CalabiClipboard* clip = channel->custom;
    pthread_mutex_lock(&clip->lock);
    const BOOL requested = clip->read_inflight;
    clip->read_inflight = FALSE;
    if (!requested || clip->pending_kind != 2) { pthread_mutex_unlock(&clip->lock); return CHANNEL_RC_OK; }
    const size_t bytes = response->common.dataLen;
    WCHAR* text = NULL;
    size_t length = 0;
    int error = 3;
    if ((response->common.msgFlags & CB_RESPONSE_OK) && response->requestedFormatData && bytes >= 2 &&
        bytes <= (CALABI_CLIP_MAX + 1) * 2 && (bytes % 2) == 0 && clip->requested_generation == clip->generation) {
        text = calloc(bytes / 2, sizeof(WCHAR));
        if (text) {
            for (size_t i = 0; i < bytes / 2; i++) text[i] = response->requestedFormatData[i * 2] | ((UINT16)response->requestedFormatData[i * 2 + 1] << 8);
            while (length < bytes / 2 && text[length] != 0) length++;
            if (length < bytes / 2 && calabi_clip_text_valid(text, length)) error = 0;
            if (error) { size_t n = bytes / 2; clear_text(&text, &n); length = 0; }
        }
    }
    finish(clip, error, text, length);
    pthread_mutex_unlock(&clip->lock);
    return CHANNEL_RC_OK;
}

static UINT reject_file(CliprdrClientContext* channel, const CLIPRDR_FILE_CONTENTS_REQUEST* request) {
    CLIPRDR_FILE_CONTENTS_RESPONSE response = { .common.msgType = CB_FILECONTENTS_RESPONSE,
        .common.msgFlags = CB_RESPONSE_FAIL, .streamId = request->streamId };
    return channel->ClientFileContentsResponse(channel, &response);
}

void calabi_clip_connected(CalabiClipboard* clip, CliprdrClientContext* channel) {
    pthread_mutex_lock(&clip->lock); clip->channel = channel; pthread_mutex_unlock(&clip->lock);
    channel->custom = clip;
    channel->MonitorReady = monitor_ready;
    channel->ServerFormatList = server_formats;
    channel->ServerFormatListResponse = server_list_response;
    channel->ServerFormatDataRequest = server_request;
    channel->ServerFormatDataResponse = server_data;
    channel->ServerFileContentsRequest = reject_file;
}

BOOL calabi_clip_request(CalabiClipboard* clip, JNIEnv* env, int command, jstring text, int id) {
    if (id <= 0 || command < 1 || command > 3) return FALSE;
    WCHAR* copy = NULL;
    size_t length = 0;
    if (command == 1) {
        if (!text) return FALSE;
        length = (*env)->GetStringLength(env, text);
        if (length > CALABI_CLIP_MAX) return FALSE;
        copy = calloc(length + 1, sizeof(WCHAR));
        if (!copy) return FALSE;
        (*env)->GetStringRegion(env, text, 0, (jsize)length, (jchar*)copy);
        if (!calabi_clip_text_valid(copy, length)) { clear_text(&copy, &length); return FALSE; }
    }
    pthread_mutex_lock(&clip->lock);
    BOOL ok = TRUE;
    if (command == 3) {
        if (clip->command_id == id) { clip->command_id = 0; clear_text(&clip->command_text, &clip->command_length); }
        if (clip->pending_id == id) { clip->pending_id = 0; clip->pending_kind = 0; }
        if (clip->notice_id == id) { clip->notice_id = 0; clear_text(&clip->notice_text, &clip->notice_length); }
    } else if (!clip->ready || !clip->channel || clip->command_id || clip->pending_id ||
               (command == 1 && clip->write_inflight) || (command == 2 && clip->read_inflight)) ok = FALSE;
    else {
        clip->command = command; clip->command_id = id; clip->command_text = copy; clip->command_length = length; copy = NULL;
    }
    pthread_mutex_unlock(&clip->lock);
    if (copy) clear_text(&copy, &length);
    return ok;
}

void calabi_clip_poll(CalabiClipboard* clip, JNIEnv* env, jobject callback, jmethodID method) {
    pthread_mutex_lock(&clip->lock);
    if (clip->pending_id && GetTickCount64() > clip->deadline) finish(clip, 4, NULL, 0);
    int command = 0;
    CliprdrClientContext* channel = clip->channel;
    if (clip->command_id && clip->ready && channel) {
        command = clip->command;
        clip->pending_id = clip->command_id; clip->pending_kind = command; clip->command_id = 0;
        clip->deadline = GetTickCount64() + 8000;
        if (command == 1) {
            clear_text(&clip->offer, &clip->offer_length);
            clip->offer = clip->command_text; clip->offer_length = clip->command_length;
            clip->command_text = NULL; clip->command_length = 0; clip->write_inflight = TRUE;
        } else if (!clip->remote_unicode) { finish(clip, 2, NULL, 0); command = 0; }
        else { clip->requested_generation = clip->generation; clip->read_inflight = TRUE; }
    }
    pthread_mutex_unlock(&clip->lock);
    UINT rc = CHANNEL_RC_OK;
    if (command == 1) {
        CLIPRDR_FORMAT format = { .formatId = CF_UNICODETEXT };
        CLIPRDR_FORMAT_LIST list = { .common.msgType = CB_FORMAT_LIST, .numFormats = 1, .formats = &format };
        rc = channel->ClientFormatList(channel, &list);
    } else if (command == 2) {
        CLIPRDR_FORMAT_DATA_REQUEST request = { .common.msgType = CB_FORMAT_DATA_REQUEST, .requestedFormatId = CF_UNICODETEXT };
        rc = channel->ClientFormatDataRequest(channel, &request);
    }
    pthread_mutex_lock(&clip->lock);
    if (rc != CHANNEL_RC_OK) {
        if (command == 1) clip->write_inflight = FALSE;
        if (command == 2) clip->read_inflight = FALSE;
        finish(clip, 1, NULL, 0);
    }
    const BOOL ready_notice = clip->ready_notice, ready = clip->ready;
    clip->ready_notice = FALSE;
    const int id = clip->notice_id, error = clip->notice_error;
    WCHAR* result = clip->notice_text; size_t length = clip->notice_length;
    clip->notice_id = 0; clip->notice_text = NULL; clip->notice_length = 0;
    pthread_mutex_unlock(&clip->lock);
    if (ready_notice) (*env)->CallVoidMethod(env, callback, method, 0, NULL, ready ? 0 : 1);
    if (id) {
        jstring value = result ? (*env)->NewString(env, (jchar*)result, (jsize)length) : NULL;
        (*env)->CallVoidMethod(env, callback, method, id, value, error);
        if (value) (*env)->DeleteLocalRef(env, value);
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    clear_text(&result, &length);
}
