package dev.kodakiko.nativeupload

class TimeoutHandler(
  private val stopWorkers: () -> Unit,
  private val failPending: () -> Unit,
  private val emitStopped: (String) -> Unit,
  private val stopService: () -> Unit,
) {
  fun onTimeout() {
    stopWorkers()
    failPending()
    emitStopped(REASON_TIMEOUT)
    stopService()
  }

  companion object {
    const val REASON_TIMEOUT = "timeout"
  }
}
