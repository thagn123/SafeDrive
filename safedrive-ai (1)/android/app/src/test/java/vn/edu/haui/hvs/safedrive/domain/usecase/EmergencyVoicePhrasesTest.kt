package vn.edu.haui.hvs.safedrive.domain.usecase

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmergencyVoicePhrasesTest {

    @Test
    fun `exact allowlisted phrases cancel`() {
        assertThat(EmergencyVoicePhrases.isCancelPhrase("Tôi ổn")).isTrue()
        assertThat(EmergencyVoicePhrases.isCancelPhrase("tôi ổn")).isTrue()
        assertThat(EmergencyVoicePhrases.isCancelPhrase("Tôi ổn.")).isTrue()
        assertThat(EmergencyVoicePhrases.isCancelPhrase("Hủy SOS")).isTrue()
        assertThat(EmergencyVoicePhrases.isCancelPhrase("Không cần hỗ trợ")).isTrue()
        assertThat(EmergencyVoicePhrases.isCancelPhrase("  Tôi vẫn ổn  ")).isTrue()
    }

    @Test
    fun `a sentence merely containing a keyword never matches (no substring matching)`() {
        // The exact trap docs/android-mvp-plan/05-voice-emergency.md warns about.
        assertThat(EmergencyVoicePhrases.isCancelPhrase("Tôi không ổn")).isFalse()
        assertThat(EmergencyVoicePhrases.isCancelPhrase("Tôi không ổn chút nào")).isFalse()
        assertThat(EmergencyVoicePhrases.isCancelPhrase("Đừng hủy SOS")).isFalse()
    }

    @Test
    fun `unrelated speech never cancels`() {
        assertThat(EmergencyVoicePhrases.isCancelPhrase("Xin chào")).isFalse()
        assertThat(EmergencyVoicePhrases.isCancelPhrase("Kiểm tra nhiệt độ động cơ")).isFalse()
        assertThat(EmergencyVoicePhrases.isCancelPhrase("")).isFalse()
    }
}
