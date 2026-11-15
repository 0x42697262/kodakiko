package dev.kodakiko.sync

import dev.kodakiko.testing.GoldenReplay
import kotlin.test.Test

class SyncGoldenTest {
    @Test
    fun failures() = GoldenReplay.run("sync/failures.json", SyncGoldenAdapter)

    @Test
    fun pushPlan() = GoldenReplay.run("sync/push-plan.json", SyncGoldenAdapter)

    @Test
    fun pulledEntry() = GoldenReplay.run("sync/pulled-entry.json", SyncGoldenAdapter)

    @Test
    fun collectionsPlan() = GoldenReplay.run("sync/collections-plan.json", SyncGoldenAdapter)

    @Test
    fun merge() = GoldenReplay.run("sync/merge.json", SyncGoldenAdapter)
}
