package dev.kodakiko.ui.feature.lock

import android.content.res.Resources
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.kodakiko.data.secure.DeviceGateReason
import dev.kodakiko.ui.core.R
import javax.crypto.Cipher

data class PromptRequest(val reason: DeviceGateReason, val cipher: Cipher?)

sealed interface PromptResult {
    data class Succeeded(val cipher: Cipher?) : PromptResult

    data object Failed : PromptResult
}

fun interface PromptLauncher {
    fun launch(request: PromptRequest, onResult: (PromptResult) -> Unit)
}

class AndroidxPromptLauncher(
    private val resources: Resources,
    private val activity: () -> FragmentActivity?,
) : PromptLauncher {
    fun promptInfo(request: PromptRequest): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder().setTitle(resources.getString(R.string.lock_biometric_prompt))
        return if (request.cipher != null) {
            builder
                .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(resources.getString(R.string.lock_cancel))
                .build()
        } else {
            builder.setAllowedAuthenticators(Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL).build()
        }
    }

    override fun launch(request: PromptRequest, onResult: (PromptResult) -> Unit) {
        val host = activity()
        if (host == null || host.isFinishing || host.isDestroyed || host.supportFragmentManager.isStateSaved) {
            onResult(PromptResult.Failed)
            return
        }
        val prompt = BiometricPrompt(host, ContextCompat.getMainExecutor(host), Callback(onResult))
        val info = promptInfo(request)
        val cipher = request.cipher
        try {
            if (cipher == null) prompt.authenticate(info) else prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        } catch (failure: IllegalArgumentException) {
            onResult(PromptResult.Failed)
        }
    }

    private class Callback(private val onResult: (PromptResult) -> Unit) : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onResult(PromptResult.Succeeded(result.cryptoObject?.cipher))
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            onResult(PromptResult.Failed)
        }
    }
}
