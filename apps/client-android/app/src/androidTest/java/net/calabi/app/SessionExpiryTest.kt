package net.calabi.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

/** Runs on Android without extra test-library dependencies. */
class SessionExpiryTest : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val results = Bundle()
        try {
            val session = SessionExpiryState()
            val initial = session.requestGeneration()
            for ((method, path, status) in listOf(
                Triple("POST", "/v1/auth/login", 401),
                Triple("POST", "/v1/selfhosted/join", 401),
                Triple("POST", "/v1/files/action", 401),
                Triple("GET", "/v1/mesh/nodes", 403),
                Triple("GET", "/v1/mesh/nodes", 502),
                Triple("GET", "/v1/mesh/nodes", 200),
            )) {
                session.record(initial, method, path, status)
                check(!session.expired.value) { "$method $path $status incorrectly requires sign-in" }
            }
            session.record(initial, "GET", "/v1/mesh/nodes", 401)
            check(session.expired.value) { "Expired node request did not require sign-in" }
            session.record(initial, "GET", "/v1/state", 200)
            check(session.expired.value) { "Saved-token polling concealed the expired session" }
            session.record(initial, "POST", "/v1/auth/login", 200)
            check(!session.expired.value) { "Successful sign-in did not clear expiration" }
            session.record(initial, "GET", "/v1/me", 401)
            check(!session.expired.value) { "Old 401 signed out the new session" }
            val renewed = session.requestGeneration()
            session.record(renewed, "GET", "/v1/me", 401)
            check(session.expired.value) { "New session expiration was ignored" }
            session.record(renewed, "POST", "/v1/selfhosted/join", 200)
            check(!session.expired.value) { "Successful self-hosted join did not clear expiration" }
            session.record(renewed, "GET", "/v1/orgs", 401)
            check(!session.expired.value) { "Old platform request signed out the self-hosted session" }
            results.putString("stream", "\nSession expiry regression checks passed\n")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, results)
        }
    }
}
