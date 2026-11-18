package dev.kodakiko.app.capture

object CaptureAdapterConstants {
    const val PHOTO_EXTENSION = "jpg"
    const val VIDEO_EXTENSION = "mp4"
    const val PICKED_EXTENSION = "bin"
    const val PHONE_FILE_PREFIX = "phone-"
    const val EXTENSION_SEPARATOR = "."
    const val PERMISSION_KEY_PREFIX = "kodakiko.permission."
    const val DEVICE_DELETE_KEY_PREFIX = "kodakiko.deviceDelete."
    const val LOCAL_PICKER_PROVIDER = "com.android.providers.media.photopicker"
    const val PICKER_PROVIDER_INDEX = 2
    const val IMAGE_MIME_PREFIX = "image/"
    const val VIDEO_MIME_PREFIX = "video/"
    val PICKER_PATHS: Set<String> = setOf("picker", "picker_get_content")
}
