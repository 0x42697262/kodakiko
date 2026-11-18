@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.kodakiko.service.backup

import dev.kodakiko.contract.EntryMetadataV1
import dev.kodakiko.exporting.ZipTimeZone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

const val ONE_GIB = 1_073_741_824L

open class ExportTestBase : HarnessTestBase() {
    protected lateinit var harness: EntriesHarness
    protected lateinit var io: CoroutineDispatcher
    protected val autoLock = CountingAutoLock()
    protected val fetched: MutableList<String> = CopyOnWriteArrayList()
    protected val capChecks = AtomicInteger(0)
    protected var fetch: suspend (String) -> Unit = { objectId: String -> fetched += objectId }

    protected fun backupTest(body: suspend TestScope.() -> Unit): TestResult =
        runTest {
            io = UnconfinedTestDispatcher(testScheduler)
            harness = EntriesHarness(context, sodium, createAccountFixture(), io)
            body()
        }

    protected fun exporter(
        source: EntriesHarness = harness,
        order: (suspend () -> List<String>)? = null,
        readMetadata: (suspend (String) -> EntryMetadataV1)? = null,
    ): ExportService =
        DefaultExportService(
            ExportServiceDeps(
                entryIds = order ?: { source.allEntryIds() },
                readMetadata = readMetadata ?: { entryId: String -> source.service.readMetadata(entryId) },
                entryKeys = source.entryKeys,
                rowStore = source.fixture.rowStore,
                files = FileStoreBackupFiles { source.fixture.fileStore },
                fetchObject = { objectId: String -> fetch(objectId) },
                enforceCap = { capChecks.incrementAndGet() },
                sodium = sodium,
                autoLock = autoLock,
                clock = source.clock,
                zipTimeZone = ZipTimeZone.UTC,
                ioDispatcher = io,
            ),
        )
}
