#include "rdp_graphics.h"
#include <android/log.h>
#include <freerdp/gdi/gfx.h>
#include <freerdp/codec/h264.h>
#include <freerdp/client/cmdline.h>
#include <string.h>

static CalabiGraphics* graphics(RdpgfxClientContext* channel) {
    rdpGdi* gdi = channel->custom;
    return calabi_gfx_from_context(gdi->context);
}
static UINT confirm(RdpgfxClientContext* channel, const RDPGFX_CAPS_CONFIRM_PDU* pdu) {
    CalabiGraphics* g = graphics(channel);
    const UINT rc = g->caps ? g->caps(channel, pdu) : CHANNEL_RC_OK;
    if (rc == CHANNEL_RC_OK && pdu->capsSet)
        __android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "gfx_caps version=%u flags=%u", pdu->capsSet->version, pdu->capsSet->flags);
    return rc;
}
static UINT present(RdpgfxClientContext* channel) {
    CalabiGraphics* g = graphics(channel);
    rdpGdi* gdi = channel->custom;
    g->committing = TRUE;
    BOOL ok = g->present(gdi->context);
    g->committing = FALSE;
    if (!ok) { atomic_store(&g->failed, TRUE); return ERROR_INTERNAL_ERROR; }
    atomic_fetch_add(&g->frames, 1);
    return CHANNEL_RC_OK;
}
static UINT surface(RdpgfxClientContext* channel, const RDPGFX_SURFACE_COMMAND* cmd) {
    CalabiGraphics* g = graphics(channel);
    if (cmd->codecId == RDPGFX_CODECID_AVC444 || cmd->codecId == RDPGFX_CODECID_AVC444v2) {
        atomic_store(&g->failed, TRUE); return ERROR_NOT_SUPPORTED;
    }
    UINT rc = g->surface(channel, cmd);
    if (rc != CHANNEL_RC_OK) { atomic_store(&g->failed, TRUE); return rc; }
    if (cmd->codecId == RDPGFX_CODECID_AVC420 || cmd->codecId == RDPGFX_CODECID_AVC444 || cmd->codecId == RDPGFX_CODECID_AVC444v2) {
        if (atomic_fetch_add(&g->avc, 1) == 0) {
            __android_log_print(ANDROID_LOG_INFO, "CalabiRdp", "gfx_avc_first codec=%u", cmd->codecId);
            g->notify(((rdpGdi*)channel->custom)->context, 2);
        }
    }
    if (!((rdpGdi*)channel->custom)->inGfxFrame) rc = present(channel);
    return rc;
}
static UINT end_frame(RdpgfxClientContext* channel, const RDPGFX_END_FRAME_PDU* pdu) {
    CalabiGraphics* g = graphics(channel);
    const UINT rc = g->end(channel, pdu);
    if (rc != CHANNEL_RC_OK) { atomic_store(&g->failed, TRUE); return rc; }
    return present(channel);
}
static UINT reject_open(RdpgfxClientContext* channel, BOOL* advertise, BOOL* ack) {
    (void)channel; *advertise = *ack = FALSE; return ERROR_INTERNAL_ERROR;
}
void calabi_gfx_connected(rdpContext* context, const ChannelConnectedEventArgs* args) {
    CalabiGraphics* g = calabi_gfx_from_context(context);
    if (!args || strcmp(args->name, RDPGFX_DVC_CHANNEL_NAME) || !g->enabled) return;
    RdpgfxClientContext* channel = args->pInterface;
    if (!channel || g->channel) return;
    if (!context->gdi || !gdi_graphics_pipeline_init(context->gdi, channel)) {
        freerdp_client_codecs_free(channel->codecs); channel->codecs = NULL; channel->custom = NULL;
        if (context->gdi) context->gdi->gfx = NULL;
        channel->OnOpen = reject_open; atomic_store(&g->failed, TRUE); return;
    }
    g->channel = channel; g->caps = channel->CapsConfirm; g->surface = channel->SurfaceCommand; g->end = channel->EndFrame;
    channel->CapsConfirm = confirm; channel->SurfaceCommand = surface; channel->EndFrame = end_frame;
    g->notify(context, 1);
}
void calabi_gfx_disconnected(rdpContext* context, const ChannelDisconnectedEventArgs* args) {
    CalabiGraphics* g = calabi_gfx_from_context(context);
    if (!args || strcmp(args->name, RDPGFX_DVC_CHANNEL_NAME) || !g->channel || g->channel != args->pInterface) return;
    g->channel->CapsConfirm = g->caps; g->channel->SurfaceCommand = g->surface; g->channel->EndFrame = g->end;
    if (context->gdi) { gdi_graphics_pipeline_uninit(context->gdi, g->channel); context->gdi->inGfxFrame = FALSE; }
    g->channel = NULL;
}
BOOL calabi_gfx_configure(CalabiGraphics* g, rdpSettings* settings, BOOL enabled) {
    if (enabled) {
        H264_CONTEXT* probe = h264_context_new(FALSE);
        enabled = probe != NULL;
        h264_context_free(probe);
    }
    g->enabled = enabled;
    return freerdp_settings_set_bool(settings, FreeRDP_SynchronousDynamicChannels, TRUE) &&
        freerdp_settings_set_bool(settings, FreeRDP_SupportDynamicChannels, enabled) &&
        freerdp_settings_set_bool(settings, FreeRDP_SupportGraphicsPipeline, enabled) &&
        freerdp_settings_set_bool(settings, FreeRDP_GfxH264, enabled) &&
        freerdp_settings_set_bool(settings, FreeRDP_GfxThinClient, enabled) &&
        freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444, FALSE) &&
        freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444v2, FALSE);
}
