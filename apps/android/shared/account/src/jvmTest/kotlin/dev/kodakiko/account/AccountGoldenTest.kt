package dev.kodakiko.account

import dev.kodakiko.contract.decodeBytes
import dev.kodakiko.contract.encodeBytes
import dev.kodakiko.crypto.BoxKeypair
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.bytesToHex
import dev.kodakiko.crypto.createCounterRandom
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.hexToBytes
import dev.kodakiko.crypto.normalizeRecoveryInput
import dev.kodakiko.crypto.passwordToBytes
import dev.kodakiko.crypto.withRandomSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountGoldenTest {
    private val sodium = createSodium()

    private val repoRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "AGENTS.md").isFile && File(it, "apps/android").isDirectory }

    private fun cases(path: String): List<JsonObject> =
        Json.parseToJsonElement(File(repoRoot, "tests/fixtures/golden/$path").readText())
            .jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    private fun JsonObject.obj(name: String) = getValue(name).jsonObject

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    private fun JsonObject.hex(name: String) = hexToBytes(text(name))

    private fun goldenSodium(seed: Int): Sodium {
        val seeded = sodium.withRandomSource(createCounterRandom(seed))
        return object : Sodium by seeded {
            override fun boxKeypair(): BoxKeypair = seeded.boxSeedKeypair(seeded.randomBytes(32))
        }
    }

    private fun fixedParams(kdf: JsonObject): (ByteArray) -> KdfParams = { salt ->
        KdfParams(salt, kdf.getValue("opsLimit").jsonPrimitive.long, kdf.getValue("memLimitBytes").jsonPrimitive.long)
    }

    private fun assertHex(expected: JsonObject, field: String, actual: ByteArray, name: String) =
        assertEquals(expected.text(field), bytesToHex(actual), "$name $field")

    private fun assertWire(request: JsonObject, field: String, actual: ByteArray, name: String) =
        assertEquals(bytesToHex(decodeBytes(request.text(field))), bytesToHex(actual), "$name request.$field")

    private fun signUpOutputs(name: String): JsonObject = cases("account/signup.json").single { it.text("name") == name }.obj("outputs")

    @Test
    fun signUpMaterialMatchesTheTypeScriptGoldens() {
        for (case in cases("account/signup.json")) {
            val name = case.text("name")
            val inputs = case.obj("inputs")
            val outputs = case.obj("outputs")
            val golden = goldenSodium(inputs.getValue("randomSeed").jsonPrimitive.int)
            val idBytes = inputs.getValue("randomIdBytes").jsonPrimitive.int
            val material = createSignUpMaterial(
                golden,
                SignUpInput(inputs.text("username"), inputs.text("password"), inputs.text("homeCollectionName"), inputs.text("createdAtUtc")) {
                    encodeBytes(golden.randomBytes(idBytes))
                },
                fixedParams(inputs.obj("kdf")),
            )
            val request = outputs.obj("request")
            assertHex(outputs, "passwordBytes", passwordToBytes(inputs.text("password")), name)
            assertHex(outputs, "salt", material.request.salt, name)
            assertHex(outputs, "loginKey", material.request.loginKey, name)
            assertHex(outputs, "recoveryLoginKey", material.request.recoveryLoginKey, name)
            assertHex(outputs, "masterKey", material.secrets.masterKey, name)
            assertHex(outputs, "homeCollectionKey", material.secrets.homeCollectionKey, name)
            assertHex(outputs, "publicKey", material.secrets.publicKey, name)
            assertHex(outputs, "privateKey", material.secrets.privateKey, name)
            assertEquals(outputs.text("userId"), material.secrets.userId, name)
            assertEquals(outputs.text("homeCollectionId"), material.secrets.homeCollectionId, name)
            assertEquals(outputs.getValue("recoveryWords").jsonArray.map { it.jsonPrimitive.content }, material.recoveryWords, name)
            assertEquals(request.text("userId"), material.request.userId, name)
            assertEquals(request.text("username"), material.request.username, name)
            assertEquals(request.getValue("opsLimit").jsonPrimitive.long, material.request.opsLimit, name)
            assertEquals(request.getValue("memLimitBytes").jsonPrimitive.long, material.request.memLimitBytes, name)
            for ((field, bytes) in listOf(
                "salt" to material.request.salt,
                "loginKey" to material.request.loginKey,
                "recoveryLoginKey" to material.request.recoveryLoginKey,
                "publicKey" to material.request.publicKey,
                "masterKeyByKek" to material.request.masterKeyByKek,
                "masterKeyByRecovery" to material.request.masterKeyByRecovery,
                "recoveryKeyByMaster" to material.request.recoveryKeyByMaster,
                "privateKeyByMaster" to material.request.privateKeyByMaster,
            )) {
                assertWire(request, field, bytes, name)
            }
            val home = request.obj("homeCollection")
            assertEquals(home.text("id"), material.request.homeCollection.id, name)
            assertWire(home, "keyByOwner", material.request.homeCollection.keyByOwner, name)
            assertWire(home, "metadataBlob", material.request.homeCollection.metadataBlob, name)
        }
    }

    @Test
    fun recoveryMatchesTheTypeScriptGoldens() {
        for (case in cases("account/recovery.json")) {
            val name = case.text("name")
            val inputs = case.obj("inputs")
            val outputs = case.obj("outputs")
            val words = normalizeRecoveryInput(inputs.text("wordsInput"))
            assertEquals(outputs.getValue("normalizedWords").jsonArray.map { it.jsonPrimitive.content }, words, name)
            val opened = openRecovery(sodium, words, inputs.text("userId"), inputs.hex("masterKeyByRecovery"))
            assertHex(outputs, "recoveryKey", opened.recoveryKey, name)
            assertHex(outputs, "masterKey", opened.masterKey, name)
            val golden = goldenSodium(inputs.getValue("randomSeed").jsonPrimitive.int)
            val completion = createRecoveryCompletion(
                golden,
                RecoveryCompletionInput(inputs.text("userId"), inputs.text("newPassword"), opened.masterKey, opened.recoveryKey),
                fixedParams(inputs.obj("kdf")),
            )
            assertHex(outputs, "salt", completion.salt, name)
            assertHex(outputs, "loginKey", completion.loginKey, name)
            assertHex(outputs, "recoveryLoginKey", completion.recoveryLoginKey, name)
            assertHex(outputs, "masterKeyByKek", completion.masterKeyByKek, name)
            val request = outputs.obj("request")
            assertWire(request, "masterKeyByKek", completion.masterKeyByKek, name)
            assertWire(request, "loginKey", completion.loginKey, name)
        }
    }

    @Test
    fun passwordChangeMatchesTheTypeScriptGoldens() {
        for (case in cases("account/password-change.json")) {
            val name = case.text("name")
            val inputs = case.obj("inputs")
            val outputs = case.obj("outputs")
            val current = inputs.obj("currentKdf")
            val golden = goldenSodium(inputs.getValue("randomSeed").jsonPrimitive.int)
            val changed = createPasswordChangeMaterial(
                golden,
                PasswordChangeInput(
                    inputs.text("userId"),
                    inputs.text("currentPassword"),
                    inputs.text("newPassword"),
                    KdfParams(current.hex("salt"), current.getValue("opsLimit").jsonPrimitive.long, current.getValue("memLimitBytes").jsonPrimitive.long),
                    inputs.hex("cachedMasterKeyByKek"),
                ),
                fixedParams(inputs.obj("kdf")),
            )
            assertHex(outputs, "currentLoginKey", changed.currentLoginKey, name)
            assertHex(outputs, "salt", changed.salt, name)
            assertHex(outputs, "loginKey", changed.loginKey, name)
            assertHex(outputs, "masterKeyByKek", changed.masterKeyByKek, name)
            assertWire(outputs.obj("request"), "masterKeyByKek", changed.masterKeyByKek, name)
        }
    }

    @Test
    fun localAccountRebuildMatchesTheTypeScriptGoldens() {
        for (case in cases("account/local-account.json")) {
            val name = case.text("name")
            val inputs = case.obj("inputs")
            val outputs = case.obj("outputs")
            val stored = inputs.obj("stored")
            val kdf = stored.obj("kdfParams")
            val home = stored.obj("homeCollection")
            val rebuilt = rebuildSignUpRequest(
                sodium,
                inputs.text("password"),
                inputs.text("username"),
                StoredLocalAccount(
                    userId = stored.text("userId"),
                    kdfParams = KdfParams(kdf.hex("salt"), kdf.getValue("opsLimit").jsonPrimitive.long, kdf.getValue("memLimitBytes").jsonPrimitive.long),
                    masterKeyByKek = stored.hex("masterKeyByKek"),
                    masterKeyByRecovery = stored.hex("masterKeyByRecovery"),
                    recoveryKeyByMaster = stored.hex("recoveryKeyByMaster"),
                    publicKey = stored.hex("publicKey"),
                    privateKeyByMaster = stored.hex("privateKeyByMaster"),
                    homeCollection = HomeCollectionBytes(home.text("id"), home.hex("keyByOwner"), home.hex("metadataBlob")),
                ),
            )
            assertHex(outputs, "loginKey", rebuilt.loginKey, name)
            assertHex(outputs, "recoveryLoginKey", rebuilt.recoveryLoginKey, name)
            val request = outputs.obj("request")
            assertWire(request, "loginKey", rebuilt.loginKey, name)
            assertWire(request, "masterKeyByKek", rebuilt.masterKeyByKek, name)
            assertWire(request, "masterKeyByRecovery", rebuilt.masterKeyByRecovery, name)
            assertEquals(request.obj("homeCollection").text("id"), rebuilt.homeCollection.id, name)
        }
        val fresh = cases("account/local-account.json").single { it.text("name") == "fresh-local-account" }
        assertEquals(signUpOutputs("non-ascii-password-with-invite").obj("request"), fresh.obj("outputs").obj("request"))
    }
}
