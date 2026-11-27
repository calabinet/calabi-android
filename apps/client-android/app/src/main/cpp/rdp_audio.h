#pragma once

#include <freerdp/channels/rdpsnd.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/freerdp.h>
#include <freerdp/settings.h>

static BOOL calabi_rdp_load_audio(rdpContext* ctx, BOOL (*load)(rdpContext*, const char*, void*)) {
    if (!freerdp_settings_get_bool(ctx->settings, FreeRDP_AudioPlayback)) return TRUE;

    // OpenSL ES dereferences the add-in arguments during its registration.
    // Keep them in the current FreeRDP settings across channel setup and reconnect.
    const char* const options[] = { RDPSND_CHANNEL_NAME, "sys:opensles" };
    if (!freerdp_client_add_static_channel(ctx->settings, 2, options)) return FALSE;
    ADDIN_ARGV* args = freerdp_static_channel_collection_find(ctx->settings, RDPSND_CHANNEL_NAME);
    return args && load(ctx, "rdpdr", ctx->settings) && load(ctx, RDPSND_CHANNEL_NAME, args);
}
