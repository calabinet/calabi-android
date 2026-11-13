#pragma once
#include <stdatomic.h>
#include <freerdp/freerdp.h>
#include <freerdp/client/rdpgfx.h>
#include <freerdp/channels/channels.h>

typedef struct {
    RdpgfxClientContext* channel;
    pcRdpgfxCapsConfirm caps;
    pcRdpgfxSurfaceCommand surface;
    pcRdpgfxEndFrame end;
    atomic_bool enabled, fallback;
    BOOL committing;
    atomic_bool failed;
    atomic_uint_fast64_t frames, avc;
    BOOL (*present)(rdpContext*);
    void (*notify)(rdpContext*, int);
} CalabiGraphics;

CalabiGraphics* calabi_gfx_from_context(rdpContext* context);

BOOL calabi_gfx_configure(CalabiGraphics* g, rdpSettings* settings, BOOL enabled);
void calabi_gfx_connected(rdpContext* context, const ChannelConnectedEventArgs* args);
void calabi_gfx_disconnected(rdpContext* context, const ChannelDisconnectedEventArgs* args);
