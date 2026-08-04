package vn.edu.haui.hvs.safedrive.core.network

import java.net.URI
import vn.edu.haui.hvs.safedrive.core.common.GatewayError
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult

/**
 * Validates a Developer Mode BASE_URL entry. Cleartext (http) is only ever accepted here for the
 * debug build's local/LAN presets; release network security config still blocks cleartext at the
 * OS level regardless of what a user types (see docs/android-mvp-plan/07-testing-security-acceptance.md).
 */
object BaseUrlValidator {

    fun validate(rawUrl: String, allowCleartext: Boolean): GatewayResult<String> {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return GatewayResult.Failure(GatewayError.Validation("URL không được để trống"))
        }
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return GatewayResult.Failure(GatewayError.Validation("URL không hợp lệ"))
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return GatewayResult.Failure(GatewayError.Validation("URL phải bắt đầu bằng http:// hoặc https://"))
        }
        if (scheme == "http" && !allowCleartext) {
            return GatewayResult.Failure(GatewayError.Validation("Bản release chỉ chấp nhận HTTPS"))
        }
        if (uri.host.isNullOrBlank()) {
            return GatewayResult.Failure(GatewayError.Validation("URL thiếu host"))
        }
        val normalized = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        return GatewayResult.Success(normalized)
    }
}
