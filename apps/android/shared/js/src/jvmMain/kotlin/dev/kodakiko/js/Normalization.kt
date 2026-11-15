package dev.kodakiko.js

import java.text.Normalizer

internal actual fun platformNormalizeNfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
