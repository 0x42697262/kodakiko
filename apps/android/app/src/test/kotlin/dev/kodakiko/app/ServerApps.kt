package dev.kodakiko.app

import android.content.Context
import dev.kodakiko.contract.ContractJson
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.DownloadResponse
import dev.kodakiko.contract.PublicConfigDto
import dev.kodakiko.contract.Routes
import dev.kodakiko.contract.SignupMode
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.data.db.DeviceSettings
import dev.kodakiko.data.db.DeviceSettingsDefaults
import dev.kodakiko.data.db.DeviceSettingsJson
import dev.kodakiko.data.db.DeviceSettingsStore
import dev.kodakiko.data.db.KodakikoDatabase
import dev.kodakiko.data.db.ObjectLocalState
import dev.kodakiko.data.db.ObjectUploadState
import dev.kodakiko.data.db.StartScreen
import dev.kodakiko.data.secure.AccountMode
import dev.kodakiko.data.secure.ActiveAccount
import dev.kodakiko.data.secure.GlobalStore
import dev.kodakiko.data.secure.SessionStore
import dev.kodakiko.service.account.SecureSessionStore
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object V031Media {
    const val MEDIA_ID = "bt7GB2t6ePYayR9Xf-UDzw"
    const val THUMBNAIL_OBJECT = "aiCCanDLFmrvG_Tf6iBlsA"
    const val ORIGINAL_OBJECT = "VRuvDpuLOAPARqzaL_4eqw"

    private val fixtureAccount: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "AGENTS.md").isFile && File(it, "apps/android").isDirectory }
            .let { File(it, "tests/fixtures/v031-device/local/files/kodakiko/${V031Fixture.HASH}") }
    }

    val fixtureThumbnailCiphertext: ByteArray get() = File(fixtureAccount, "objects/$THUMBNAIL_OBJECT.kdks").readBytes()

    val fixtureOriginalCiphertext: ByteArray get() = File(fixtureAccount, "objects/$ORIGINAL_OBJECT.kdks").readBytes()

    val fixturePlainThumbnail: ByteArray get() = File(fixtureAccount, "thumbs-plain/$MEDIA_ID.webp").readBytes()

    fun accountDir(context: Context): File = File(context.filesDir, "kodakiko/${V031Fixture.HASH}")

    fun plainThumbnail(context: Context): File = File(accountDir(context), "thumbs-plain/$MEDIA_ID.webp")

    fun objectFile(context: Context, objectId: String): File = File(accountDir(context), "objects/$objectId.kdks")

    fun makeThumbnailRemote(context: Context) {
        plainThumbnail(context).delete()
        makeRemote(context, THUMBNAIL_OBJECT)
    }

    fun makeOriginalRemote(context: Context) {
        makeRemote(context, ORIGINAL_OBJECT)
    }

    private fun makeRemote(context: Context, objectId: String) {
        objectFile(context, objectId).delete()
        KodakikoDatabase.open(context, V031Fixture.HASH).use { database ->
            database.transaction { tx ->
                val row = requireNotNull(tx.objects.get(objectId))
                tx.objects.put(row.copy(localState = ObjectLocalState.MISSING, uploadState = ObjectUploadState.UPLOADED))
            }
        }
    }

    fun clearOutbox(context: Context) {
        KodakikoDatabase.open(context, V031Fixture.HASH).use { database ->
            database.transaction { tx -> tx.outbox.all().forEach { tx.outbox.delete(it.id) } }
        }
    }

    fun writeSettings(context: Context, settings: DeviceSettings) {
        KodakikoDatabase.open(context, V031Fixture.HASH).use { database ->
            DeviceSettingsStore.open(database, DeviceSettingsDefaults.ANDROID).update(DeviceSettingsJson.encode(settings))
        }
    }
}

class FakeBucket {
    enum class Mode { SERVE, HOLD }

    private val socket = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
    private val connections = CopyOnWriteArrayList<Socket>()

    @Volatile
    var mode: Mode = Mode.SERVE

    val bodies: MutableMap<String, ByteArray> = ConcurrentHashMap()

    val requests: MutableList<String> = CopyOnWriteArrayList()
    val heldConnectionClosed = CountDownLatch(1)

    val baseUrl: String get() = "http://$LOOPBACK:${socket.localPort}"

    fun start(): FakeBucket {
        thread(isDaemon = true, name = "fake-bucket") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                connections += client
                thread(isDaemon = true, name = "fake-bucket-client") { serve(client) }
            }
        }
        return this
    }

    fun shutdown() {
        socket.close()
        connections.forEach { runCatching { it.close() } }
    }

    fun awaitHeldConnectionClosed(timeoutMs: Long): Boolean = heldConnectionClosed.await(timeoutMs, TimeUnit.MILLISECONDS)

    private fun serve(client: Socket) {
        try {
            val input = client.getInputStream()
            while (true) {
                val head = readHead(input) ?: return
                requests += head.lineSequence().first()
                if (mode == Mode.HOLD) {
                    holdUntilClosed(input)
                    return
                }
                val body = bodies[head.lineSequence().first().split(' ')[1].substringAfterLast('/')]
                val out = client.getOutputStream()
                if (body == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                } else {
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                    out.write(body)
                }
                out.flush()
            }
        } catch (_: Exception) {
            return
        } finally {
            runCatching { client.close() }
        }
    }

    private fun holdUntilClosed(input: InputStream) {
        try {
            while (input.read() != END_OF_STREAM) Unit
        } catch (_: Exception) {
            Unit
        }
        heldConnectionClosed.countDown()
    }

    private fun readHead(input: InputStream): String? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next == END_OF_STREAM) return null
            head.append(next.toChar())
        }
        return head.toString()
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val BACKLOG = 50
        const val END_OF_STREAM = -1
    }
}

class FakeKodakikoServer(private val sharingEnabled: Boolean, private val bucket: FakeBucket) : Dispatcher() {
    private val mock = MockWebServer()

    val calls: MutableList<String> = CopyOnWriteArrayList()

    val baseUrl: String get() = mock.url("/").toString().trimEnd('/')

    fun start(): FakeKodakikoServer {
        mock.dispatcher = this
        mock.start(InetAddress.getByName(LOOPBACK), 0)
        return this
    }

    fun shutdown() = mock.close()

    fun count(method: String, path: String): Int = calls.count { it == "$method $path" }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath
        calls += "${request.method} $path"
        return when {
            request.method == GET && path == Routes.config() -> json(ContractJson.stringify(ContractSchemas.publicConfigSchema.encode(config(sharingEnabled))))
            request.method == GET && path == Routes.objectDownload(V031Media.THUMBNAIL_OBJECT) -> download(V031Media.THUMBNAIL_OBJECT)
            request.method == GET && path == Routes.objectDownload(V031Media.ORIGINAL_OBJECT) -> download(V031Media.ORIGINAL_OBJECT)
            else -> MockResponse.Builder().code(NOT_FOUND).build()
        }
    }

    private fun download(objectId: String): MockResponse =
        json(ContractJson.stringify(ContractSchemas.downloadResponse.encode(DownloadResponse("${bucket.baseUrl}/bucket/$objectId", EXPIRES_AT))))

    private fun json(body: String): MockResponse = MockResponse.Builder().code(OK).addHeader("Content-Type", "application/json").body(body).build()

    companion object {
        const val GET = "GET"
        const val TOKEN = "fake-session-token-for-k007local"
        private const val LOOPBACK = "127.0.0.1"
        private const val OK = 200
        private const val NOT_FOUND = 404
        private const val EXPIRES_AT = "2099-01-01T00:00:00.000Z"

        fun config(sharingEnabled: Boolean): PublicConfigDto = PublicConfigDto(
            signupMode = SignupMode.INVITE,
            sharingEnabled = sharingEnabled,
            publicLinksEnabled = true,
            maxMediaPerEntry = 20,
            maxUploadBytes = 3_758_096_384L,
            defaultQuotaBytes = 10_737_418_240L,
            trashDays = 30,
            accountDeletionGraceDays = 7,
            serverVersion = "0.3.1",
        )
    }
}

object ServerFixture {
    @Volatile
    var server: FakeKodakikoServer? = null

    @Volatile
    var bucket: FakeBucket? = null

    fun requireServer(): FakeKodakikoServer = requireNotNull(server)

    fun requireBucket(): FakeBucket = requireNotNull(bucket)

    fun shutdown() {
        server?.shutdown()
        bucket?.shutdown()
        server = null
        bucket = null
    }

    fun install(
        context: Context,
        sharingEnabled: Boolean,
        bucketMode: FakeBucket.Mode,
        settings: DeviceSettings? = null,
        remoteThumbnail: Boolean = true,
        remoteOriginal: Boolean = false,
    ) {
        FakeAndroidKeyStore.install()
        V031Fixture.install(context)
        V031Media.clearOutbox(context)
        if (remoteThumbnail) V031Media.makeThumbnailRemote(context)
        if (remoteOriginal) V031Media.makeOriginalRemote(context)
        settings?.let { V031Media.writeSettings(context, it) }
        val bucket = FakeBucket().apply {
            mode = bucketMode
            bodies[V031Media.THUMBNAIL_OBJECT] = V031Media.fixtureThumbnailCiphertext
            bodies[V031Media.ORIGINAL_OBJECT] = V031Media.fixtureOriginalCiphertext
        }.start()
        val server = FakeKodakikoServer(sharingEnabled, bucket).start()
        this.bucket = bucket
        this.server = server
        val global = GlobalStore(context)
        global.setServerUrl(server.baseUrl)
        global.setServerConfigJson(ContractJson.stringify(ContractSchemas.publicConfigSchema.encode(FakeKodakikoServer.config(sharingEnabled))))
        global.setActiveAccount(ActiveAccount(ActiveAccount.CURRENT_SCHEMA_VERSION, V031Fixture.ACCOUNT_KEY, V031Fixture.USERNAME, AccountMode.SERVER))
        SecureSessionStore(SessionStore(context), createSodium()).save(V031Fixture.ACCOUNT_KEY, FakeKodakikoServer.TOKEN)
    }
}

class ServerV031App : KodakikoApp() {
    override fun onCreate() {
        ServerFixture.install(this, sharingEnabled = true, bucketMode = FakeBucket.Mode.SERVE)
        super.onCreate()
    }
}

class ServerV031HeldBucketApp : KodakikoApp() {
    override fun onCreate() {
        ServerFixture.install(this, sharingEnabled = true, bucketMode = FakeBucket.Mode.HOLD)
        super.onCreate()
    }
}

class ServerV031RemoteOriginalApp : KodakikoApp() {
    override fun onCreate() {
        ServerFixture.install(this, sharingEnabled = true, bucketMode = FakeBucket.Mode.SERVE, remoteThumbnail = false, remoteOriginal = true)
        super.onCreate()
    }
}

class ServerV031NoSharingApp : KodakikoApp() {
    override fun onCreate() {
        ServerFixture.install(this, sharingEnabled = false, bucketMode = FakeBucket.Mode.SERVE)
        super.onCreate()
    }
}

class CalendarStartServerV031App : KodakikoApp() {
    override fun onCreate() {
        ServerFixture.install(
            this,
            sharingEnabled = true,
            bucketMode = FakeBucket.Mode.SERVE,
            settings = DeviceSettingsDefaults.ANDROID.copy(startScreen = StartScreen.CALENDAR, hideBarsOnScroll = false),
        )
        super.onCreate()
    }
}

class CalendarStartV031App : KodakikoApp() {
    override fun onCreate() {
        FakeAndroidKeyStore.install()
        V031Fixture.install(this)
        V031Media.writeSettings(this, DeviceSettingsDefaults.ANDROID.copy(startScreen = StartScreen.CALENDAR, hideBarsOnScroll = false))
        V031Fixture.writeLegacyActiveAccount(this)
        super.onCreate()
    }
}
