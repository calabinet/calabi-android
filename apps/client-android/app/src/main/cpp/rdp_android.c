#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <stdio.h>
#include <freerdp/freerdp.h>
#include <freerdp/settings.h>
#include <freerdp/error.h>
#include <freerdp/input.h>
#include <freerdp/transport_io.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/codec/color.h>
#include <freerdp/crypto/certificate.h>
#include <freerdp/client/channels.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/channels/channels.h>
#include <freerdp/addin.h>
#include "rdp_clipboard.h"
#include "rdp_graphics.h"
#include <winpr/synch.h>
#include <winpr/sysinfo.h>
#include <winpr/string.h>
#include <winpr/wlog.h>

#define MAX_PIXELS (1920u * 1080u)
#define QUEUE_SIZE 256
typedef struct { int kind, flags, x, y; } Input;
typedef struct Session {
    JavaVM* vm;
    jobject callback;
    jmethodID state_cb, frame_cb, verify_cb, clipboard_cb, recovery_cb, graphics_cb;
    JNIEnv* worker_env;
    freerdp* rdp;
    rdpSettings* initial_settings;
    CalabiGraphics graphics;
    uint64_t connected_at;
    pthread_t thread;
    pthread_mutex_t lock;
    atomic_bool stopped;
    atomic_bool foreground;
    atomic_bool release_pending;
    atomic_bool network_ready, repair_requested;
    BOOL subscribed;
    BOOL awaiting_frame;
    BOOL repairing_connection;
    uint64_t ready_at;
    uint64_t repair_deadline;
    BOOL output_visible;
    BOOL keys[512];
    UINT16 buttons, pointer_x, pointer_y, unicode_down;
    int port, started, width, height, certificate_verified, certificate_rejected;
    BYTE* frame;
    size_t frame_bytes;
    uint64_t frames, rendered, begun;
    uint64_t input_count, event_checks, copied_bytes;
    char host[INET_ADDRSTRLEN], local[INET_ADDRSTRLEN], pin[65];
    Input queue[QUEUE_SIZE];
    unsigned read_pos, write_pos;
    CalabiClipboard clipboard;
} Session;
typedef struct { rdpContext base; Session* session; } Context;

static Session* session_of(rdpContext* context) { return ((Context*)context)->session; }
CalabiGraphics* calabi_gfx_from_context(rdpContext* context) { return &session_of(context)->graphics; }
static BOOL paint_end(rdpContext* context);
static void graphics_notify(rdpContext* context, int mode) {
    Session* s = session_of(context);
    __android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "graphics_mode=%d", mode);
    (*s->worker_env)->CallVoidMethod(s->worker_env, s->callback, s->graphics_cb, mode);
    if ((*s->worker_env)->ExceptionCheck(s->worker_env)) { (*s->worker_env)->ExceptionClear(s->worker_env); atomic_store(&s->stopped, TRUE); }
}
static void channel_connected(void* context, const ChannelConnectedEventArgs* args) {
    if (args) __android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "channel_connected name=%s interface=%d", args->name, args->pInterface != NULL);
    if (args && strcmp(args->name, CLIPRDR_SVC_CHANNEL_NAME) == 0 && args->pInterface) {
        calabi_clip_connected(&session_of(context)->clipboard, args->pInterface);
    }
    calabi_gfx_connected(context, args);
}
static void channel_disconnected(void* context, const ChannelDisconnectedEventArgs* args) {
    if (args && strcmp(args->name, CLIPRDR_SVC_CHANNEL_NAME) == 0)
        calabi_clip_disconnected(&session_of(context)->clipboard);
    calabi_gfx_disconnected(context, args);
}
static BOOL load_static(rdpContext* ctx, const char* name) {
    PVIRTUALCHANNELENTRY entry = freerdp_load_channel_addin_entry(name, NULL, NULL,
        FREERDP_ADDIN_CHANNEL_STATIC | FREERDP_ADDIN_CHANNEL_ENTRYEX);
    PVIRTUALCHANNELENTRYEX extended = WINPR_FUNC_PTR_CAST(entry, PVIRTUALCHANNELENTRYEX);
    return extended && freerdp_channels_client_load_ex(ctx->channels, ctx->settings, extended, NULL) == CHANNEL_RC_OK;
}
// FreeRDP 3 rebuilds context->channels after PreConnect. Load into the final manager.
static BOOL load_channels(freerdp* instance) {
    rdpContext* ctx = instance->context;
    Session* s = session_of(ctx);
    if (!s->subscribed) {
        if (PubSub_SubscribeChannelConnected(ctx->pubSub, channel_connected) != CHANNEL_RC_OK ||
            PubSub_SubscribeChannelDisconnected(ctx->pubSub, channel_disconnected) != CHANNEL_RC_OK) return FALSE;
        s->subscribed = TRUE;
    }
    if (!load_static(ctx, CLIPRDR_SVC_CHANNEL_NAME)) return FALSE;
    if (s->graphics.enabled) {
        __android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "gfx_advertise avc=%d avc444=%d", freerdp_settings_get_bool(ctx->settings, FreeRDP_GfxH264), freerdp_settings_get_bool(ctx->settings, FreeRDP_GfxAVC444));
        const char* args[] = {RDPGFX_CHANNEL_NAME};
        if (!freerdp_client_add_dynamic_channel(ctx->settings, 1, args) || !load_static(ctx, "drdynvc")) {
            atomic_store(&s->graphics.failed, TRUE); return FALSE;
        }
    }
    return TRUE;
}
static void state(Session* s, int code, const char* text) {
    JNIEnv* env = s->worker_env;
    jstring msg = (*env)->NewStringUTF(env, text ? text : "");
    (*env)->CallVoidMethod(env, s->callback, s->state_cb, code, msg);
    (*env)->DeleteLocalRef(env, msg);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); atomic_store(&s->stopped, 1); }
}

// Bind to the VPN's exact assigned address. No LAN/public fallback or protected socket.
static int mesh_connect(rdpContext* context, rdpSettings* settings, const char* host, int port, DWORD timeout) {
    (void)settings;
    Session* s = session_of(context);
    struct sockaddr_in src = {.sin_family = AF_INET}, dst = {.sin_family = AF_INET};
    if (!host || strcmp(host, s->host) || port != s->port ||
        inet_pton(AF_INET, s->local, &src.sin_addr) != 1 || inet_pton(AF_INET, host, &dst.sin_addr) != 1) return -1;
    dst.sin_port = htons((uint16_t)s->port);
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    int yes = 1, idle = 5, interval = 3, retries = 3, ack = 12000;
    (void)setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &yes, sizeof(yes));
    (void)setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &idle, sizeof(idle));
    (void)setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &interval, sizeof(interval));
    (void)setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &retries, sizeof(retries));
    (void)setsockopt(fd, IPPROTO_TCP, TCP_USER_TIMEOUT, &ack, sizeof(ack));
    if (bind(fd, (struct sockaddr*)&src, sizeof(src)) || fcntl(fd, F_SETFL, O_NONBLOCK)) goto failed;
    if (connect(fd, (struct sockaddr*)&dst, sizeof(dst)) < 0) {
        if (errno != EINPROGRESS) goto failed;
        ULONGLONG deadline = GetTickCount64() + (timeout && timeout < 10000 ? timeout : 10000);
        if (s->repair_deadline && s->repair_deadline < deadline) deadline = s->repair_deadline;
        int result = 0, error = 0;
        struct pollfd pfd = {.fd = fd, .events = POLLOUT};
        while (!atomic_load(&s->stopped) && GetTickCount64() < deadline && (result = poll(&pfd, 1, 100)) == 0) {}
        socklen_t n = sizeof(error);
        if (result <= 0 || atomic_load(&s->stopped) || getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &n) || error) goto failed;
    }
    return fd;
failed:
    close(fd);
    return -1;
}

static int verify(freerdp* instance, const BYTE* data, size_t length, const char* host, UINT16 port, DWORD flags) {
    Session* s = session_of(instance->context);
    s->certificate_rejected = 1;
    if (atomic_load(&s->stopped) || !host || strcmp(host, s->host) || port != s->port ||
        (flags & (VERIFY_CERT_FLAG_GATEWAY | VERIFY_CERT_FLAG_REDIRECT)) || length == 0 || length > 1024 * 1024) return 0;
    char* pem = calloc(length + 1, 1);
    if (!pem) return 0;
    memcpy(pem, data, length);
    rdpCertificate* cert = freerdp_certificate_new_from_pem(pem);
    free(pem);
    char* hash = cert ? freerdp_certificate_get_fingerprint_by_hash_ex(cert, "sha256", FALSE) : NULL;
    BOOL trusted = FALSE;
    if (hash && strlen(hash) == 64 && strcasecmp(hash, s->pin) == 0) {
        jstring actual = (*s->worker_env)->NewStringUTF(s->worker_env, hash);
        trusted = (*s->worker_env)->CallBooleanMethod(s->worker_env, s->callback, s->verify_cb, actual);
        (*s->worker_env)->DeleteLocalRef(s->worker_env, actual);
        if ((*s->worker_env)->ExceptionCheck(s->worker_env)) { (*s->worker_env)->ExceptionClear(s->worker_env); trusted = FALSE; }
    }
    free(hash);
    freerdp_certificate_free(cert);
    s->certificate_verified = trusted;
    s->certificate_rejected = !trusted;
    return trusted ? 2 : 0;
}

static BOOL paint_begin(rdpContext* context) {
    rdpGdi* g = context->gdi;
    if (g && g->primary && g->primary->hdc && g->primary->hdc->hwnd) g->primary->hdc->hwnd->invalid->null = TRUE;
    return TRUE;
}

static BOOL paint_end(rdpContext* context) {
    Session* s = session_of(context);
    if (s->graphics.channel && !s->graphics.committing) return TRUE;
    if (!atomic_load(&s->foreground) && !(s->awaiting_frame && atomic_load(&s->network_ready))) return TRUE;
    rdpGdi* g = context->gdi;
    if (!g || !g->primary_buffer || g->width <= 0 || g->height <= 0 ||
        (uint64_t)g->width * g->height > MAX_PIXELS || atomic_load(&s->stopped)) return FALSE;
    if (!s->graphics.committing && g->primary && g->primary->hdc && g->primary->hdc->hwnd && g->primary->hdc->hwnd->invalid->null) return TRUE;
    size_t bytes = (size_t)g->width * g->height * 4;
    pthread_mutex_lock(&s->lock);
    const BOOL resized = bytes != s->frame_bytes || s->width != g->width || s->height != g->height;
    if (bytes != s->frame_bytes) {
        BYTE* next = realloc(s->frame, bytes);
        if (!next) { pthread_mutex_unlock(&s->lock); return FALSE; }
        s->frame = next;
        s->frame_bytes = bytes;
    }
    int left = 0, top = 0, right = g->width, bottom = g->height;
    if (!s->graphics.committing && !resized && s->frames && !(s->awaiting_frame && !s->ready_at) && g->primary && g->primary->hdc && g->primary->hdc->hwnd) {
        const HGDI_RGN r = g->primary->hdc->hwnd->invalid;
        if (r && !r->null && r->x >= 0 && r->y >= 0 && r->w > 0 && r->h > 0 &&
            r->x <= g->width && r->y <= g->height && r->w <= g->width - r->x && r->h <= g->height - r->y) {
            left = r->x; top = r->y; right = left + r->w; bottom = top + r->h;
        }
    }
    for (int row = top; row < bottom; row++) memcpy(s->frame + ((size_t)row * g->width + left) * 4,
        g->primary_buffer + (size_t)row * g->stride + left * 4, (size_t)(right - left) * 4);
    s->copied_bytes += (uint64_t)(right - left) * (bottom - top) * 4;
    s->width = g->width; s->height = g->height; s->frames++;
    if (s->awaiting_frame && !s->ready_at) s->ready_at = GetTickCount64() + (s->repairing_connection ? 3000 : 500);
    const uint64_t count = s->frames;
    pthread_mutex_unlock(&s->lock);
    if (count == 1) __android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "first_frame_ms=%llu size=%dx%d", (unsigned long long)(GetTickCount64() - s->begun), g->width, g->height);
    (*s->worker_env)->CallVoidMethod(s->worker_env, s->callback, s->frame_cb, g->width, g->height);
    if ((*s->worker_env)->ExceptionCheck(s->worker_env)) { (*s->worker_env)->ExceptionClear(s->worker_env); return FALSE; }
    return TRUE;
}

static BOOL desktop_resize(rdpContext* context) {
    Session* s = session_of(context);
    UINT32 width = freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopWidth);
    UINT32 height = freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopHeight);
    if (!width || !height || (uint64_t)width * height > MAX_PIXELS) {
        atomic_store(&s->graphics.failed, TRUE); return FALSE;
    }
    return context->gdi && gdi_resize(context->gdi, width, height);
}
static BOOL connected(freerdp* instance) {
    Session* s = session_of(instance->context);
    if (instance->context->gdi) gdi_free(instance);
    if (!s->certificate_verified || !gdi_init(instance, PIXEL_FORMAT_RGBX32)) return FALSE;
    atomic_store(&s->release_pending, TRUE);
    s->awaiting_frame = TRUE; s->ready_at = 0;
    s->connected_at = GetTickCount64();
    s->output_visible = TRUE;
    instance->context->update->BeginPaint = paint_begin;
    instance->context->update->EndPaint = paint_end;
    instance->context->update->DesktopResize = desktop_resize;
    __android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "display_negotiated size=%dx%d depth=%u graphics=%s",
        instance->context->gdi->width, instance->context->gdi->height,
        freerdp_settings_get_uint32(instance->context->settings, FreeRDP_ColorDepth), s->graphics.enabled ? "pending" : "classic");
    return TRUE;
}

static BOOL drain_inputs(Session* s) {
    for (;;) {
        pthread_mutex_lock(&s->lock);
        if (!atomic_load(&s->foreground)) { s->read_pos = s->write_pos; pthread_mutex_unlock(&s->lock); return TRUE; }
        if (s->read_pos == s->write_pos) { pthread_mutex_unlock(&s->lock); return TRUE; }
        Input in = s->queue[s->read_pos];
        s->read_pos = (s->read_pos + 1) % QUEUE_SIZE;
        pthread_mutex_unlock(&s->lock);
        BOOL ok;
        if (in.kind == 0) ok = freerdp_input_send_mouse_event(s->rdp->context->input, in.flags, in.x, in.y);
        else if (in.kind == 1) ok = freerdp_input_send_keyboard_event(s->rdp->context->input, in.flags, in.x);
        else ok = freerdp_input_send_unicode_keyboard_event(s->rdp->context->input, in.flags, in.x);
        if (!ok) return FALSE;
        pthread_mutex_lock(&s->lock); s->input_count++; pthread_mutex_unlock(&s->lock);
        if (in.kind == 0 && (in.flags & 0x7000)) {
            const UINT16 button = in.flags & 0x7000;
            if (in.flags & 0x8000) s->buttons |= button; else s->buttons &= ~button;
        }
        if (in.kind == 0) { s->pointer_x = in.x; s->pointer_y = in.y; }
        if (in.kind == 1) s->keys[(in.x & 255) | ((in.flags & 0x100) ? 256 : 0)] = !(in.flags & 0x8000);
        if (in.kind == 2) s->unicode_down = (in.flags & 0x8000) ? 0 : in.x;
    }
}

// Worker-thread only: release anything actually sent, including a half-drained chord.
static BOOL update_visibility(Session* s) {
    const BOOL visible = atomic_load(&s->foreground) || (s->awaiting_frame && atomic_load(&s->network_ready));
    const BOOL release = atomic_exchange(&s->release_pending, FALSE);
    int released_keys = 0, released_buttons = 0;
    if (visible == s->output_visible && !release) return TRUE;
    if (!visible || release) {
        for (int i = 0; i < 512; i++) if (s->keys[i]) {
            if (!freerdp_input_send_keyboard_event(s->rdp->context->input, 0x8000 | (i > 255 ? 0x100 : 0), i & 255)) return FALSE;
            s->keys[i] = FALSE;
            released_keys++;
        }
        for (UINT16 button = 0x1000; button <= 0x4000; button <<= 1) if (s->buttons & button) {
            if (!freerdp_input_send_mouse_event(s->rdp->context->input, button, s->pointer_x, s->pointer_y)) return FALSE;
            released_buttons++;
        }
        s->buttons = 0;
        if (s->unicode_down && !freerdp_input_send_unicode_keyboard_event(s->rdp->context->input, 0x8000, s->unicode_down)) return FALSE;
        s->unicode_down = 0;
    }
    if (!gdi_send_suppress_output(s->rdp->context->gdi, !visible)) return FALSE;
    if (visible) {
        // The previous TCP stream may have acknowledged a release locally but
        // lost it on the uplink. Reset remote modifiers on every focus restore.
        const int modifiers[] = {0x1d, 0x11d, 0x2a, 0x36, 0x38, 0x138, 0x15b, 0x15c};
        for (unsigned i = 0; i < sizeof(modifiers) / sizeof(modifiers[0]); i++) {
            int scan = modifiers[i];
            if (!freerdp_input_send_keyboard_event(s->rdp->context->input, 0x8000 | (scan > 255 ? 0x100 : 0), scan & 255)) return FALSE;
            s->keys[scan] = FALSE;
        }
        if (!freerdp_input_send_focus_in_event(s->rdp->context->input, 0)) return FALSE;
        const RECTANGLE_16 rect = { .left = 0, .top = 0,
            .right = (UINT16)(freerdp_settings_get_uint32(s->rdp->context->settings, FreeRDP_DesktopWidth) - 1),
            .bottom = (UINT16)(freerdp_settings_get_uint32(s->rdp->context->settings, FreeRDP_DesktopHeight) - 1) };
        if (!s->rdp->context->update->RefreshRect(s->rdp->context, 1, &rect)) return FALSE;
    }
    s->output_visible = visible;
    __android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "output_visible=%d released_keys=%d released_buttons=%d frames=%llu", visible, released_keys, released_buttons, (unsigned long long)s->frames);
    return TRUE;
}

// Only retry transport failures; rejected credentials and server policy always terminate.
static BOOL repairable(Session* s, BOOL forced) {
    if (s->certificate_rejected || atomic_load(&s->stopped) || freerdp_error_info(s->rdp) != ERRINFO_SUCCESS) return FALSE;
    const UINT32 error = freerdp_get_last_error(s->rdp->context);
    if (forced && error == FREERDP_ERROR_SUCCESS) return TRUE;
    // Losing the uplink can make a best-effort release set the abort event.
    // Explicit cancellation is distinguished by stopped, checked above.
    if (error == FREERDP_ERROR_CONNECT_CANCELLED) return TRUE;
    switch (error) {
        case FREERDP_ERROR_CONNECT_TRANSPORT_FAILED:
        case FREERDP_ERROR_CONNECT_FAILED:
        case FREERDP_ERROR_MCS_CONNECT_INITIAL_ERROR:
        case FREERDP_ERROR_SECURITY_NEGO_CONNECT_FAILED:
        case FREERDP_ERROR_TLS_CONNECT_FAILED: return TRUE;
        default: return FALSE;
    }
}
static int authority(Session* s) {
    jint result = (*s->worker_env)->CallIntMethod(s->worker_env, s->callback, s->recovery_cb);
    if ((*s->worker_env)->ExceptionCheck(s->worker_env)) { (*s->worker_env)->ExceptionClear(s->worker_env); return -1; }
    return result;
}
// Rebuild only the local graphics attempt; all connection settings (including
// credentials) stay in native memory and every new TLS socket is verified again.
static BOOL rebuild_classic(Session* s) {
    pthread_mutex_lock(&s->lock);
    if (s->rdp) {
        freerdp_disconnect(s->rdp);
        if (s->rdp->context && s->rdp->context->gdi) gdi_free(s->rdp);
        if (s->rdp->context) freerdp_context_free(s->rdp);
        freerdp_free(s->rdp);
    }
    s->rdp = NULL; s->subscribed = FALSE; s->graphics.channel = NULL;
    s->graphics.enabled = FALSE; atomic_store(&s->graphics.failed, FALSE);
    s->certificate_verified = s->certificate_rejected = 0;
    BOOL ok = !atomic_load(&s->stopped) && (s->rdp = freerdp_new()) &&
        (s->rdp->ContextSize = sizeof(Context), freerdp_context_new(s->rdp));
    if (ok) {
        ((Context*)s->rdp->context)->session = s;
        s->rdp->PostConnect = connected; s->rdp->LoadChannels = load_channels; s->rdp->VerifyX509Certificate = verify;
        ok = freerdp_settings_copy(s->rdp->context->settings, s->initial_settings) &&
            calabi_gfx_configure(&s->graphics, s->rdp->context->settings, FALSE);
        const rdpTransportIo* defaults = freerdp_get_io_callbacks(s->rdp->context);
        rdpTransportIo io = *defaults; io.TCPConnect = mesh_connect;
        ok = ok && freerdp_set_io_callbacks(s->rdp->context, &io);
    }
    pthread_mutex_unlock(&s->lock);
    return ok;
}
static void* worker(void* value) {
    Session* s = value;
    if ((*s->vm)->AttachCurrentThread(s->vm, &s->worker_env, NULL) != JNI_OK) return NULL;
    s->begun = GetTickCount64();
    s->initial_settings = freerdp_settings_clone(s->rdp->context->settings);
    BOOL ok = s->initial_settings && !atomic_load(&s->stopped) && freerdp_connect(s->rdp);
    BOOL had_connection = ok;
    int terminal = 2;
    unsigned attempts = 0;
    while (!atomic_load(&s->stopped)) {
        if (ok) {
            s->repair_deadline = 0; attempts = 0;
            BOOL announced = FALSE;
            while (!atomic_load(&s->stopped) && !atomic_load(&s->repair_requested) && !atomic_load(&s->graphics.failed) &&
                   !freerdp_shall_disconnect_context(s->rdp->context)) {
                HANDLE events[64];
                DWORD n = freerdp_get_event_handles(s->rdp->context, events, 64);
                const BOOL active = freerdp_is_active_state(s->rdp->context);
                if (s->graphics.enabled && s->awaiting_frame && !s->ready_at &&
                    GetTickCount64() - s->connected_at >= 8000) { atomic_store(&s->graphics.failed, TRUE); break; }
                if (active && !announced && s->ready_at && GetTickCount64() >= s->ready_at) {
                    // Keep fresh output enabled while the UI completes its
                    // authorization handoff. Suppress/resume here can race the
                    // first IME commit with Windows restoring input focus.
                    announced = TRUE; atomic_store(&s->release_pending, TRUE); state(s, 1, "connected");
                    __android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "protocol_active frames=%llu", (unsigned long long)s->frames);
                }
                if (announced && atomic_load(&s->foreground)) s->awaiting_frame = FALSE;
                if (!n || (active && (!update_visibility(s) || !drain_inputs(s)))) break;
                calabi_clip_poll(&s->clipboard, s->worker_env, s->callback, s->clipboard_cb);
                DWORD wait = WaitForMultipleObjects(n, events, FALSE, 16);
                pthread_mutex_lock(&s->lock); s->event_checks++; pthread_mutex_unlock(&s->lock);
                if (wait == WAIT_FAILED || !freerdp_check_event_handles(s->rdp->context)) break;
            }
        }
        const BOOL forced = atomic_exchange(&s->repair_requested, FALSE);
        const UINT32 info = freerdp_error_info(s->rdp);
        const BOOL graphics_error = info == ERRINFO_GRAPHICS_MODE_NOT_SUPPORTED ||
            info == ERRINFO_GRAPHICS_SUBSYSTEM_RESET_FAILED || info == ERRINFO_GRAPHICS_SUBSYSTEM_FAILED;
        if (s->graphics.enabled && !s->graphics.fallback && s->certificate_verified && !s->certificate_rejected &&
            !atomic_load(&s->stopped) && (info == ERRINFO_SUCCESS || graphics_error) &&
            (atomic_load(&s->graphics.failed) || graphics_error)) {
            s->graphics.fallback = TRUE;
            atomic_store(&s->foreground, FALSE); atomic_store(&s->release_pending, TRUE);
            state(s, 6, "graphics_fallback"); graphics_notify(s->rdp->context, 3);
            const uint64_t deadline = GetTickCount64() + 60000;
            int allowed = 0;
            while (!atomic_load(&s->stopped) && GetTickCount64() < deadline) {
                if (atomic_load(&s->network_ready)) {
                    allowed = authority(s);
                    if (allowed != 0) break;
                }
                Sleep(100);
            }
            if (atomic_load(&s->stopped)) break;
            if (allowed < 0) { terminal = 5; break; }
            if (!allowed) { terminal = 9; break; }
            if (!rebuild_classic(s)) break;
            __android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "graphics_classic_retry=1");
            s->awaiting_frame = TRUE; s->ready_at = 0; s->repairing_connection = TRUE;
            ok = !atomic_load(&s->stopped) && freerdp_connect(s->rdp);
            had_connection |= ok;
            continue;
        }
        __android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "repair_decision error=0x%08x info=0x%08x forced=%d stopped=%d",
            freerdp_get_last_error(s->rdp->context), freerdp_error_info(s->rdp), forced, atomic_load(&s->stopped));
        if (!had_connection || !repairable(s, forced)) break;
        atomic_store(&s->foreground, FALSE); atomic_store(&s->release_pending, TRUE);
        (void)update_visibility(s);
        pthread_mutex_lock(&s->lock); s->read_pos = s->write_pos; pthread_mutex_unlock(&s->lock);
        calabi_clip_disconnected(&s->clipboard);
        state(s, 6, "repair");
        if (!s->repair_deadline) s->repair_deadline = GetTickCount64() + 60000;
        const UINT32 delay = attempts == 0 ? 500 : attempts == 1 ? 2000 : attempts == 2 ? 4000 : 8000;
        const uint64_t next = GetTickCount64() + delay;
        while (!atomic_load(&s->stopped) && GetTickCount64() < s->repair_deadline &&
              (GetTickCount64() < next || !atomic_load(&s->network_ready))) Sleep(100);
        if (atomic_load(&s->stopped)) break;
        if (GetTickCount64() >= s->repair_deadline) { terminal = 9; break; }
        const int allowed = authority(s);
        if (allowed < 0) { terminal = 5; break; }
        if (!allowed) { ok = FALSE; atomic_store(&s->repair_requested, TRUE); continue; }
        attempts++;
        atomic_store(&s->repair_requested, FALSE);
        __android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "repair_attempt=%u", attempts);
        if (freerdp_get_last_error(s->rdp->context) == FREERDP_ERROR_CONNECT_CANCELLED)
            freerdp_set_last_error(s->rdp->context, FREERDP_ERROR_SUCCESS);
        // PostConnect may be retained; require new output before enabling input.
        s->awaiting_frame = TRUE; s->ready_at = 0;
        s->repairing_connection = TRUE;
        ok = !atomic_load(&s->stopped) && freerdp_reconnect(s->rdp);
        if (ok) __android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "repair_connected attempt=%u", attempts);
    }
    atomic_store(&s->foreground, FALSE); atomic_store(&s->release_pending, TRUE);
    if (ok) (void)update_visibility(s);
    UINT32 error = s->rdp && s->rdp->context ? freerdp_get_last_error(s->rdp->context) : FREERDP_ERROR_CONNECT_FAILED;
    if (s->rdp && s->rdp->context) freerdp_disconnect(s->rdp);
    char message[80];
    snprintf(message, sizeof(message), "RDP 0x%08x", error);
    __android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "session_end error=0x%08x frames=%llu", error, (unsigned long long)s->frames);
    state(s, atomic_load(&s->stopped) ? 3 : s->certificate_rejected ? 8 : terminal, message);
    (*s->vm)->DetachCurrentThread(s->vm);
    return NULL;
}

static char* string_copy(JNIEnv* env, jstring value) {
    if (!value) return strdup("");
    const jchar* text = (*env)->GetStringChars(env, value, NULL);
    char* copy = text ? ConvertWCharNToUtf8Alloc((const WCHAR*)text, (*env)->GetStringLength(env, value), NULL) : NULL;
    if (text) (*env)->ReleaseStringChars(env, value, text);
    return copy;
}

JNIEXPORT jboolean JNICALL Java_net_calabi_app_rdp_RdpNative_initialize(JNIEnv* env, jobject self, jstring directory, jstring decoder) {
    (void)self;
    char* home = string_copy(env, directory);
    BOOL ok = home && home[0] == '/' && setenv("HOME", home, 1) == 0;
    char* codec = string_copy(env, decoder);
    ok = ok && codec && strlen(codec) < 256 && setenv("CALABI_RDP_ANDROID_AVC_DECODER", codec, 1) == 0;
    free(codec);
    if (ok) (void)freerdp_register_addin_provider(freerdp_channels_load_static_addin_entry, 0);
    free(home);
    return ok;
}

JNIEXPORT jlong JNICALL Java_net_calabi_app_rdp_RdpNative_create(JNIEnv* env, jobject self, jobject callback,
        jstring host, jstring local, jstring pin, jstring user, jstring domain, jstring password, jint port) {
    (void)self;
    if (port < 1 || port > 65535) return 0;
    Session* s = calloc(1, sizeof(Session));
    if (!s) return 0;
    s->port = port;
    atomic_init(&s->foreground, TRUE);
    atomic_init(&s->network_ready, TRUE);
    atomic_init(&s->repair_requested, FALSE);
    s->output_visible = TRUE;
    pthread_mutex_init(&s->lock, NULL);
    calabi_clip_init(&s->clipboard);
    s->graphics.present = paint_end; s->graphics.notify = graphics_notify;
    (*env)->GetJavaVM(env, &s->vm);
    s->callback = (*env)->NewGlobalRef(env, callback);
    jclass cls = (*env)->GetObjectClass(env, callback);
    s->state_cb = (*env)->GetMethodID(env, cls, "onNativeState", "(ILjava/lang/String;)V");
    s->frame_cb = (*env)->GetMethodID(env, cls, "onNativeFrame", "(II)V");
    s->verify_cb = (*env)->GetMethodID(env, cls, "verifyNativeCertificate", "(Ljava/lang/String;)Z");
    s->clipboard_cb = (*env)->GetMethodID(env, cls, "onNativeClipboard", "(ILjava/lang/String;I)V");
    s->recovery_cb = (*env)->GetMethodID(env, cls, "recoveryAuthorization", "()I");
    s->graphics_cb = (*env)->GetMethodID(env, cls, "onNativeGraphics", "(I)V");
    (*env)->DeleteLocalRef(env, cls);
    char *h = string_copy(env, host), *l = string_copy(env, local), *p = string_copy(env, pin);
    char *u = string_copy(env, user), *d = string_copy(env, domain), *pw = string_copy(env, password);
    BOOL ok = h && l && p && u && d && pw && strlen(h) < sizeof(s->host) && strlen(l) < sizeof(s->local) && strlen(p) == 64;
    struct in_addr parsed;
    if (ok) ok = inet_pton(AF_INET, h, &parsed) == 1 && inet_pton(AF_INET, l, &parsed) == 1 && strspn(p, "0123456789abcdefABCDEF") == 64;
    int stage = ok ? 1 : 0;
    if (ok) { strcpy(s->host, h); strcpy(s->local, l); strcpy(s->pin, p); }
    WLog_SetLogLevel(WLog_GetRoot(), WLOG_ERROR);
    s->rdp = freerdp_new();
    if (!s->rdp) ok = FALSE;
    if (ok) {
        s->rdp->ContextSize = sizeof(Context);
        ok = freerdp_context_new(s->rdp);
        stage = ok ? 2 : 1;
    }
    if (ok) {
        ((Context*)s->rdp->context)->session = s;
        s->rdp->PostConnect = connected;
        s->rdp->LoadChannels = load_channels;
        s->rdp->VerifyX509Certificate = verify;
        rdpSettings* settings = s->rdp->context->settings;
        const rdpTransportIo* defaults = freerdp_get_io_callbacks(s->rdp->context);
        rdpTransportIo transport = *defaults;
        transport.TCPConnect = mesh_connect;
        ok = freerdp_set_io_callbacks(s->rdp->context, &transport) &&
             freerdp_settings_set_string(settings, FreeRDP_ServerHostname, h) &&
             freerdp_settings_set_uint32(settings, FreeRDP_ServerPort, (UINT32)s->port) &&
             freerdp_settings_set_string(settings, FreeRDP_Username, u) &&
             freerdp_settings_set_string(settings, FreeRDP_Domain, d) &&
             freerdp_settings_set_string(settings, FreeRDP_Password, pw) &&
             freerdp_settings_set_uint32(settings, FreeRDP_DesktopWidth, 1280) &&
             freerdp_settings_set_uint32(settings, FreeRDP_DesktopHeight, 720) &&
             freerdp_settings_set_uint32(settings, FreeRDP_ColorDepth, 32) &&
             freerdp_settings_set_uint32(settings, FreeRDP_TcpConnectTimeout, 10000) &&
             freerdp_settings_set_bool(settings, FreeRDP_NlaSecurity, TRUE) &&
             freerdp_settings_set_bool(settings, FreeRDP_TlsSecurity, FALSE) &&
             freerdp_settings_set_bool(settings, FreeRDP_RdpSecurity, FALSE) &&
             freerdp_settings_set_bool(settings, FreeRDP_ExternalCertificateManagement, TRUE) &&
             freerdp_settings_set_bool(settings, FreeRDP_SupportGraphicsPipeline, FALSE) &&
             freerdp_settings_set_bool(settings, FreeRDP_AutoReconnectionEnabled, TRUE) &&
             freerdp_settings_set_bool(settings, FreeRDP_RedirectClipboard, TRUE) &&
             freerdp_settings_set_uint32(settings, FreeRDP_ClipboardFeatureMask, CLIPRDR_FLAG_LOCAL_TO_REMOTE | CLIPRDR_FLAG_REMOTE_TO_LOCAL) &&
             freerdp_settings_set_bool(settings, FreeRDP_SupportMultitransport, FALSE);
        if (ok) stage = 3;
    }
    free(h); free(l); free(p); free(u); free(d);
    if (pw) { volatile char* wipe = pw; size_t n = strlen(pw); while (n--) *wipe++ = 0; free(pw); }
    if (!ok) {
        __android_log_print(ANDROID_LOG_ERROR, "CalabiRdp", "native_init_failed stage=%d", stage);
        if (s->rdp) { if (s->rdp->context) freerdp_context_free(s->rdp); freerdp_free(s->rdp); }
        calabi_clip_free(&s->clipboard);
        (*env)->DeleteGlobalRef(env, s->callback); pthread_mutex_destroy(&s->lock); free(s); return 0;
    }
    return (jlong)(uintptr_t)s;
}

JNIEXPORT jboolean JNICALL Java_net_calabi_app_rdp_RdpNative_start(JNIEnv* env, jobject self, jlong handle) {
    (void)env; (void)self; Session* s = (Session*)(uintptr_t)handle;
    if (!s || s->started) return JNI_FALSE;
    s->started = pthread_create(&s->thread, NULL, worker, s) == 0;
    return s->started;
}

JNIEXPORT jboolean JNICALL Java_net_calabi_app_rdp_RdpNative_configure(JNIEnv* env, jobject self, jlong handle, jint quality, jboolean hardware) {
    (void)env; (void)self; Session* s = (Session*)(uintptr_t)handle;
    if (!s || s->started || quality < 0 || quality > 2) return JNI_FALSE;
    const UINT32 widths[] = {960, 1280, 1920}, heights[] = {540, 720, 1080};
    rdpSettings* settings = s->rdp->context->settings;
    const char* decoder = getenv("CALABI_RDP_ANDROID_AVC_DECODER");
    WLog_SetLogLevel(WLog_Get("com.freerdp.codec.h264"), WLOG_INFO);
    return calabi_gfx_configure(&s->graphics, settings, hardware && quality != 0 && decoder && decoder[0]) &&
        freerdp_settings_set_uint32(settings, FreeRDP_DesktopWidth, widths[quality]) &&
        freerdp_settings_set_uint32(settings, FreeRDP_DesktopHeight, heights[quality]) &&
        freerdp_settings_set_uint32(settings, FreeRDP_ColorDepth, quality == 0 ? 16 : 32) &&
        freerdp_settings_set_bool(settings, FreeRDP_DisableWallpaper, TRUE) &&
        freerdp_settings_set_bool(settings, FreeRDP_DisableFullWindowDrag, quality == 0) &&
        freerdp_settings_set_bool(settings, FreeRDP_DisableMenuAnims, TRUE) &&
        freerdp_settings_set_bool(settings, FreeRDP_DisableThemes, quality == 0) &&
        freerdp_settings_set_uint32(settings, FreeRDP_PerformanceFlags,
            PERF_DISABLE_WALLPAPER | PERF_DISABLE_MENUANIMATIONS |
            (quality == 0 ? PERF_DISABLE_FULLWINDOWDRAG | PERF_DISABLE_THEMING : 0));
}

JNIEXPORT jlongArray JNICALL Java_net_calabi_app_rdp_RdpNative_graphicsStats(JNIEnv* env, jobject self, jlong handle) {
    (void)self; Session* s = (Session*)(uintptr_t)handle;
    if (!s) return NULL;
    jlong values[] = {atomic_load(&s->graphics.enabled), atomic_load(&s->graphics.fallback),
        atomic_load(&s->graphics.frames), atomic_load(&s->graphics.avc)};
    jlongArray result = (*env)->NewLongArray(env, 4);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, 4, values);
    return result;
}
JNIEXPORT void JNICALL Java_net_calabi_app_rdp_RdpNative_failGraphics(JNIEnv* env, jobject self, jlong handle) {
    (void)env; (void)self; Session* s = (Session*)(uintptr_t)handle;
    if (s && s->graphics.enabled) atomic_store(&s->graphics.failed, TRUE);
}

JNIEXPORT jlongArray JNICALL Java_net_calabi_app_rdp_RdpNative_stats(JNIEnv* env, jobject self, jlong handle) {
    (void)self; Session* s = (Session*)(uintptr_t)handle;
    if (!s) return NULL;
    pthread_mutex_lock(&s->lock);
    const jlong values[] = {s->width, s->height, s->frames, s->rendered, s->input_count,
        s->event_checks, s->copied_bytes, s->begun ? GetTickCount64() - s->begun : 0};
    pthread_mutex_unlock(&s->lock);
    jlongArray result = (*env)->NewLongArray(env, 8);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, 8, values);
    return result;
}

JNIEXPORT void JNICALL Java_net_calabi_app_rdp_RdpNative_stop(JNIEnv* env, jobject self, jlong handle) {
    (void)env; (void)self; Session* s = (Session*)(uintptr_t)handle;
    if (s) {
        atomic_store(&s->stopped, 1);
        pthread_mutex_lock(&s->lock);
        if (s->rdp && s->rdp->context) freerdp_abort_connect_context(s->rdp->context);
        pthread_mutex_unlock(&s->lock);
    }
}

JNIEXPORT void JNICALL Java_net_calabi_app_rdp_RdpNative_foreground(JNIEnv* env, jobject self, jlong handle, jboolean foreground) {
    (void)env; (void)self; Session* s = (Session*)(uintptr_t)handle;
    if (!s) return;
    pthread_mutex_lock(&s->lock);
    atomic_store(&s->foreground, foreground);
    if (!foreground) { s->read_pos = s->write_pos; atomic_store(&s->release_pending, TRUE); }
    pthread_mutex_unlock(&s->lock);
}

JNIEXPORT void JNICALL Java_net_calabi_app_rdp_RdpNative_network(JNIEnv* env, jobject self, jlong handle, jboolean available, jboolean changed) {
    (void)env; (void)self; Session* s = (Session*)(uintptr_t)handle;
    if (!s) return;
    atomic_store(&s->network_ready, available);
    if (changed) atomic_store(&s->repair_requested, TRUE);
}

JNIEXPORT void JNICALL Java_net_calabi_app_rdp_RdpNative_destroy(JNIEnv* env, jobject self, jlong handle) {
    (void)self; Session* s = (Session*)(uintptr_t)handle;
    if (!s) return;
    atomic_store(&s->stopped, 1);
    if (s->started) pthread_join(s->thread, NULL);
    if (s->rdp) {
        if (s->rdp->context && s->rdp->context->gdi) gdi_free(s->rdp);
        if (s->rdp->context) freerdp_context_free(s->rdp);
        freerdp_free(s->rdp);
    }
    calabi_clip_free(&s->clipboard);
    freerdp_settings_free(s->initial_settings);
    (*env)->DeleteGlobalRef(env, s->callback);
    free(s->frame); pthread_mutex_destroy(&s->lock); free(s);
}

JNIEXPORT jboolean JNICALL Java_net_calabi_app_rdp_RdpNative_clipboard(JNIEnv* env, jobject self,
        jlong handle, jint command, jstring text, jint requestId) {
    (void)self; Session* s = (Session*)(uintptr_t)handle;
    return s && !atomic_load(&s->stopped) && (command == 3 || atomic_load(&s->foreground)) && calabi_clip_request(&s->clipboard, env, command, text, requestId);
}

JNIEXPORT jboolean JNICALL Java_net_calabi_app_rdp_RdpNative_input(JNIEnv* env, jobject self, jlong handle, jint kind, jint flags, jint x, jint y) {
    (void)env; (void)self; Session* s = (Session*)(uintptr_t)handle;
    if (!s || atomic_load(&s->stopped) || kind < 0 || kind > 2 || x < 0 || x > 65535 || y < 0 || y > 65535) return JNI_FALSE;
    pthread_mutex_lock(&s->lock);
    if (!atomic_load(&s->foreground)) { pthread_mutex_unlock(&s->lock); return JNI_FALSE; }
    // Absolute motion has no intermediate action to preserve. Replace only an
    // adjacent motion; never cross a button, wheel, or keyboard transition.
    if (kind == 0 && flags == 0x0800 && s->read_pos != s->write_pos) {
        unsigned previous = (s->write_pos + QUEUE_SIZE - 1) % QUEUE_SIZE;
        if (s->queue[previous].kind == 0 && s->queue[previous].flags == 0x0800) {
            s->queue[previous] = (Input){kind, flags, x, y};
            pthread_mutex_unlock(&s->lock);
            return JNI_TRUE;
        }
    }
    unsigned next = (s->write_pos + 1) % QUEUE_SIZE;
    BOOL ok = next != s->read_pos;
    if (ok) { s->queue[s->write_pos] = (Input){kind, flags, x, y}; s->write_pos = next; }
    else {
        s->read_pos = s->write_pos;
        atomic_store(&s->foreground, FALSE);
        atomic_store(&s->release_pending, TRUE);
        atomic_store(&s->repair_requested, TRUE);
    }
    pthread_mutex_unlock(&s->lock);
    // Never replay an overflowed batch or silently lose a release: repair the
    // stream and reset remote modifiers before accepting any new input.
    return ok;
}

JNIEXPORT jboolean JNICALL Java_net_calabi_app_rdp_RdpNative_blit(JNIEnv* env, jobject self, jlong handle, jobject bitmap) {
    (void)self; Session* s = (Session*)(uintptr_t)handle;
    if (!s || atomic_load(&s->stopped)) return JNI_FALSE;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    pthread_mutex_lock(&s->lock);
    BOOL ok = s->frame && info.width == (UINT32)s->width && info.height == (UINT32)s->height;
    void* pixels = NULL;
    if (ok && AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS) {
        for (int row = 0; row < s->height; row++) memcpy((BYTE*)pixels + row * info.stride, s->frame + (size_t)row * s->width * 4, (size_t)s->width * 4);
        AndroidBitmap_unlockPixels(env, bitmap);
        s->rendered = s->frames;
    } else ok = FALSE;
    pthread_mutex_unlock(&s->lock);
    return ok;
}
