#pragma once
#include <jni.h>
#include <pthread.h>
#include <freerdp/freerdp.h>
#include <freerdp/client/cliprdr.h>

#define CALABI_CLIP_MAX 32768u
typedef struct {
    pthread_mutex_t lock;
    CliprdrClientContext* channel;
    BOOL ready, ready_notice, remote_unicode, read_inflight, write_inflight;
    UINT32 generation, requested_generation;
    int command, command_id, pending_kind, pending_id, notice_id, notice_error;
    UINT64 deadline;
    WCHAR *offer, *command_text, *notice_text;
    size_t offer_length, command_length, notice_length;
} CalabiClipboard;

void calabi_clip_init(CalabiClipboard* clip);
void calabi_clip_free(CalabiClipboard* clip);
void calabi_clip_connected(CalabiClipboard* clip, CliprdrClientContext* channel);
void calabi_clip_disconnected(CalabiClipboard* clip);
BOOL calabi_clip_request(CalabiClipboard* clip, JNIEnv* env, int command, jstring text, int id);
void calabi_clip_poll(CalabiClipboard* clip, JNIEnv* env, jobject callback, jmethodID method);
BOOL calabi_clip_text_valid(const WCHAR* text, size_t length);
