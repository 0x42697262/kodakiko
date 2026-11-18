package dev.kodakiko.ui.feature.settings

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat

class PickerLaunch(val requestCode: Int, val contract: ActivityResultContract<*, *>, val input: Any?)

class FakeResultRegistry : ActivityResultRegistry() {
    val launches = mutableListOf<PickerLaunch>()

    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
        launches += PickerLaunch(requestCode, contract, input)
    }

    fun <O> answer(result: O) {
        dispatchResult(launches.last().requestCode, result)
    }
}

class FakeRegistryOwner(registry: ActivityResultRegistry) : ActivityResultRegistryOwner {
    override val activityResultRegistry: ActivityResultRegistry = registry
}

@Composable
fun WithRegistry(registry: FakeResultRegistry, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalActivityResultRegistryOwner provides FakeRegistryOwner(registry)) { content() }
}

object PickedUris {
    val FOLDER: Uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AKodakiko")
    val PART_ONE: Uri = Uri.parse("content://com.android.providers.downloads.documents/document/kodakiko-export-1.zip.kdkx")
    val PART_TWO: Uri = Uri.parse("content://com.android.providers.downloads.documents/document/kodakiko-export-2.zip.kdkx")
}
