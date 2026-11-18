package dev.kodakiko.ui.core.format

import dev.kodakiko.ui.core.format.DeviceSettingsMatrix.assertEverywhere
import org.junit.Test

class FormatBytesTest {
    private fun check(bytes: Long, text: String) = assertEverywhere(text, "formatBytes($bytes)") { formatBytes(bytes) }

    @Test
    fun zeroBytes() = check(0, "0 B")

    @Test
    fun justBelowOneKilobyteStaysInBytes() = check(1023, "1023 B")

    @Test
    fun oneAndAHalfKilobytes() = check(1536, "1.5 KB")

    @Test
    fun fiveHundredTwelveMegabytes() = check(536_870_912, "512 MB")

    @Test
    fun twoGigabytes() = check(2_147_483_648, "2 GB")

    @Test
    fun threeAndAHalfGigabytes() = check(3_758_096_384, "3.5 GB")

    @Test
    fun edgeCasesComputedWithTheV031Function() {
        check(1024, "1 KB")
        check(1_048_575, "1024 KB")
        check(1_048_576, "1 MB")
        check(1_572_864, "1.5 MB")
        check(2_000_000, "1.9 MB")
        check(5_242_880, "5 MB")
        check(5_300_000, "5.1 MB")
        check(10_485_759, "10 MB")
        check(1_099_511_627_776, "1 TB")
        check(1_125_899_906_842_624, "1024 TB")
        check(-5, "0 B")
    }
}
