package dev.kodakiko.nativeupload

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import expo.modules.interfaces.permissions.PermissionsStatus
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

class PartRecord : Record {
  @Field val partNumber: Int = 0
  @Field val url: String = ""
  @Field val offset: Double = 0.0
  @Field val length: Double = 0.0
}

class JobRecord : Record {
  @Field val objectId: String = ""
  @Field val filePath: String = ""
  @Field val parts: List<PartRecord> = emptyList()
}

class NativeUploadModule : Module() {
  private var removeListener: (() -> Unit)? = null

  private val context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  override fun definition() = ModuleDefinition {
    Name(UploadConstants.MODULE_NAME)

    Events(UploadConstants.EVENT_NAME)

    OnCreate {
      removeListener = UploadRegistry.addListener { event ->
        sendEvent(UploadConstants.EVENT_NAME, event.toMap())
      }
    }

    OnDestroy {
      removeListener?.invoke()
      removeListener = null
    }

    AsyncFunction("enqueue") { jobs: List<JobRecord> ->
      UploadRegistry.submit(
        jobs.map { job ->
          UploadJob(
            objectId = job.objectId,
            filePath = job.filePath,
            parts = job.parts.map { part ->
              UploadPart(part.partNumber, part.url, part.offset.toLong(), part.length.toLong())
            },
          )
        },
      )
      ContextCompat.startForegroundService(context, Intent(context, UploadService::class.java))
    }

    AsyncFunction("statuses") { -> UploadRegistry.snapshot() }

    AsyncFunction("cancelAll") { ->
      UploadRegistry.clear()
      context.stopService(Intent(context, UploadService::class.java))
      UploadRegistry.emit(UploadEvent.Stopped("cancelled"))
    }

    AsyncFunction("requestNotificationPermission") { promise: Promise ->
      val permission = Manifest.permission.POST_NOTIFICATIONS
      val alreadyGranted =
        Build.VERSION.SDK_INT < UploadConstants.NOTIFICATION_PERMISSION_FIRST_SDK ||
          ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
      if (alreadyGranted) {
        promise.resolve(true)
        return@AsyncFunction
      }
      val permissions = appContext.permissions
      if (permissions == null) {
        promise.resolve(false)
        return@AsyncFunction
      }
      permissions.askForPermissions({ result ->
        promise.resolve(result[permission]?.status == PermissionsStatus.GRANTED)
      }, permission)
    }
  }
}
