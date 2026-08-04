package vn.edu.haui.hvs.safedrive.core.network

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

// docs/android-mvp-plan/12 W5.11 locked budget — do not raise these just to mask a slow server.
private const val CONNECT_TIMEOUT_S = 3L
private const val READ_TIMEOUT_S = 8L
private const val WRITE_TIMEOUT_S = 5L

/**
 * Builds the Retrofit/OkHttp client for a given `baseUrl`. `baseUrl` must already be validated by
 * [BaseUrlValidator] (scheme/host, cleartext-only-in-debug) before this is called — this module
 * does not re-validate it.
 */
object NetworkModule {

    /** Shared with [vn.edu.haui.hvs.safedrive.data.remote.RemoteSafeDriveGateway] so it can decode an
     * error response's `ErrorEnvelope` body with the exact same lenient config used for success
     * bodies (forward/backward compatible with an evolving backend). */
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * Builds the shared [OkHttpClient] used for both Retrofit (HTTP) and
     * [vn.edu.haui.hvs.safedrive.data.remote.AssistantSocketClient] (WebSocket) against a
     * given `baseUrl`. Read/connect/write timeouts govern HTTP calls only -- OkHttp's
     * WebSocket connections are not subject to `readTimeout` once the upgrade completes
     * (see `AssistantSocketClient`'s own liveness handling), so callers using this client for
     * a socket must implement their own liveness/backstop timeout rather than relying on this.
     */
    fun createOkHttpClient(
        allowCleartext: Boolean,
        connectTimeoutSeconds: Long = CONNECT_TIMEOUT_S,
        readTimeoutSeconds: Long = READ_TIMEOUT_S,
        writeTimeoutSeconds: Long = WRITE_TIMEOUT_S,
    ): OkHttpClient {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)

        if (allowCleartext) {
            // Debug builds only (release never constructs a Remote gateway with allowCleartext=true;
            // see RemoteSafeDriveGateway's callers and the release network security config).
            clientBuilder.addInterceptor(RedactingLoggingInterceptor { message -> Log.d("SafeDriveNetwork", message) })
        }
        return clientBuilder.build()
    }

    fun createRetrofit(
        baseUrl: String,
        allowCleartext: Boolean,
        connectTimeoutSeconds: Long = CONNECT_TIMEOUT_S,
        readTimeoutSeconds: Long = READ_TIMEOUT_S,
        writeTimeoutSeconds: Long = WRITE_TIMEOUT_S,
    ): Retrofit {
        val client = createOkHttpClient(
            allowCleartext,
            connectTimeoutSeconds,
            readTimeoutSeconds,
            writeTimeoutSeconds,
        )
        return buildRetrofit(baseUrl, client)
    }

    fun buildRetrofit(baseUrl: String, client: OkHttpClient): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }
}
