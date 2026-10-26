package net.rotko.zigner.domain

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthenticationMethodsTest {
	@Test fun weakOnlyForValidatorSignerOnApi28And29() {
		for (sdk in listOf(28, 29)) {
			assertEquals(BIOMETRIC_WEAK or DEVICE_CREDENTIAL, Authentication.requiredAuthMethods(sdk, validatorSigner = true))
			assertEquals(BIOMETRIC_STRONG, Authentication.requiredAuthMethods(sdk, validatorSigner = false))
		}
		for (sdk in listOf(26, 27, 30, 34)) for (v in listOf(true, false)) {
			assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, Authentication.requiredAuthMethods(sdk, v))
		}
	}
}
