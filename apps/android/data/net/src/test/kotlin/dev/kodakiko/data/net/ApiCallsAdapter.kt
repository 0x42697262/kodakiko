package dev.kodakiko.data.net

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.testing.GoldenAdapter
import dev.kodakiko.testing.UnsupportedGoldenFunction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

object ApiCallsAdapter : GoldenAdapter {
    private val builders: Map<String, (JsonArray) -> ApiCall<*>> = mapOf(
        "AUTH.params" to { a -> AuthCalls.params(ContractSchemas.paramsRequest.decode(a[0])) },
        "AUTH.signUp" to { a -> AuthCalls.signUp(ContractSchemas.signUpRequest.decode(a[0])) },
        "AUTH.signIn" to { a -> AuthCalls.signIn(ContractSchemas.signInRequest.decode(a[0])) },
        "AUTH.session" to { _ -> AuthCalls.session() },
        "AUTH.keyBundle" to { _ -> AuthCalls.keyBundle() },
        "AUTH.recoveryStart" to { a -> AuthCalls.recoveryStart(ContractSchemas.recoveryStartRequest.decode(a[0])) },
        "AUTH.recoveryComplete" to { a -> AuthCalls.recoveryComplete(ContractSchemas.recoveryCompleteRequest.decode(a[0])) },
        "AUTH.signOut" to { _ -> AuthCalls.signOut() },
        "AUTH.changePassword" to { a -> AuthCalls.changePassword(ContractSchemas.passwordChangeRequest.decode(a[0])) },
        "AUTH.requestDeletion" to { _ -> AuthCalls.requestDeletion() },
        "AUTH.cancelDeletion" to { _ -> AuthCalls.cancelDeletion() },
        "SYNC.syncState" to { _ -> SyncCalls.syncState() },
        "SYNC.collection" to { a -> SyncCalls.collection(id(a[0])) },
        "SYNC.diff" to { a -> SyncCalls.diff(id(a[0]), a[1].jsonPrimitive.long, a[2].jsonPrimitive.long) },
        "SYNC.createEntry" to { a -> SyncCalls.createEntry(ContractSchemas.entryCreateRequest.decode(a[0])) },
        "SYNC.getEntry" to { a -> SyncCalls.getEntry(id(a[0])) },
        "SYNC.patchEntry" to { a -> SyncCalls.patchEntry(id(a[0]), ContractSchemas.entryPatchRequest.decode(a[1])) },
        "SYNC.trashEntry" to { a -> SyncCalls.trashEntry(id(a[0])) },
        "SYNC.restoreEntry" to { a -> SyncCalls.restoreEntry(id(a[0])) },
        "SYNC.deleteEntry" to { a -> SyncCalls.deleteEntry(id(a[0])) },
        "SYNC.addMedia" to { a -> SyncCalls.addMedia(id(a[0]), ContractSchemas.mediaCreateRequest.decode(a[1])) },
        "SYNC.removeMedia" to { a -> SyncCalls.removeMedia(id(a[0])) },
        "SYNC.presign" to { a -> SyncCalls.presign(ContractSchemas.presignRequest.decode(a[0])) },
        "SYNC.parts" to { a -> SyncCalls.parts(id(a[0])) },
        "SYNC.confirm" to { a -> SyncCalls.confirm(id(a[0])) },
        "OBJECT.download" to { a -> ObjectCalls.download(id(a[0])) },
        "SHARING.create" to { a -> SharingCalls.create(ContractSchemas.collectionCreateRequest.decode(a[0])) },
        "SHARING.rename" to { a -> SharingCalls.rename(id(a[0]), bytes(a[1])) },
        "SHARING.remove" to { a -> SharingCalls.remove(id(a[0])) },
        "SHARING.addEntry" to { a -> SharingCalls.addEntry(id(a[0]), id(a[1]), bytes(a[2])) },
        "SHARING.removeEntry" to { a -> SharingCalls.removeEntry(id(a[0]), id(a[1])) },
        "SHARING.publicKey" to { a -> SharingCalls.publicKey(text(a[0])) },
        "SHARING.share" to { a -> SharingCalls.share(id(a[0]), id(a[1]), bytes(a[2])) },
        "SHARING.listShares" to { a -> SharingCalls.listShares(id(a[0])) },
        "SHARING.unshare" to { a -> SharingCalls.unshare(id(a[0]), id(a[1])) },
        "CONFIG.get" to { _ -> ConfigCalls.get() },
        "LINK.create" to { a -> LinkCalls.create(id(a[0]), bytes(a[1]), ContractSchemas.linkExpirySchema.decode(a[2])) },
        "LINK.list" to { _ -> LinkCalls.list() },
        "LINK.revoke" to { a -> LinkCalls.revoke(id(a[0])) },
        "ACCOUNT.me" to { _ -> AccountCalls.me() },
        "ADMIN.listUsers" to { a -> AdminCalls.listUsers(if (a[0] is JsonNull) null else text(a[0])) },
        "ADMIN.patchUser" to { a -> AdminCalls.patchUser(id(a[0]), ContractSchemas.adminUserPatchRequest.decode(a[1])) },
        "ADMIN.createInvite" to { a -> AdminCalls.createInvite(ContractSchemas.adminInviteCreateRequest.decode(a[0])) },
        "ADMIN.listInvites" to { _ -> AdminCalls.listInvites() },
        "ADMIN.revokeInvite" to { a -> AdminCalls.revokeInvite(id(a[0])) },
        "ADMIN.stats" to { _ -> AdminCalls.stats() },
    )

    fun portedCalls(): Set<String> = builders.keys

    override fun call(fn: String, args: JsonObject): JsonElement {
        if (fn != "call") throw UnsupportedGoldenFunction(fn)
        val name = text(args.getValue("group")) + "." + text(args.getValue("call"))
        return encode(builders.getValue(name)(args.getValue("args").jsonArray))
    }

    private fun id(json: JsonElement): String = ContractSchemas.idSchema.decode(json)

    private fun bytes(json: JsonElement): String = ContractSchemas.bytesSchema.decode(json)

    private fun text(json: JsonElement): String = (json as JsonPrimitive).also { check(it.isString) }.content

    private fun encode(call: ApiCall<*>): JsonElement = buildJsonObject {
        put("method", call.method)
        put("path", call.path)
        put("auth", encode(call.auth))
        put("idempotent", call.idempotent)
        put("timeoutMs", call.timeoutMs)
        put("body", call.body ?: JsonNull)
        put("responseSchema", call.responseSchema.name)
    }

    private fun encode(auth: ApiAuth): JsonElement = when (auth) {
        ApiAuth.Session -> JsonPrimitive("session")
        ApiAuth.None -> JsonPrimitive("none")
        is ApiAuth.Link -> buildJsonObject {
            put("linkToken", auth.linkToken)
            put("viewToken", auth.viewToken)
        }
    }
}
