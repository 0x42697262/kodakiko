package dev.kodakiko.platform.picker

import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments

object DocumentPicking {
    fun openMany(): PickerRequest<Array<String>, List<Uri>> = PickerRequest(OpenMultipleDocuments(), arrayOf(PickerConstants.ANY_MIME_TYPE))

    fun openFolder(): PickerRequest<Uri?, Uri?> = PickerRequest(OpenDocumentTree(), null)
}
