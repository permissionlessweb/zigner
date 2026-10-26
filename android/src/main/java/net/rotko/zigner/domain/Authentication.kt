package net.rotko.zigner.domain

import android.app.KeyguardManager
import android.content.Context
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import net.rotko.zigner.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.suspendCoroutine


class Authentication {

	companion object {
		/**
		 * androidx.biometric rejects STRONG | DEVICE_CREDENTIAL on API 28-29
		 * (PromptInfo.build() throws). Only the validator signer path (old
		 * phones used as dedicated signers) falls back to WEAK | DEVICE_CREDENTIAL
		 * there; everything else stays BIOMETRIC_STRONG (no PIN fallback on 28-29).
		 */
		fun requiredAuthMethods(sdk: Int, validatorSigner: Boolean): Int {
			val strong = BiometricManager.Authenticators.BIOMETRIC_STRONG
			val cred = BiometricManager.Authenticators.DEVICE_CREDENTIAL
			if (sdk != 28 && sdk != 29) return strong or cred
			return if (validatorSigner) BiometricManager.Authenticators.BIOMETRIC_WEAK or cred else strong
		}

		fun canAuthenticate(context: Context): Boolean {
			val keygoard =
				context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
			return keygoard.isDeviceSecure
		}
	}

	/**
	 * Allow biometric (fingerprint/face) with PIN/password fallback.
	 * BIOMETRIC_STRONG requires hardware-backed biometrics.
	 * DEVICE_CREDENTIAL allows PIN/password/pattern as fallback.
	 */
	private fun getRequiredAuthMethods(validatorSigner: Boolean = false): Int =
		requiredAuthMethods(android.os.Build.VERSION.SDK_INT, validatorSigner)

	private lateinit var biometricPrompt: BiometricPrompt

	private val _auth = MutableStateFlow<Boolean>(false)
	val auth: StateFlow<Boolean> = _auth.asStateFlow()

	fun authenticate(activity: FragmentActivity, onSuccess: () -> Unit) = authenticate(activity, false, onSuccess)

	/** validatorSigner = true only for the validator key screens (move, export). */
	fun authenticate(activity: FragmentActivity, validatorSigner: Boolean, onSuccess: () -> Unit) {
		if (FeatureFlags.isEnabled(FeatureOption.SKIP_UNLOCK_FOR_DEVELOPMENT)) {
			_auth.value = true
			onSuccess()
			return
		}

		val context = activity.baseContext

		val promptInfo =
			BiometricPrompt.PromptInfo.Builder()
				.setTitle(context.getString(R.string.unlock_device_title))
				.setSubtitle(context.getString(R.string.unlock_device_subtitle))
				.setAllowedAuthenticators(getRequiredAuthMethods(validatorSigner))
				.build()

		if (canAuthenticate(context)) {

			val executor = ContextCompat.getMainExecutor(context)

			biometricPrompt = BiometricPrompt(
				activity, executor,
				object : BiometricPrompt.AuthenticationCallback() {
					override fun onAuthenticationError(
						errorCode: Int,
						errString: CharSequence
					) {
						super.onAuthenticationError(errorCode, errString)
						Toast.makeText(
							context,
							context.getString(R.string.auth_error_message, errString),
							Toast.LENGTH_SHORT
						).show()
						_auth.value = false
					}

					override fun onAuthenticationSucceeded(
						result: BiometricPrompt.AuthenticationResult
					) {
						super.onAuthenticationSucceeded(result)
						_auth.value = true
						onSuccess()
					}

					override fun onAuthenticationFailed() {
						super.onAuthenticationFailed()
						Toast.makeText(
							context, context.getString(R.string.auth_failed_message),
							Toast.LENGTH_SHORT
						).show()
					}
				})
			biometricPrompt.authenticate(promptInfo)
		} else {
			Toast.makeText(
				context, context.getString(R.string.auth_error_status_unknown),
				Toast.LENGTH_LONG
			).show()
		}
	}

	suspend fun authenticate(activity: FragmentActivity): AuthResult =
		suspendCoroutine { continuation ->
			val context = activity.baseContext

			val promptInfo =
				BiometricPrompt.PromptInfo.Builder()
					.setTitle(context.getString(R.string.unlock_device_title))
					.setSubtitle(context.getString(R.string.unlock_device_subtitle))
					.setAllowedAuthenticators(getRequiredAuthMethods())
					.build()

			if (canAuthenticate(context)) {

				val executor = ContextCompat.getMainExecutor(context)

				biometricPrompt = BiometricPrompt(
					activity, executor,
					object : BiometricPrompt.AuthenticationCallback() {
						override fun onAuthenticationError(
							errorCode: Int,
							errString: CharSequence
						) {
							super.onAuthenticationError(errorCode, errString)
							Toast.makeText(
								context,
								context.getString(R.string.auth_error_message, errString),
								Toast.LENGTH_SHORT
							).show()
							_auth.value = false
							continuation.resumeWith(Result.success(AuthResult.AuthError))
						}

						override fun onAuthenticationSucceeded(
							result: BiometricPrompt.AuthenticationResult
						) {
							super.onAuthenticationSucceeded(result)
							_auth.value = true
							continuation.resumeWith(Result.success(AuthResult.AuthSuccess))
						}

						override fun onAuthenticationFailed() {
							super.onAuthenticationFailed()
							Toast.makeText(
								context, context.getString(R.string.auth_failed_message),
								Toast.LENGTH_SHORT
							).show()
						}
					}
				)
				biometricPrompt.authenticate(promptInfo)
			} else {
				Toast.makeText(
					context, context.getString(R.string.auth_error_status_unknown),
					Toast.LENGTH_LONG
				).show()
			}
		}
}


	sealed class AuthResult {
		data object AuthSuccess : AuthResult()
		data object AuthError : AuthResult()
		data object AuthFailed : AuthResult()
		data object AuthUnavailable : AuthResult()
	}


