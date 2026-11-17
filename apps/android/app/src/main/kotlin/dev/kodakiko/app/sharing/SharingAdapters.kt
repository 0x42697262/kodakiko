package dev.kodakiko.app.sharing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import dev.kodakiko.data.db.CollectionKind
import dev.kodakiko.data.files.FileStore
import dev.kodakiko.data.net.ApiCall
import dev.kodakiko.data.net.HttpClient
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.data.upload.PartUploader
import dev.kodakiko.data.upload.UploadEvent
import dev.kodakiko.data.upload.UploadJob
import dev.kodakiko.data.upload.UploadRegistry
import dev.kodakiko.data.upload.UploadStatus
import dev.kodakiko.service.account.Observable
import dev.kodakiko.service.account.RowStorePort
import dev.kodakiko.service.shareout.ShareLauncher
import dev.kodakiko.service.sync.ObjectUploadJob
import dev.kodakiko.service.sync.SyncHttp
import dev.kodakiko.service.sync.Uploader
import dev.kodakiko.service.sync.asSyncHttp
import dev.kodakiko.ui.feature.links.LinkSharing
import dev.kodakiko.ui.feature.sharing.SharedJournalRow
import dev.kodakiko.ui.feature.sharing.SharedJournalRows
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class LocalAccountException : IllegalStateException(SharingAdapterConstants.LOCAL_ACCOUNT)

class ServerSyncHttp(http: HttpClient, private val accountMode: Observable<AccountMode>) : SyncHttp {
    private val delegate = http.asSyncHttp()

    override fun <T> request(call: ApiCall<T>): T {
        if (accountMode.value != AccountMode.SERVER) throw LocalAccountException()
        return delegate.request(call)
    }
}

class RowStoreJournalRows(private val rowStore: RowStorePort, private val ioDispatcher: CoroutineDispatcher) : SharedJournalRows {
    override suspend fun load(): List<SharedJournalRow> = withContext(ioDispatcher) {
        rowStore.transaction { tx -> tx.collections.all() }
            .filter { it.kind == CollectionKind.SHARED }
            .map { SharedJournalRow(it.id, it.role, it.ownerUsername) }
    }
}

class AppLinkSharing(private val context: Context, private val launcher: ShareLauncher) : LinkSharing {
    override fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(SharingAdapterConstants.CLIP_LABEL, text))
    }

    override fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType(SharingAdapterConstants.TEXT_MIME).putExtra(Intent.EXTRA_TEXT, text)
        launcher.launch(Intent.createChooser(send, null))
    }
}

class InProcessUploader(
    private val client: OkHttpClient,
    private val files: () -> FileStore,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) : Uploader {
    private val registry = UploadRegistry()
    private val supervisor = SupervisorJob(scope.coroutineContext[Job])
    private val uploads = CoroutineScope(scope.coroutineContext + supervisor)
    private val draining = Mutex()

    override suspend fun enqueue(jobs: List<ObjectUploadJob>) {
        val store = withContext(ioDispatcher) { files() }
        registry.submit(jobs.map { UploadJob(it.objectId, store.paths.objectFile(it.objectId).path, it.parts) })
        uploads.launch(ioDispatcher) { draining.withLock { PartUploader(client, registry) {}.drain() } }
    }

    override suspend fun statuses(): Map<String, UploadStatus> = registry.snapshot()

    override fun subscribe(listener: (UploadEvent) -> Unit): () -> Unit = registry.addListener(listener)

    override suspend fun cancelAll() {
        registry.failPending()
        supervisor.cancelChildren()
    }
}
