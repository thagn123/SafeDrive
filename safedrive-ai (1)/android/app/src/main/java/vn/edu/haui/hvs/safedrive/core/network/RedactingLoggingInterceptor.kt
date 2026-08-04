package vn.edu.haui.hvs.safedrive.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Debug-only network log: method, path (no query string, which could carry a session id) and
 * status/latency — **never** request/response bodies, since those can carry assistant transcripts,
 * DTC/vehicle detail or emergency evidence (docs/android-mvp-plan/07-testing-security-acceptance.md,
 * "Không log raw transcript ... production log phải redact"). There is no release-build equivalent:
 * this interceptor is only ever attached in debug builds (see `NetworkModule`).
 */
class RedactingLoggingInterceptor(private val log: (String) -> Unit) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startNs = System.nanoTime()
        val response = chain.proceed(request)
        val tookMs = (System.nanoTime() - startNs) / 1_000_000
        log("${request.method} ${request.url.encodedPath} -> ${response.code} (${tookMs}ms)")
        return response
    }
}
