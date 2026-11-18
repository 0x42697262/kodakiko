package dev.kodakiko.data.media

data class ProbeResult(val width: Int, val height: Int, val durationMs: Long?, val decodable: Boolean, val rotationDegrees: Int) {
    companion object {
        val UNDECODABLE = ProbeResult(0, 0, null, false, 0)
    }
}
