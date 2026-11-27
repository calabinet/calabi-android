package net.calabi.app.rdp

import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.calabi.app.BuildConfig
import net.calabi.app.CoreClient
import net.calabi.app.ui.InlineNotice
import net.calabi.app.ui.NoticeTone
import net.calabi.app.ui.StatusBadge
import net.calabi.app.R
import net.calabi.app.ui.CalabiTheme
import net.calabi.app.ui.Palette
import net.calabi.app.ui.Styles
import net.calabi.app.ui.ActionRow
import net.calabi.app.ui.Group
import org.json.JSONObject

/** Internal remote desktop preview, launched from a selected Mesh device. */
class RdpActivity : ComponentActivity() {
    companion object { private var current = java.lang.ref.WeakReference<RdpActivity>(null) }
    private var session: RdpSession? = null
    private var desktopPresentation = false
    private var resumed by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dispatch IME insets to Compose as well as resizing the canvas (Android 10+).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        current.get()?.takeIf { it !== this }?.let { it.session?.close(); it.finish() }
        current = java.lang.ref.WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { CalabiTheme { Screen(intent.getStringExtra("overlay").orEmpty()) } }
    }

    @Suppress("DEPRECATION") // Android 10/EMUI needs FLAG_FULLSCREEN in addition to insets.
    private fun enterFullscreen() {
        // EMUI also needs the window flag to remove the reserved status-bar inset.
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    @android.annotation.SuppressLint("SourceLockedOrientationActivity") // User-requested portrait sign-in, separate from desktop rotation.
    @Suppress("DEPRECATION") // Clear the legacy fullscreen flag when returning to sign-in.
    private fun showLogin() {
        desktopPresentation = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && desktopPresentation) enterFullscreen()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        session?.view?.let { view -> view.post { view.refreshRemoteViewport() } }
    }

    override fun onResume() { super.onResume(); resumed = true; session?.foreground() }
    override fun onPause() {
        resumed = false
        if (isFinishing) session?.close() else session?.background()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0)
        super.onPause()
    }
    override fun onDestroy() { session?.close(); session = null; super.onDestroy() }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable private fun Screen(overlay: String) {
        val scope = rememberCoroutineScope()
        var target by remember { mutableStateOf<JSONObject?>(null) }
        var reload by remember { mutableStateOf(0) }
        var error by remember { mutableStateOf("") }
        var user by remember { mutableStateOf("") }
        var domain by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var needsMesh by remember { mutableStateOf(false) }
        var active by remember { mutableStateOf<RdpSession?>(null) }
        val store = remember { RdpCredentialStore(this) }
        var credentialScope by remember { mutableStateOf<String?>(null) }
        var rememberPassword by remember { mutableStateOf(false) }
        var saved by remember { mutableStateOf(false) }
        var clearingCredentials by remember { mutableStateOf(false) }
        var portText by rememberSaveable(overlay) { mutableStateOf("3389") }
        val selectedPort = portText.toIntOrNull()?.takeIf { it in 1..65535 }
        val loginPorts = target?.optJSONArray("login_ports")?.let { ports ->
            List(ports.length()) { ports.getInt(it) }
        }.orEmpty()
        val authorizedPort = loginPorts.isEmpty() || selectedPort != null && selectedPort in loginPorts
        var pendingPort by remember { mutableIntStateOf(3389) }
        val portPrefs = remember { getSharedPreferences("rdp-ports", MODE_PRIVATE) }
        var credentialMessage by remember { mutableStateOf("") }
        var pendingSave by remember { mutableStateOf<RdpCredentials?>(null) }
        var zoom by remember { mutableStateOf(1f) }
        var help by remember { mutableStateOf(false) }
        val displayPrefs = remember { getSharedPreferences("rdp-display", MODE_PRIVATE) }
        var trackpad by remember { mutableStateOf(displayPrefs.getBoolean("trackpad", false)) }
        var preferredPortrait by remember { mutableStateOf(displayPrefs.getBoolean("portrait", false)) }
        var fabRight by remember { mutableStateOf(displayPrefs.getBoolean("fab_right", true)) }
        var fabYFraction by remember { mutableFloatStateOf(displayPrefs.getFloat("fab_y", 0.82f)) }
        var tools by remember { mutableStateOf(false) }
        var connectionOptions by remember { mutableStateOf(false) }
        var controlsVisible by remember { mutableStateOf(false) }
        var confirmDisconnect by remember { mutableStateOf(false) }
        var shortcuts by remember { mutableStateOf(false) }
        var clipboard by remember { mutableStateOf(false) }
        var fileClipboard by remember { mutableStateOf(false) }
        var playAudio by remember { mutableStateOf(false) }
        var allowFileCopy by remember { mutableStateOf(true) }
        var modifiers by remember { mutableStateOf(0) }
        var originalSize by remember { mutableStateOf(false) }
        var quality by remember { mutableStateOf(RdpQuality.from(
            if (BuildConfig.DEBUG && intent.hasExtra("quality")) intent.getIntExtra("quality", 1) else displayPrefs.getInt("quality", RdpQuality.ADAPTIVE.ordinal))) }
        var scaleOption by remember { mutableStateOf(RdpScale.from(displayPrefs.getInt("scale", RdpScale.AUTO.ordinal))) }
        var statistics by remember { mutableStateOf(false) }
        var hardware by remember { mutableStateOf(displayPrefs.getBoolean("hardware", true) &&
            !(BuildConfig.DEBUG && intent.getBooleanExtra("classic", false))) }
        var quickClipMode by remember { mutableIntStateOf(0) } // 0=none, 1=send&paste, 2=pullToPhone
        var quickClipRequest by remember { mutableIntStateOf(0) }
        val storageFailed = stringResource(R.string.rdp_credentials_failed)
        val failed = stringResource(R.string.remote_service_failed)
        LaunchedEffect(overlay, reload) {
            error = ""; needsMesh = false; target = null; password = ""; busy = false; saved = false; rememberPassword = false
            if (!RdpNative.available) { error = failed; return@LaunchedEffect }
            val state = CoreClient.call("GET", "/v1/state")
            if (!state.ok) { error = failed; return@LaunchedEffect }
            credentialScope = RdpCredentialStore.scope(state.json(), BuildConfig.BFF_URL)
            if (!state.json().optBoolean("connected")) {
                needsMesh = true; return@LaunchedEffect
            }
            val result = CoreClient.call("GET", "/v1/remote/rdp/targets")
            if (!result.ok || result.json().optJSONArray("items") == null) {
                error = failed; return@LaunchedEffect
            }
            if (!result.json().optBoolean("available")) {
                error = failed; return@LaunchedEffect
            }
            val list = result.json().optJSONArray("items")
            target = (0 until (list?.length() ?: 0)).map { list!!.getJSONObject(it) }.firstOrNull { it.optString("overlay") == overlay &&
                (intent.getStringExtra("device_key") == null || it.optString("device_key") == intent.getStringExtra("device_key")) }
            if (target?.optBoolean("available") != true) error = getString(RdpErrors.reason(target?.optString("reason").orEmpty()))
            val identity = credentialScope
            if (intent.hasExtra("credential_scope") && intent.getStringExtra("credential_scope") != identity) {
                target = null; error = getString(R.string.rdp_identity_changed)
            }
            val key = target?.optString("device_key").orEmpty()
            if (identity != null && key.isNotBlank()) {
                val grants = target?.optJSONArray("login_ports")?.let { ports -> List(ports.length()) { ports.getInt(it) } }.orEmpty()
                val preferred = listOf(intent.getIntExtra("port", 0), portPrefs.getInt(store.file(identity, key).name, 3389))
                portText = (preferred.firstOrNull { it in 1..65535 && (grants.isEmpty() || it in grants) }
                    ?: grants.firstOrNull() ?: 3389).toString()
            }
        }
        LaunchedEffect(needsMesh) {
            while (needsMesh) {
                delay(2000)
                val state = CoreClient.call("GET", "/v1/state")
                if (state.ok && state.json().optBoolean("connected")) { reload++; break }
            }
        }
        val deviceKey = target?.optString("device_key").orEmpty()
        LaunchedEffect(credentialScope, deviceKey, selectedPort) {
            user = ""; domain = ""; password = ""; saved = false; rememberPassword = false; credentialMessage = ""
            val identity = credentialScope
            val port = selectedPort
            if (identity != null && deviceKey.isNotBlank() && port != null && target?.optBoolean("available") == true) {
                val loaded = withContext(Dispatchers.IO) { runCatching { store.load(identity, deviceKey, port) } }
                loaded.onSuccess { credentials ->
                    if (credentials != null) {
                        user = credentials.user; domain = credentials.domain; password = credentials.password
                        rememberPassword = true; saved = true
                    }
                }.onFailure { credentialMessage = storageFailed }
            }
        }
        fun rotateDisplay() {
            val nextPortrait = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            preferredPortrait = nextPortrait
            displayPrefs.edit().putBoolean("portrait", nextPortrait).apply()
            requestedOrientation = if (nextPortrait)
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        }
        fun resolveScale(targetQuality: RdpQuality): Int {
            val dm = resources.displayMetrics
            return scaleOption.resolve(dm.density, targetQuality, maxOf(dm.widthPixels, dm.heightPixels))
        }
        fun hideKeyboard() {
            active?.view?.let { view ->
                val ime = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                ime.hideSoftInputFromWindow(view.windowToken, 0)
            }
        }
        fun toggleKeyboard(currentlyVisible: Boolean = false) {
            active?.view?.let { view ->
                view.requestFocus()
                val ime = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                if (currentlyVisible || androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true) {
                    ime.hideSoftInputFromWindow(view.windowToken, 0)
                } else {
                    ime.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        val running = active
        if (running != null) {
            val status by running.state.collectAsState()
            val interactive by running.interactive.collectAsState()
            val touchReady by running.touchReady.collectAsState()
            val clipboardReady by running.clipboardReady.collectAsState()
            val clipboardResult by running.clipboardResult.collectAsState()
            val clipboardManager = remember { getSystemService(CLIPBOARD_SERVICE) as ClipboardManager }
            fun quickPasteFromPhone() {
                if (!clipboardReady) {
                    net.calabi.app.ui.toast(this@RdpActivity, getString(R.string.rdp_clip_unavailable))
                    return
                }
                val clip = clipboardManager.primaryClip
                val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
                if (text.isNullOrEmpty()) {
                    net.calabi.app.ui.toast(this@RdpActivity, getString(R.string.rdp_clip_no_text))
                    return
                }
                val outgoing = runCatching { RdpClipboardText.windows(text) }.getOrNull()
                if (outgoing == null) {
                    net.calabi.app.ui.toast(this@RdpActivity, getString(R.string.rdp_clip_invalid))
                    return
                }
                quickClipMode = 1
                quickClipRequest = running.clipboard(outgoing)
            }
            fun quickPullFromComputer() {
                if (!clipboardReady) {
                    net.calabi.app.ui.toast(this@RdpActivity, getString(R.string.rdp_clip_unavailable))
                    return
                }
                quickClipMode = 2
                quickClipRequest = running.clipboard()
            }
            LaunchedEffect(clipboardResult.id) {
                if (quickClipMode != 0 && clipboardResult.id != 0 && clipboardResult.id == quickClipRequest) {
                    val mode = quickClipMode
                    quickClipMode = 0
                    quickClipRequest = 0
                    if (clipboardResult.error == 0) {
                        if (mode == 1) {
                            running.view?.shortcut(intArrayOf(0x1d), 0x2f)
                            net.calabi.app.ui.toast(this@RdpActivity, getString(R.string.rdp_quick_pasted))
                        } else if (mode == 2 && clipboardResult.text != null) {
                            clipboardManager.setPrimaryClip(ClipData.newPlainText("Calabi", clipboardResult.text))
                            net.calabi.app.ui.toast(this@RdpActivity, getString(R.string.rdp_clip_copied))
                        }
                    } else {
                        val msg = when (clipboardResult.error) {
                            2 -> R.string.rdp_clip_no_text
                            3 -> R.string.rdp_clip_invalid
                            4 -> R.string.rdp_clip_timeout
                            5 -> R.string.rdp_clip_changed
                            else -> R.string.rdp_clip_unavailable
                        }
                        net.calabi.app.ui.toast(this@RdpActivity, getString(msg))
                    }
                }
            }
            if (running.copyEnabled) RdpFileCopy(running, fileClipboard) { fileClipboard = false }
            LaunchedEffect(resumed) {
                if (!resumed) { tools = false; help = false; shortcuts = false; clipboard = false; quickClipMode = 0 }
            }
            LaunchedEffect(interactive) {
                if (!interactive) { tools = false; shortcuts = false; clipboard = false; modifiers = 0; quickClipMode = 0 }
            }
            LaunchedEffect(running, interactive, credentialScope, deviceKey) {
                val identity = credentialScope
                if (interactive && identity != null && deviceKey.isNotBlank()) {
                    delay(3000)
                    while (true) {
                        val image = running.view?.thumbnail()
                        if (image != null) {
                            try {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        check(RdpCredentialStore.scope(CoreClient.callBlocking("GET", "/v1/state").json(), BuildConfig.BFF_URL) == identity)
                                        RdpRecentStore(this@RdpActivity).savePreview(identity,
                                            RdpRecent(deviceKey, "", overlay, running.port, System.currentTimeMillis()), image)
                                    }.onFailure { android.util.Log.w("CalabiRdp", "Could not save local preview") }
                                }
                            } finally { image.recycle() }
                        }
                        delay(if (image == null) 1000 else 15000)
                    }
                }
            }
            LaunchedEffect(status.first) {
                if (status.first == 1) {
                    if (!desktopPresentation) {
                        desktopPresentation = true
                        requestedOrientation = if (preferredPortrait)
                            ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                        enterFullscreen()
                    }
                    val credentials = pendingSave
                    pendingSave = null
                    val identity = credentialScope
                    if (identity != null) {
                        val recent = withContext(Dispatchers.IO) { runCatching {
                            check(RdpCredentialStore.scope(CoreClient.callBlocking("GET", "/v1/state").json(), BuildConfig.BFF_URL) == identity)
                            RdpRecentStore(this@RdpActivity).record(identity, RdpRecent(
                                target!!.getString("device_key"), target!!.optString("name").ifBlank { overlay }, overlay, pendingPort, System.currentTimeMillis()))
                        } }
                        if (recent.isFailure) net.calabi.app.ui.toast(this@RdpActivity, getString(R.string.remote_recent_failed))
                    }
                    if (credentials != null && identity != null) {
                        val result = withContext(Dispatchers.IO) { runCatching {
                            check(RdpCredentialStore.scope(CoreClient.callBlocking("GET", "/v1/state").json(), BuildConfig.BFF_URL) == identity)
                            store.save(identity, target!!.getString("device_key"), credentials, pendingPort)
                        } }
                        credentialMessage = if (result.isSuccess) "" else storageFailed
                        if (result.isSuccess) saved = true
                    }
                } else if (RdpSession.terminal(status.first)) pendingSave = null
            }
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val density = LocalDensity.current
            // Android 10 (API 29) suppresses WindowInsets.ime when FLAG_FULLSCREEN is set;
            // fall back to getWindowVisibleDisplayFrame so the keyboard bar and IME focus still work.
            var legacyImePx by remember { mutableIntStateOf(0) }
            DisposableEffect(window.decorView) {
                val decor = window.decorView
                val rect = android.graphics.Rect()
                val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    decor.getWindowVisibleDisplayFrame(rect)
                    val covered = (decor.height - rect.bottom).coerceAtLeast(0)
                    legacyImePx = if (decor.height > 0 && covered > decor.height * 0.15f) covered else 0
                }
                decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
                onDispose { decor.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
            }
            val insetImePx = WindowInsets.ime.getBottom(density)
            val fallbackImePadding = with(density) { if (insetImePx == 0) legacyImePx.toDp() else 0.dp }
            val keyboardVisible = insetImePx > 0 || legacyImePx > 0
            LaunchedEffect(keyboardVisible) {
                if (!keyboardVisible) modifiers = 0
                running.view?.setImeVisible(keyboardVisible)
            }
            Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().imePadding().padding(bottom = fallbackImePadding)) {
                if (status.first != 1) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RdpIconControl(R.drawable.ic_chevron_left, stringResource(R.string.back), tint = Color.White, onClick = { confirmDisconnect = true })
                    Text(target?.optString("name").orEmpty().ifBlank { overlay } + ":" + running.port, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                if (credentialMessage.isNotEmpty()) InlineNotice(credentialMessage, tone = NoticeTone.Error, onDark = true, modifier = Modifier.padding(horizontal = 12.dp))
                if (status.first != 1) InlineNotice(
                    stringResource(if (status.first == 0) R.string.rdp_connecting else if (status.first == 6) { if (status.second == "offline") R.string.rdp_wait_network else R.string.rdp_repairing } else RdpErrors.session(status.first, status.second)),
                    tone = if (RdpSession.terminal(status.first)) NoticeTone.Error else NoticeTone.Info, onDark = true, modifier = Modifier.padding(8.dp),
                )
                if (RdpSession.terminal(status.first)) Button(onClick = {
                    running.close(); session = null; active = null; pendingSave = null
                    showLogin()
                    tools = false; shortcuts = false; clipboard = false; help = false
                    zoom = 1f; reload++
                }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.rdp_reconnect)) }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val containerWidthPx = constraints.maxWidth.toFloat()
                val containerHeightPx = constraints.maxHeight.toFloat()
                AndroidView(factory = { context -> RdpView(context, running).also { view ->
                    view.onZoomChanged = { zoom = it; originalSize = view.viewport.originalSize }
                    view.onModifiersChanged = { modifiers = it }
                    running.view = view; view.requestFocus()
                } }, update = { view ->
                    view.interactive = interactive && status.first == 1
                    view.modifiers = modifiers
                    view.trackpadMode = trackpad
                    view.nativeTouchMode = touchReady
                },
                    modifier = Modifier.fillMaxSize())
                if (!keyboardVisible && (zoom > 1.001f || originalSize)) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .consumeControlTouches()
                            .clickable(role = Role.Button) { running.view?.fitScreen() },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        color = Palette.surface.copy(alpha = 0.85f),
                        shadowElevation = 4.dp,
                    ) {
                        Text(
                            text = (if (originalSize && zoom < 1.001f) "1:1" else "${(zoom * 100).toInt()}%") + " ↺",
                            style = Styles.mono,
                            fontSize = 12.sp,
                            color = Palette.ink,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                if (!keyboardVisible) {
                    if (controlsVisible) Surface(Modifier.align(if (landscape) Alignment.BottomEnd else Alignment.BottomCenter).padding(16.dp).consumeControlTouches(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = Palette.surface, shadowElevation = 8.dp) {
                        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RdpIconControl(R.drawable.ic_rdp_keyboard, stringResource(R.string.rdp_keyboard), enabled = interactive && status.first == 1, onClick = { toggleKeyboard(keyboardVisible) })
                            RdpIconControl(if (trackpad) R.drawable.ic_rdp_touchpad else R.drawable.ic_rdp_touch,
                                stringResource(if (trackpad) R.string.rdp_trackpad else R.string.rdp_direct_touch),
                                enabled = interactive && status.first == 1, selected = trackpad,
                                onClick = { trackpad = !trackpad
                                    displayPrefs.edit().putBoolean("trackpad", trackpad).apply()
                                    android.widget.Toast.makeText(this@RdpActivity, getString(if (trackpad) R.string.rdp_trackpad else R.string.rdp_direct_touch), android.widget.Toast.LENGTH_SHORT).show() })
                            RdpIconControl(R.drawable.ic_copy, stringResource(R.string.rdp_quick_paste),
                                enabled = interactive && status.first == 1 && clipboardReady,
                                onClick = ::quickPasteFromPhone)
                            RdpIconControl(R.drawable.ic_rdp_sliders, stringResource(R.string.rdp_tools), onClick = { tools = true })
                            RdpIconControl(R.drawable.ic_chevron_down, stringResource(R.string.mobile_hide_controls), onClick = { controlsVisible = false; modifiers = 0 })
                        }
                    } else RdpFloatingHandle(
                        containerWidthPx = containerWidthPx,
                        containerHeightPx = containerHeightPx,
                        snapRight = fabRight,
                        yFraction = fabYFraction,
                        onOpen = { controlsVisible = true },
                        onPositionSaved = { right, y ->
                            fabRight = right; fabYFraction = y
                            displayPrefs.edit().putBoolean("fab_right", right).putFloat("fab_y", y).apply()
                        },
                    )
                }
                }
                // Compact single-row IME helper bar always available when typing.
                if (keyboardVisible && interactive && status.first == 1) {
                    RdpKeyboardBar(
                        modifiers = modifiers,
                        keyboardVisible = true,
                        clipboardReady = clipboardReady,
                        onToggleModifier = { bit -> modifiers = modifiers xor bit },
                        onPress = { scan -> running.view?.press(scan) },
                        onPasteFromPhone = ::quickPasteFromPhone,
                        onHide = ::hideKeyboard,
                    )
                }
            }

            BackHandler(enabled = !tools && !help && !shortcuts && !clipboard && !fileClipboard && !statistics && !confirmDisconnect && !keyboardVisible) {
                if (controlsVisible) {
                    modifiers = 0; controlsVisible = false
                } else {
                    confirmDisconnect = true
                }
            }
            if (tools) ModalBottomSheet(modifier = Modifier.statusBarsPadding(), onDismissRequest = { tools = false }, containerColor = Palette.ground,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(target?.optString("name").orEmpty().ifBlank { overlay } + ":" + running.port, style = Styles.section,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        RdpIconControl(R.drawable.ic_close, stringResource(R.string.mobile_dismiss), onClick = { tools = false })
                    }
                    if (!interactive || status.first != 1) Text(
                        stringResource(if (status.second == "offline") R.string.rdp_wait_network else R.string.rdp_repairing), style = Styles.hint)
                    if (interactive && status.first == 1) {
                        RdpToolGrid(listOfNotNull(
                            RdpToolAction(R.drawable.ic_rdp_keyboard, stringResource(R.string.rdp_shortcuts)) { tools = false; shortcuts = true },
                            RdpToolAction(R.drawable.ic_copy, stringResource(R.string.rdp_clipboard)) { tools = false; clipboard = true },
                            if (running.copyEnabled) RdpToolAction(R.drawable.ic_file, stringResource(R.string.rdp_file_copy)) { tools = false; fileClipboard = true } else null,
                            RdpToolAction(R.drawable.ic_rdp_rotate, stringResource(R.string.rdp_rotate_display)) { tools = false; rotateDisplay() },
                        ))
                        if (running.quality == RdpQuality.ADAPTIVE) {
                            Text(stringResource(R.string.rdp_scale), style = Styles.section)
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RdpScale.entries.forEach { option ->
                                    FilterChip(
                                        selected = scaleOption == option,
                                        onClick = {
                                            scaleOption = option
                                            displayPrefs.edit().putInt("scale", option.ordinal).apply()
                                            running.setScale(resolveScale(running.quality))
                                        },
                                        label = { Text(stringResource(option.title)) },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                    )
                                }
                            }
                        }
                    }
                    Group {
                        ActionRow(stringResource(R.string.rdp_gestures), onClick = { tools = false; help = true })
                        ActionRow(stringResource(R.string.rdp_statistics), onClick = { tools = false; statistics = true })
                    }
                    OutlinedButton(onClick = { tools = false; confirmDisconnect = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.rdp_disconnect), color = Palette.danger)
                    }
                }
            }
            if (confirmDisconnect) AlertDialog(onDismissRequest = { confirmDisconnect = false },
                title = { Text(stringResource(R.string.mobile_disconnect_title)) },
                text = { Text(stringResource(R.string.mobile_disconnect_body), modifier = Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp), onClick = { running.close(); finish() }) { Text(stringResource(R.string.rdp_disconnect), color = Palette.danger) } },
                dismissButton = { TextButton(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp), onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.settings_cancel)) } })
            if (help) AlertDialog(onDismissRequest = { help = false },
                title = { Text(stringResource(R.string.rdp_gestures)) },
                text = { Text(stringResource(R.string.rdp_gestures_hint), modifier = Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp), onClick = { help = false }) { Text(stringResource(android.R.string.ok)) } })
            if (shortcuts) RdpShortcutDialog(running) { shortcuts = false }
            if (clipboard) RdpClipboardDialog(running, ::quickPasteFromPhone, ::quickPullFromComputer) { clipboard = false }
            if (statistics) {
                val graphics by running.graphics.collectAsState()
                var sample by remember(running) { mutableStateOf(LongArray(0)) }
                var rates by remember(running) { mutableStateOf<RdpRates?>(null) }
                LaunchedEffect(running) {
                    while (true) {
                        val next = running.statistics()
                        rates = RdpRates.between(sample, next)
                        sample = next
                        delay(1000)
                    }
                }
                AlertDialog(onDismissRequest = { statistics = false },
                    title = { Text(stringResource(R.string.rdp_statistics)) },
                    text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(stringResource(running.quality.title))
                        rates?.let {
                            Text(stringResource(R.string.rdp_statistics_rates, it.framesPerSecond, it.megabitsPerSecond))
                        }
                        if (sample.size >= 8) Text(stringResource(R.string.rdp_statistics_value,
                            sample[0], sample[1], sample[7] / 1000))
                        Text(stringResource(when (graphics) {
                            1 -> R.string.rdp_graphics_gfx
                            2 -> R.string.rdp_graphics_hardware
                            3 -> R.string.rdp_graphics_fallback
                            else -> R.string.rdp_graphics_classic
                        }), style = MaterialTheme.typography.bodySmall)
                    } },
                    confirmButton = { TextButton(contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp), onClick = { statistics = false }) { Text(stringResource(android.R.string.ok)) } })
            }
        } else {
            Column(Modifier.fillMaxSize().background(Palette.ground).safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RdpIconControl(R.drawable.ic_chevron_left, stringResource(R.string.back), enabled = !busy, onClick = { finish() })
                    Text(stringResource(R.string.rdp_preview_title), modifier = Modifier.weight(1f))
                    RdpIconControl(R.drawable.ic_rdp_sliders, stringResource(R.string.rdp_connection_options), enabled = !busy, onClick = { connectionOptions = true })
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), color = Palette.accentSoft) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { net.calabi.app.ui.Glyph(R.drawable.ic_monitor, Palette.accent, 24.dp) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(target?.optString("name") ?: overlay, style = Styles.section)
                        Text(if (selectedPort != null) "$overlay:$selectedPort" else overlay, style = Styles.mono)
                    }
                    if (needsMesh) StatusBadge(stringResource(R.string.remote_mesh_disconnected), modifier = Modifier.widthIn(max = 128.dp))
                }
                OutlinedTextField(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), value = user, enabled = !busy, onValueChange = { user = it }, label = { Text(stringResource(R.string.rdp_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (domain.isNotBlank()) Text(stringResource(R.string.rdp_domain) + ": " + domain, style = Styles.hint)
                OutlinedTextField(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), value = password, enabled = !busy, onValueChange = { password = it }, label = { Text(stringResource(R.string.login_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = portText, onValueChange = { value -> if (value.length <= 5 && value.all(Char::isDigit)) portText = value },
                    enabled = !busy && !clearingCredentials, isError = selectedPort == null || !authorizedPort, label = { Text(stringResource(R.string.rdp_port)) },
                    supportingText = {
                        if (selectedPort == null) Text(stringResource(R.string.rdp_port_invalid))
                        else if (loginPorts.isNotEmpty()) Text(loginPorts.joinToString())
                    },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberPassword, enabled = !busy && !clearingCredentials && credentialScope != null, onCheckedChange = { rememberPassword = it })
                    Text(stringResource(R.string.rdp_remember_password), modifier = Modifier.weight(1f))
                    if (saved) TextButton(modifier = Modifier.heightIn(min = 48.dp),
                        enabled = !busy && !clearingCredentials && selectedPort != null && credentialScope != null && target != null, onClick = {
                            val forgottenPort = selectedPort ?: return@TextButton
                            val identity = credentialScope ?: return@TextButton
                            val deviceKey = target?.getString("device_key") ?: return@TextButton
                            clearingCredentials = true
                            scope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) { runCatching { store.forget(identity, deviceKey, forgottenPort) } }
                                    if (result.isSuccess) { saved = false; rememberPassword = false; password = ""; credentialMessage = "" }
                                    else credentialMessage = storageFailed
                                } finally { clearingCredentials = false }
                            }
                        }) { Text(stringResource(R.string.rdp_clear_credentials)) }
                }
                net.calabi.app.ui.LinkRow(stringResource(R.string.rdp_connection_options), stringResource(quality.title), onClick = { if (!busy) connectionOptions = true })
                if (BuildConfig.FILE_TRANSFER_ENABLED) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowFileCopy, enabled = !busy, onCheckedChange = { allowFileCopy = it })
                    Text(stringResource(R.string.rdp_allow_file_copy))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = playAudio, enabled = !busy, onCheckedChange = { playAudio = it })
                    Text(stringResource(R.string.rdp_play_audio))
                }
                if (credentialMessage.isNotEmpty()) InlineNotice(credentialMessage, tone = NoticeTone.Error)
                if (error.isNotEmpty()) InlineNotice(error, tone = NoticeTone.Error, actionIcon = R.drawable.ic_refresh,
                    actionLabel = stringResource(R.string.rdp_retry), onAction = { if (!busy) reload++ })
                }
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Button(modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Color.White), enabled = !busy && !clearingCredentials && selectedPort != null && authorizedPort && user.isNotBlank() && password.isNotEmpty() && target?.optBoolean("available") == true && error.isEmpty(), onClick = {
                    val connectionPort = selectedPort ?: return@Button
                    busy = true
                    scope.launch {
                        val result = CoreClient.call("POST", "/v1/remote/rdp/prepare", JSONObject().put("device_key", target!!.getString("device_key")).put("port", connectionPort).put("copy_files", allowFileCopy))
                        if (!result.ok) { busy = false; error = result.error(failed); return@launch }
                        val identity = credentialScope
                        val currentIdentity = RdpCredentialStore.scope(CoreClient.call("GET", "/v1/state").json(), BuildConfig.BFF_URL)
                        if (identity == null || currentIdentity != identity) {
                            CoreClient.call("POST", "/v1/remote/rdp/close", JSONObject().put("token", result.json().getString("token")))
                            busy = false; password = ""; error = failed; return@launch
                        }
                        if (!rememberPassword) {
                            val removed = withContext(Dispatchers.IO) { runCatching { store.forget(identity, target!!.getString("device_key"), connectionPort) } }
                            if (removed.isFailure) credentialMessage = storageFailed
                            else saved = false
                        }
                        pendingPort = connectionPort
                        portPrefs.edit().putInt(store.file(identity, target!!.getString("device_key")).name, connectionPort).apply()
                        pendingSave = if (rememberPassword) RdpCredentials(user.trim(), domain.trim(), password) else null
                        val connection = RdpSession(result.json(), quality, hardware, playAudio, resolveScale(quality))
                        playAudio = false
                        fileClipboard = false
                        session = connection
                        active = connection
                        connection.start(user.trim(), domain.trim(), password)
                        if (!resumed) connection.background()
                        password = ""
                    }
                }) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    else net.calabi.app.ui.Glyph(R.drawable.ic_monitor, Color.White, 20.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(if (busy) R.string.rdp_connecting else R.string.rdp_connect))
                }
                }
            }
            if (connectionOptions) ModalBottomSheet(modifier = Modifier.statusBarsPadding(), onDismissRequest = { connectionOptions = false }, containerColor = Palette.ground,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.rdp_connection_options), style = Styles.section, modifier = Modifier.weight(1f))
                        RdpIconControl(R.drawable.ic_close, stringResource(R.string.mobile_dismiss), onClick = { connectionOptions = false })
                    }
                Text(stringResource(R.string.rdp_quality), style = Styles.section)
                FlowRow(Modifier.fillMaxWidth()) {
                    RdpQuality.entries.forEach { option ->
                        FilterChip(selected = quality == option, enabled = !busy, onClick = {
                            quality = option; displayPrefs.edit().putInt("quality", option.ordinal).apply()
                        }, label = { Text(stringResource(option.title)) }, modifier = Modifier.padding(end = 8.dp).heightIn(min = 48.dp))
                    }
                }
                Text(stringResource(R.string.rdp_quality_hint), style = Styles.hint)
                if (quality == RdpQuality.ADAPTIVE) Text(stringResource(R.string.rdp_quality_adaptive_hint), style = Styles.hint)
                Text(stringResource(R.string.rdp_scale), style = Styles.section)
                FlowRow(Modifier.fillMaxWidth()) {
                    RdpScale.entries.forEach { option ->
                        FilterChip(selected = scaleOption == option, enabled = !busy, onClick = {
                            scaleOption = option; displayPrefs.edit().putInt("scale", option.ordinal).apply()
                        }, label = { Text(stringResource(option.title)) }, modifier = Modifier.padding(end = 8.dp).heightIn(min = 48.dp))
                    }
                }
                Text(stringResource(R.string.rdp_scale_hint), style = Styles.hint)
                Row {
                    Checkbox(checked = hardware && quality != RdpQuality.ADAPTIVE, enabled = !busy && quality != RdpQuality.ECONOMY && quality != RdpQuality.ADAPTIVE && RdpHardware.decoder.isNotEmpty(), onCheckedChange = {
                        hardware = it; displayPrefs.edit().putBoolean("hardware", it).apply()
                    })
                    Text(stringResource(R.string.rdp_hardware_prefer), modifier = Modifier.weight(1f).padding(top = 12.dp))
                }
                Text(stringResource(R.string.rdp_hardware_hint), style = Styles.hint)
                OutlinedTextField(value = domain, enabled = !busy, onValueChange = { domain = it }, label = { Text(stringResource(R.string.rdp_domain)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
