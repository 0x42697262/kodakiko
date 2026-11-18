package dev.kodakiko.platform.picker

import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia

object MediaPicking {
    fun request(remainingSlots: Int): PickerRequest<PickVisualMediaRequest, *>? = when {
        remainingSlots < PickerConstants.SINGLE_SELECTION -> null
        remainingSlots == PickerConstants.SINGLE_SELECTION -> PickerRequest(PickVisualMedia(), PickVisualMediaRequest(PickVisualMedia.ImageAndVideo))
        else -> PickerRequest(PickMultipleVisualMedia(), PickVisualMediaRequest(PickVisualMedia.ImageAndVideo, maxItems = remainingSlots))
    }
}
