package dev.kodakiko.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.rules.ExternalResource
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

val repoRoot: File by lazy {
    generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, E2eConstants.REPO_MARKER).isFile && File(it, E2eConstants.ANDROID_DIRECTORY).isDirectory }
}

private fun portOpen(port: Int): Boolean =
    try {
        Socket().use { it.connect(InetSocketAddress(E2eConstants.LOOPBACK, port), E2eConstants.REACHABILITY_TIMEOUT_MS) }
        true
    } catch (_: IOException) {
        false
    }

private fun nodeAvailable(): Boolean =
    try {
        ProcessBuilder(E2eConstants.NODE_COMMAND, E2eConstants.NODE_VERSION_FLAG).redirectErrorStream(true).start().waitFor(
            E2eConstants.LAUNCH_TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
    } catch (_: IOException) {
        false
    }

fun testStackReachable(): Boolean =
    portOpen(E2eConstants.POSTGRES_PORT) &&
        portOpen(E2eConstants.GARAGE_PORT) &&
        File(repoRoot, E2eConstants.GARAGE_KEY_FILE).isFile &&
        nodeAvailable()

data class SeededInvite(val token: String, val url: String)

data class ServerEntry(val revision: Long, val metadataBlob: String, val trashedAt: String?)

class E2eServer(private val env: Map<String, String> = emptyMap()) : ExternalResource() {
    private var process: Process? = null
    private val control = OkHttpClient()

    lateinit var baseUrl: String
        private set

    private lateinit var controlUrl: String

    override fun before() {
        assumeTrue(E2eConstants.STACK_UNREACHABLE, testStackReachable())
        val envJson = JsonObject(env.mapValues { JsonPrimitive(it.value) }).toString()
        val started = ProcessBuilder(
            E2eConstants.NODE_COMMAND,
            E2eConstants.NODE_IMPORT_FLAG,
            E2eConstants.RESOLVE_HOOKS,
            E2eConstants.LAUNCHER,
            envJson,
        ).directory(File(repoRoot, E2eConstants.E2E_DIRECTORY))
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        process = started
        val reader = started.inputStream.bufferedReader()
        val executor = Executors.newSingleThreadExecutor()
        val line = try {
            executor.submit<String?> { reader.readLine() }.get(E2eConstants.LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        val ready = Json.parseToJsonElement(line ?: error("the e2e server launcher exited before it was ready")).jsonObject
        baseUrl = ready.getValue(E2eConstants.BASE_URL_FIELD).jsonPrimitive.content
        controlUrl = ready.getValue(E2eConstants.CONTROL_URL_FIELD).jsonPrimitive.content
    }

    override fun after() {
        val running = process ?: return
        try {
            post(ControlRoutes.CLOSE, JsonObject(emptyMap()))
        } catch (_: IOException) {
            running.outputStream.close()
        }
        if (!running.waitFor(E2eConstants.SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) running.destroyForcibly()
        process = null
    }

    private fun post(route: String, body: JsonElement): ByteArray {
        val request = Request.Builder()
            .url(controlUrl + route)
            .post(body.toString().toRequestBody(ControlFields.JSON_MEDIA_TYPE.toMediaType()))
            .build()
        control.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("control $route failed: ${response.code} ${response.body.string()}")
            return response.body.bytes()
        }
    }

    private fun postJson(route: String, body: JsonElement): JsonObject = Json.parseToJsonElement(post(route, body).decodeToString()).jsonObject

    fun findServerEntry(entryId: String): ServerEntry? {
        val entry = postJson(ControlRoutes.ENTRY, buildJsonObject { put(ControlFields.ENTRY_ID, entryId) })[ControlFields.ENTRY]
        if (entry == null || entry is JsonNull) return null
        val fields = entry.jsonObject
        val trashedAt = fields[ControlFields.TRASHED_AT]
        return ServerEntry(
            revision = fields.getValue(ControlFields.REVISION).jsonPrimitive.content.toLong(),
            metadataBlob = fields.getValue(ControlFields.METADATA_BLOB).jsonPrimitive.content,
            trashedAt = if (trashedAt == null || trashedAt is JsonNull) null else trashedAt.jsonPrimitive.content,
        )
    }

    fun serverEntry(entryId: String): ServerEntry = findServerEntry(entryId) ?: error("the server has no entry $entryId")

    fun setQuota(userId: String, quotaBytes: Long?) {
        post(
            ControlRoutes.QUOTA,
            buildJsonObject {
                put(ControlFields.USER_ID, userId)
                put(ControlFields.QUOTA_BYTES, quotaBytes)
            },
        )
    }

    fun rewindEntry(entryId: String, collectionId: String, older: ServerEntry) {
        post(
            ControlRoutes.REWIND,
            buildJsonObject {
                put(ControlFields.ENTRY_ID, entryId)
                put(ControlFields.COLLECTION_ID, collectionId)
                put(ControlFields.REVISION, older.revision)
                put(ControlFields.METADATA_BLOB, older.metadataBlob)
            },
        )
    }

    fun pruneTombstonesWithMovedClock() {
        post(ControlRoutes.PRUNE_TOMBSTONES, JsonObject(emptyMap()))
    }

    fun openLink(url: String, read: Boolean): JsonObject =
        postJson(
            ControlRoutes.OPEN_LINK,
            buildJsonObject {
                put(ControlFields.URL, url)
                put(ControlFields.READ, read)
            },
        )

    fun seedInvite(): SeededInvite {
        val fields = postJson(ControlRoutes.INVITE, JsonObject(emptyMap()))
        return SeededInvite(fields.getValue(ControlFields.TOKEN).jsonPrimitive.content, fields.getValue(ControlFields.INVITE_URL).jsonPrimitive.content)
    }

    fun media(route: String): ByteArray = post(route, JsonObject(emptyMap()))
}
