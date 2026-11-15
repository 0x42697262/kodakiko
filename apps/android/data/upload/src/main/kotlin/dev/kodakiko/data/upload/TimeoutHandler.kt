package dev.kodakiko.data.upload

class TimeoutHandler(
    private val stopWorkers: () -> Unit,
    private val failPending: () -> Unit,
    private val emitStopped: (StopReason) -> Unit,
    private val stopService: () -> Unit,
) {
    fun onTimeout() {
        stopWorkers()
        failPending()
        emitStopped(StopReason.TIMEOUT)
        stopService()
    }
}
