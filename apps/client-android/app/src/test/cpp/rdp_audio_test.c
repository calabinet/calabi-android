#include "rdp_audio.h"
#include <assert.h>
#include <string.h>
#include <freerdp/channels/channels.h>
#include <freerdp/client/channels.h>
#include <freerdp/client/rdpsnd.h>

static unsigned loads;
static rdpsndDevicePlugin* playback;

static void register_playback(rdpsndPlugin* rdpsnd, rdpsndDevicePlugin* device) {
    (void)rdpsnd;
    assert(device && !playback);
    playback = device;
}

static BOOL check_channel(rdpContext* ctx, const char* channel, void* data) {
    if (++loads == 1) {
        assert(strcmp(channel, "rdpdr") == 0 && data == ctx->settings);
        return TRUE;
    }

    assert(strcmp(channel, RDPSND_CHANNEL_NAME) == 0);
    ADDIN_ARGV* args = data;
    assert(args && args->argc == 2 && strcmp(args->argv[1], "sys:opensles") == 0);
    const DWORD flags = FREERDP_ADDIN_CHANNEL_STATIC | FREERDP_ADDIN_CHANNEL_ENTRYEX;
    PFREERDP_RDPSND_DEVICE_ENTRY entry = WINPR_FUNC_PTR_CAST(
        freerdp_load_channel_addin_entry(RDPSND_CHANNEL_NAME, "opensles", NULL, flags),
        PFREERDP_RDPSND_DEVICE_ENTRY);
    assert(entry);
    FREERDP_RDPSND_DEVICE_ENTRY_POINTS points = { .pRegisterRdpsndDevice = register_playback, .args = args };
    assert(entry(&points) == CHANNEL_RC_OK);
    assert(playback && playback->Open && playback->Free);
    playback->Free(playback);
    playback = NULL;
    return TRUE;
}

int main(void) {
    freerdp* client = freerdp_new();
    assert(client && freerdp_context_new(client));
    freerdp_register_addin_provider(freerdp_channels_load_static_addin_entry, 0);
    rdpSettings* settings = client->context->settings;
    assert(freerdp_settings_set_bool(settings, FreeRDP_AudioPlayback, FALSE));
    assert(calabi_rdp_load_audio(client->context, check_channel) && loads == 0);
    assert(freerdp_settings_set_bool(settings, FreeRDP_AudioPlayback, TRUE));
    for (unsigned attempt = 0; attempt < 2; attempt++) {
        loads = 0;
        assert(calabi_rdp_load_audio(client->context, check_channel) && loads == 2);
    }
    freerdp_context_free(client);
    freerdp_free(client);
    return 0;
}
