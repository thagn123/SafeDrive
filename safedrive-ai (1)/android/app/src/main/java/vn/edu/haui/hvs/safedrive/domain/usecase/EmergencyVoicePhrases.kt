package vn.edu.haui.hvs.safedrive.domain.usecase

/**
 * Exact-match allowlist per docs/android-mvp-plan/05-voice-emergency.md: "Chuẩn hóa voice phrase
 * bằng lowercase, trim, bỏ dấu câu và so khớp exact intent/grammar giới hạn. Không dùng điều kiện
 * kiểu contains('không'), vì có thể hủy nhầm câu 'tôi không ổn'."
 */
object EmergencyVoicePhrases {
    private val cancelPhrases = setOf(
        "tôi ổn",
        "tôi vẫn ổn",
        "hủy sos",
        "không cần hỗ trợ",
    )

    fun isCancelPhrase(rawText: String): Boolean {
        val normalized = rawText.trim().lowercase().trimEnd('.', '!', '?', ',')
        return normalized in cancelPhrases
    }
}
