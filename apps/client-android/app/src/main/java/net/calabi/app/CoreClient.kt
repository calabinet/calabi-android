package net.calabi.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One answer from the core's local API. */
data class ApiResult(val status: Int, val body: String) {
    val ok get() = status in 200..299
    fun json(): JSONObject = try {
        JSONObject(body)
    } catch (_: Exception) {
        JSONObject()
    }

    /** Stable reasons are translated using the app locale, with English fallback. */
    fun error(fallback: String): String = UserErrors.render(json(), status, fallback)
}

/**
 * The app's only way to the Go core's data: its in-process /v1 API
 * (apps/client/mobile/api.go). Calls block on network I/O, so they run off the
 * main thread.
 */
object CoreClient {
    internal val session = SessionExpiryState()
    val sessionExpired = session.expired

    suspend fun call(method: String, path: String, body: JSONObject? = null): ApiResult =
        withContext(Dispatchers.IO) { callBlocking(method, path, body) }

    fun callBlocking(method: String, path: String, body: JSONObject? = null): ApiResult {
        val generation = session.requestGeneration()
        val resp = CalabiApp.instance.core.call(method, path, body?.toString()?.toByteArray())
        return ApiResult(resp.status, String(resp.body ?: ByteArray(0))).also {
            session.record(generation, method, path, it.status)
        }
    }

    /** Cheap: reads the saved session, no network. */
    fun signedIn(): Boolean = callBlocking("GET", "/v1/state").json().optBoolean("signed_in")
}

/** A final platform 401 survives the core's refresh attempt and requires sign-in. */
internal class SessionExpiryState {
    private var generation = 0L
    private val mutableExpired = MutableStateFlow(false)
    val expired = mutableExpired.asStateFlow()

    @Synchronized
    fun requestGeneration(): Long = generation

    @Synchronized
    fun record(requestGeneration: Long, method: String, path: String, status: Int) {
        if (requestGeneration != generation) return
        val route = path.substringBefore('?')
        if (method == "POST" && status in 200..299 && route in setOf(
                "/v1/auth/login", "/v1/auth/logout", "/v1/selfhosted/join", "/v1/orgs/switch",
            )) {
            // Old requests must not sign out a newly authenticated session or network.
            generation++
            mutableExpired.value = false
        } else if (status == 401 && (
                (method == "GET" && (route in setOf(
                    "/v1/me", "/v1/orgs", "/v1/mesh/nodes", "/v1/mesh/replaceable",
                    "/v1/tunnels", "/v1/usage/overview",
                ) || (route.startsWith("/v1/tunnels/") && route.endsWith("/access")))) ||
                (method == "POST" && route in setOf("/v1/orgs/switch", "/v1/mesh/replace"))
            )) {
            // Login/TOTP failures, self-hosted probes and peer file denials are unrelated.
            mutableExpired.value = true
        }
    }
}
