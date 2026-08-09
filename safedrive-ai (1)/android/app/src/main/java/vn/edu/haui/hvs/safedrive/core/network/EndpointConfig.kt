package vn.edu.haui.hvs.safedrive.core.network

/**
 * Canonical endpoints used by the Android app.
 *
 * Cloud Run terminates HTTPS/WSS on the standard public port 443, so the production URL must not
 * include the backend container's internal port. Port 8000 is only for a developer-owned backend
 * reached through USB reverse, the emulator host alias, or a LAN address.
 */
object EndpointConfig {
    const val PRODUCTION_BASE_URL =
        "https://safedrive-backend-165374511912.asia-southeast1.run.app/"

    const val PRODUCTION_HTTPS_PORT = 443
    const val LOCAL_BACKEND_PORT = 8000

    const val USB_LOCAL_BASE_URL = "http://127.0.0.1:8000/"
    const val EMULATOR_BASE_URL = "http://10.0.2.2:8000/"
    const val LEGACY_LAN_BASE_URL = "http://192.168.1.15:8000/"

    /** Endpoints shipped as old demo defaults. They are migrated once to production on upgrade. */
    val LEGACY_DEMO_BASE_URLS = setOf(
        USB_LOCAL_BASE_URL,
        EMULATOR_BASE_URL,
        LEGACY_LAN_BASE_URL,
    )
}
