package dev.kodakiko.nativeupload

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.facebook.react.modules.network.OkHttpClientProvider
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class UploadService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var runner: Job? = null

  @Volatile
  private var lastStartId = 0
  private lateinit var notifier: UploadNotifier

  private val timeoutHandler = TimeoutHandler(
    stopWorkers = { runner?.cancel() },
    failPending = { UploadRegistry.failPending() },
    emitStopped = { reason -> UploadRegistry.emit(UploadEvent.Stopped(reason)) },
    stopService = { stopSelf() },
  )

  override fun onCreate() {
    super.onCreate()
    notifier = UploadNotifier(this)
    notifier.createChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    lastStartId = startId
    ServiceCompat.startForeground(
      this,
      UploadConstants.NOTIFICATION_ID,
      notifier.build(),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
    if (runner?.isActive != true) runner = scope.launch { drainUntilEmpty() }
    return START_NOT_STICKY
  }

  private suspend fun drainUntilEmpty() {
    val client = OkHttpClientProvider.getOkHttpClient().newBuilder()
      .callTimeout(UploadConstants.TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .readTimeout(UploadConstants.TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .writeTimeout(UploadConstants.TIMEOUT_SECONDS, TimeUnit.SECONDS)
      .build()
    val worker = UploadWorker(client) { notifier.update(System.currentTimeMillis()) }
    while (true) {
      while (UploadRegistry.hasPending()) worker.drain()
      if (!UploadRegistry.hasPending()) UploadRegistry.resetProgress()
      if (stopSelfResult(lastStartId)) break
    }
  }

  override fun onTimeout(startId: Int, fgsType: Int) {
    timeoutHandler.onTimeout()
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
