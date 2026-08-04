package vn.edu.haui.hvs.safedrive.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import vn.edu.haui.hvs.safedrive.core.common.GatewayResult

class BaseUrlValidatorTest {

    @Test
    fun `blank url is rejected`() {
        val result = BaseUrlValidator.validate("", allowCleartext = true)
        assertThat(result).isInstanceOf(GatewayResult.Failure::class.java)
    }

    @Test
    fun `non-http scheme is rejected`() {
        val result = BaseUrlValidator.validate("ftp://10.0.2.2:8000", allowCleartext = true)
        assertThat(result).isInstanceOf(GatewayResult.Failure::class.java)
    }

    @Test
    fun `cleartext http is accepted when allowed (debug emulator preset)`() {
        val result = BaseUrlValidator.validate("http://10.0.2.2:8000", allowCleartext = true)
        check(result is GatewayResult.Success)
        assertThat(result.data).isEqualTo("http://10.0.2.2:8000/")
    }

    @Test
    fun `cleartext http is rejected when not allowed (release)`() {
        val result = BaseUrlValidator.validate("http://10.0.2.2:8000", allowCleartext = false)
        assertThat(result).isInstanceOf(GatewayResult.Failure::class.java)
    }

    @Test
    fun `https is always accepted regardless of cleartext flag`() {
        val result = BaseUrlValidator.validate("https://staging.safedrive.example.com", allowCleartext = false)
        assertThat(result).isInstanceOf(GatewayResult.Success::class.java)
    }

    @Test
    fun `missing host is rejected`() {
        val result = BaseUrlValidator.validate("http://", allowCleartext = true)
        assertThat(result).isInstanceOf(GatewayResult.Failure::class.java)
    }
}
