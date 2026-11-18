package dev.kodakiko.ui.feature.lock

import android.os.Handler
import android.os.Looper
import dev.kodakiko.data.secure.DeviceGate
import dev.kodakiko.data.secure.DeviceGateReason
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.crypto.Cipher

class BiometricDeviceGate(private val launcher: PromptLauncher) : DeviceGate {
    private val oneAtATime = Semaphore(LockConstants.ONE_PROMPT, true)
    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    override fun authenticate(reason: DeviceGateReason): Boolean = prompt(PromptRequest(reason, cipher = null)) is PromptResult.Succeeded

    override fun authenticateCipher(reason: DeviceGateReason, cipher: Cipher): Cipher? =
        (prompt(PromptRequest(reason, cipher)) as? PromptResult.Succeeded)?.cipher

    private fun prompt(request: PromptRequest): PromptResult {
        if (Looper.myLooper() == Looper.getMainLooper()) return PromptResult.Failed
        try {
            oneAtATime.acquire()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return PromptResult.Failed
        }
        try {
            val answer = CompletableFuture<PromptResult>()
            main.post {
                try {
                    launcher.launch(request) { answer.complete(it) }
                } catch (failure: RuntimeException) {
                    answer.complete(PromptResult.Failed)
                }
            }
            return answer.get(LockConstants.PROMPT_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return PromptResult.Failed
        } catch (failure: TimeoutException) {
            return PromptResult.Failed
        } catch (failure: ExecutionException) {
            return PromptResult.Failed
        } finally {
            oneAtATime.release()
        }
    }
}
