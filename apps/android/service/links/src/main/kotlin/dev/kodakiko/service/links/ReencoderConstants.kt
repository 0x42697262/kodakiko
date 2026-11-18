package dev.kodakiko.service.links

import android.media.ExifInterface

internal object ReencoderConstants {
    const val SAMPLE_SIZE_STEP = 2
    const val MIRROR_SCALE = -1f
    private const val QUARTER_TURN = 90
    private const val HALF_TURN = 180
    private const val THREE_QUARTER_TURN = 270
    val AXIS_SWAPPING_DEGREES: Set<Int> = setOf(QUARTER_TURN, THREE_QUARTER_TURN)
    val UPRIGHT = PhotoOrientation(0, false)
    val EXIF_ORIENTATIONS: Map<Int, PhotoOrientation> = mapOf(
        ExifInterface.ORIENTATION_NORMAL to UPRIGHT,
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL to PhotoOrientation(0, true),
        ExifInterface.ORIENTATION_ROTATE_180 to PhotoOrientation(HALF_TURN, false),
        ExifInterface.ORIENTATION_FLIP_VERTICAL to PhotoOrientation(HALF_TURN, true),
        ExifInterface.ORIENTATION_TRANSPOSE to PhotoOrientation(QUARTER_TURN, true),
        ExifInterface.ORIENTATION_ROTATE_90 to PhotoOrientation(QUARTER_TURN, false),
        ExifInterface.ORIENTATION_TRANSVERSE to PhotoOrientation(THREE_QUARTER_TURN, true),
        ExifInterface.ORIENTATION_ROTATE_270 to PhotoOrientation(THREE_QUARTER_TURN, false),
    )
}
