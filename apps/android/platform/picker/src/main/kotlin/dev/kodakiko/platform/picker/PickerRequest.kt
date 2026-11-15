package dev.kodakiko.platform.picker

import androidx.activity.result.contract.ActivityResultContract

class PickerRequest<I, O>(val contract: ActivityResultContract<I, O>, val input: I)
