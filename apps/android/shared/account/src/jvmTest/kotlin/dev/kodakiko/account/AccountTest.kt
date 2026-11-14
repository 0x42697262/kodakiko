package dev.kodakiko.account

import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.openPayload
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.Argon2idInput
import dev.kodakiko.crypto.InvalidRecoveryWordsError
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.bytesEqual
import dev.kodakiko.crypto.createSodium
import dev.kodakiko.crypto.unwrapKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountTest {
    private val sodium = createSodium()
    private val password = "correct horse battery staple"
    private val otherPassword = "a completely different one"
    private val username = "alice"
    private val createdAt = "2026-11-12T10:00:00.000Z"
    private val homeName = "Journal"
    private val mebibyte = 1_048_576L

    private fun fastParams(salt: ByteArray) = KdfParams(salt, 2, 8_388_608)

    private fun idSource(): () -> String {
        val ids = listOf("AAAAAAAAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBBBBBBBB")
        var position = 0
        return { ids.getOrElse(position++) { "CCCCCCCCCCCCCCCCCCCCCC" } }
    }

    private fun signUpInput(name: String = username) = SignUpInput(name, password, homeName, createdAt, idSource())

    private fun paramsOf(salt: ByteArray, opsLimit: Long, memLimitBytes: Long) = KdfParams(salt, opsLimit, memLimitBytes)

    private fun bundleOf(request: SignUpRequestBytes) = KeyBundleBytes(
        salt = request.salt,
        opsLimit = request.opsLimit,
        memLimitBytes = request.memLimitBytes,
        masterKeyByKek = request.masterKeyByKek,
        recoveryKeyByMaster = request.recoveryKeyByMaster,
        publicKey = request.publicKey,
        privateKeyByMaster = request.privateKeyByMaster,
    )

    private class CountingArgon(real: Sodium, private val behavior: (Argon2idInput) -> ByteArray) : Sodium by real {
        var calls = 0

        override fun argon2id(input: Argon2idInput): ByteArray {
            calls++
            return behavior(input)
        }
    }

    @Test
    fun usernameNormalizesWithNfkcTrimAndLowerCase() {
        assertEquals("alice", normalizeUsername("  \uFF21Lice  "))
        assertEquals("bob.smith", normalizeUsername("Bob.Smith"))
    }

    @Test
    fun usernameValidatesAgainstTheContractPattern() {
        assertTrue(isValidUsername("alice"))
        assertTrue(isValidUsername("a.b_c-d"))
        assertFalse(isValidUsername("ab"))
        assertFalse(isValidUsername("a".repeat(33)))
        assertFalse(isValidUsername("Alice"))
        assertFalse(isValidUsername("has space"))
    }

    @Test
    fun passwordPolicy() {
        val composed = { pairs: Int -> "e\u0301a\u0301".repeat(pairs) }
        val cases = listOf(
            Triple("abcdefghijk", username, listOf(PasswordRule.TOO_SHORT)),
            Triple("abcdefghijkl", username, emptyList()),
            Triple("Alice-Wonderland", "alice-wonderland", listOf(PasswordRule.EQUALS_USERNAME)),
            Triple("aaaaaaaaaaaa", username, listOf(PasswordRule.SINGLE_REPEATED_CHARACTER)),
            Triple("", username, listOf(PasswordRule.TOO_SHORT)),
            Triple(composed(6), username, emptyList()),
            Triple(composed(5) + "e\u0301", username, listOf(PasswordRule.TOO_SHORT)),
            Triple("\uD83D\uDE00\uD83D\uDE01\uD83D\uDE02\uD83E\uDD23\uD83D\uDE03\uD83D\uDE04\uD83D\uDE05\uD83D\uDE06\uD83D\uDE09\uD83D\uDE0A\uD83D\uDE0B", username, listOf(PasswordRule.TOO_SHORT)),
        )
        for ((candidate, name, expected) in cases) assertEquals(expected, checkPasswordPolicy(candidate, name), candidate)
    }

    @Test
    fun kdfParamsStartHalveAndCheckTheFloor() {
        val salt = ByteArray(SALT_BYTES)
        val start = startKdfParams(salt)
        assertEquals(ARGON2ID_START_OPS, start.opsLimit)
        assertEquals(ARGON2ID_START_MEM_BYTES, start.memLimitBytes)
        val second = nextKdfParamsAfterFailure(start)!!
        assertEquals(ARGON2ID_START_OPS * 2, second.opsLimit)
        assertEquals(ARGON2ID_MIN_MEM_BYTES, second.memLimitBytes)
        assertNull(nextKdfParamsAfterFailure(second))
        assertTrue(meetsKdfFloor(32, ARGON2ID_MIN_MEM_BYTES))
        assertTrue(meetsKdfFloor(16, ARGON2ID_START_MEM_BYTES))
        assertFalse(meetsKdfFloor(31, ARGON2ID_MIN_MEM_BYTES))
        assertFalse(meetsKdfFloor(15, ARGON2ID_START_MEM_BYTES))
        assertFalse(meetsKdfFloor(64, 64 * mebibyte))
    }

    @Test
    fun adaptiveDerivationEndsAt128MiBAnd32Ops() {
        val fake = CountingArgon(sodium) { input ->
            if (input.memLimitBytes > ARGON2ID_MIN_MEM_BYTES) error("out of memory")
            ByteArray(KEY_BYTES) { 7 }
        }
        val derived = deriveWithAdaptiveParams(fake, password, ByteArray(SALT_BYTES) { 1 })
        assertEquals(ARGON2ID_MIN_MEM_BYTES, derived.params.memLimitBytes)
        assertEquals(ARGON2ID_START_OPS * 2, derived.params.opsLimit)
        assertEquals(2, fake.calls)
    }

    @Test
    fun adaptiveDerivationThrowsWhenEveryAttemptFails() {
        val fake = CountingArgon(sodium) { error("out of memory") }
        assertFailsWith<KdfUnavailableError> { deriveWithAdaptiveParams(fake, password, ByteArray(SALT_BYTES)) }
    }

    @Test
    fun signInRefusesHostileParamsAndShortSaltsBeforeArgon2id() {
        val fake = CountingArgon(sodium) { ByteArray(KEY_BYTES) }
        assertFailsWith<HostileServerParamsError> { deriveForSignIn(fake, password, KdfParams(ByteArray(SALT_BYTES), 64, 64 * mebibyte)) }
        assertFailsWith<HostileServerParamsError> {
            deriveForSignIn(fake, password, KdfParams(ByteArray(SALT_BYTES - 1), ARGON2ID_START_OPS, ARGON2ID_START_MEM_BYTES))
        }
        assertEquals(0, fake.calls)
    }

    @Test
    fun derivationIsDeterministic() {
        val params = fastParams(ByteArray(SALT_BYTES) { 3 })
        val first = deriveLoginMaterial(sodium, password, params)
        val second = deriveLoginMaterial(sodium, password, params)
        val other = deriveLoginMaterial(sodium, otherPassword, params)
        assertTrue(bytesEqual(first.kek, second.kek))
        assertTrue(bytesEqual(first.loginKey, second.loginKey))
        assertFalse(bytesEqual(first.loginKey, first.kek))
        assertFalse(bytesEqual(first.loginKey, other.loginKey))
    }

    @Test
    fun signUpOpensWithKeyBundleAndHomeCollection() {
        val material = createSignUpMaterial(sodium, signUpInput(), ::fastParams)
        val request = material.request
        assertEquals(material.secrets.userId, request.userId)
        assertEquals(username, request.username)
        assertEquals(SALT_BYTES, request.salt.size)
        val login = deriveLoginMaterial(sodium, password, paramsOf(request.salt, request.opsLimit, request.memLimitBytes))
        assertTrue(bytesEqual(login.loginKey, request.loginKey))
        val keys = openKeyBundle(sodium, login.kek, request.userId, bundleOf(request))
        assertTrue(bytesEqual(keys.masterKey, material.secrets.masterKey))
        assertTrue(bytesEqual(keys.privateKey, material.secrets.privateKey))
        assertTrue(bytesEqual(keys.publicKey, material.secrets.publicKey))
        assertEquals(request.homeCollection.id, material.secrets.homeCollectionId)
        assertNotEquals(material.secrets.userId, material.secrets.homeCollectionId)
        val collectionKey = unwrapKey(sodium, material.secrets.masterKey, request.homeCollection.keyByOwner, AdContext.COLLECTION_KEY_BY_MASTER, material.secrets.homeCollectionId)
        assertTrue(bytesEqual(collectionKey, material.secrets.homeCollectionKey))
        val schema = ContractSchemas.all.getValue("CollectionMetadataV1")
        val metadata = openPayload(sodium, collectionKey, request.homeCollection.metadataBlob, schema, 1, emptyMap(), AdContext.COLLECTION_METADATA, material.secrets.homeCollectionId)
        val json = schema.encode(metadata) as JsonObject
        assertEquals(homeName, json.getValue("name").jsonPrimitive.content)
        assertEquals(createdAt, json.getValue("createdAtUtc").jsonPrimitive.content)
    }

    @Test
    fun signUpProducesWrapsOf73BytesAnd32ByteKeys() {
        val request = createSignUpMaterial(sodium, signUpInput(), ::fastParams).request
        for (wrap in listOf(request.masterKeyByKek, request.masterKeyByRecovery, request.recoveryKeyByMaster, request.privateKeyByMaster, request.homeCollection.keyByOwner)) {
            assertEquals(73, wrap.size)
        }
        assertEquals(KEY_BYTES, request.loginKey.size)
        assertEquals(KEY_BYTES, request.recoveryLoginKey.size)
        assertEquals(KEY_BYTES, request.publicKey.size)
    }

    @Test
    fun recoveryWordsOpenTheRecoveryWraps() {
        val material = createSignUpMaterial(sodium, signUpInput(), ::fastParams)
        val request = material.request
        assertEquals(24, material.recoveryWords.size)
        val recovered = openRecovery(sodium, material.recoveryWords, request.userId, request.masterKeyByRecovery)
        assertTrue(bytesEqual(recovered.masterKey, material.secrets.masterKey))
        assertTrue(bytesEqual(recoveryLoginKey(sodium, recovered.recoveryKey), request.recoveryLoginKey))
        val back = unwrapKey(sodium, material.secrets.masterKey, request.recoveryKeyByMaster, AdContext.RECOVERY_KEY_BY_MASTER, request.userId)
        assertTrue(bytesEqual(back, recovered.recoveryKey))
    }

    @Test
    fun wrongPasswordAndWrongUserIdAreRejected() {
        val request = createSignUpMaterial(sodium, signUpInput(), ::fastParams).request
        val wrong = deriveLoginMaterial(sodium, otherPassword, paramsOf(request.salt, request.opsLimit, request.memLimitBytes))
        assertFailsWith<WrongPasswordError> { openKeyBundle(sodium, wrong.kek, request.userId, bundleOf(request)) }
        val right = deriveLoginMaterial(sodium, password, paramsOf(request.salt, request.opsLimit, request.memLimitBytes))
        assertFailsWith<WrongPasswordError> { openKeyBundle(sodium, right.kek, "ZZZZZZZZZZZZZZZZZZZZZZ", bundleOf(request)) }
    }

    @Test
    fun passwordChangeRewrapsTheMasterKey() {
        val material = createSignUpMaterial(sodium, signUpInput(), ::fastParams)
        val request = material.request
        val changed = createPasswordChangeMaterial(
            sodium,
            PasswordChangeInput(request.userId, password, otherPassword, paramsOf(request.salt, request.opsLimit, request.memLimitBytes), request.masterKeyByKek),
            ::fastParams,
        )
        assertTrue(bytesEqual(changed.currentLoginKey, request.loginKey))
        assertFalse(bytesEqual(changed.salt, request.salt))
        val login = deriveLoginMaterial(sodium, otherPassword, paramsOf(changed.salt, changed.opsLimit, changed.memLimitBytes))
        assertTrue(bytesEqual(login.loginKey, changed.loginKey))
        val masterKey = unwrapKey(sodium, login.kek, changed.masterKeyByKek, AdContext.MASTER_KEY_BY_KEK, request.userId)
        assertTrue(bytesEqual(masterKey, material.secrets.masterKey))
    }

    @Test
    fun passwordChangeWithAWrongCurrentPasswordRunsArgon2idOnce() {
        val request = createSignUpMaterial(sodium, signUpInput(), ::fastParams).request
        val counting = CountingArgon(sodium) { sodium.argon2id(it) }
        assertFailsWith<WrongPasswordError> {
            createPasswordChangeMaterial(
                counting,
                PasswordChangeInput(request.userId, otherPassword, "yet another password", paramsOf(request.salt, request.opsLimit, request.memLimitBytes), request.masterKeyByKek),
                ::fastParams,
            )
        }
        assertEquals(1, counting.calls)
    }

    @Test
    fun recoveryCompletionSignsInWithTheNewPassword() {
        val material = createSignUpMaterial(sodium, signUpInput(), ::fastParams)
        val request = material.request
        val opened = openRecovery(sodium, material.recoveryWords, request.userId, request.masterKeyByRecovery)
        val completion = createRecoveryCompletion(sodium, RecoveryCompletionInput(request.userId, otherPassword, opened.masterKey, opened.recoveryKey), ::fastParams)
        assertTrue(bytesEqual(completion.recoveryLoginKey, request.recoveryLoginKey))
        val login = deriveLoginMaterial(sodium, otherPassword, paramsOf(completion.salt, completion.opsLimit, completion.memLimitBytes))
        assertTrue(bytesEqual(login.loginKey, completion.loginKey))
        val masterKey = unwrapKey(sodium, login.kek, completion.masterKeyByKek, AdContext.MASTER_KEY_BY_KEK, request.userId)
        assertTrue(bytesEqual(masterKey, material.secrets.masterKey))
    }

    @Test
    fun recoveryWithWordsOfAnotherKeyOrAnUnknownWordFails() {
        val first = createSignUpMaterial(sodium, signUpInput("alice"), ::fastParams)
        val second = createSignUpMaterial(sodium, signUpInput("bobby"), ::fastParams)
        assertFailsWith<WrongRecoveryWordsError> { openRecovery(sodium, second.recoveryWords, first.request.userId, first.request.masterKeyByRecovery) }
        val tampered = first.recoveryWords.toMutableList().also { it[0] = "zzzzzz" }
        assertFailsWith<InvalidRecoveryWordsError> { openRecovery(sodium, tampered, first.request.userId, first.request.masterKeyByRecovery) }
    }

    @Test
    fun localAccountRebuildsTheSignUpRequest() {
        val material = createSignUpMaterial(sodium, signUpInput(), ::fastParams)
        val request = material.request
        val stored = StoredLocalAccount(
            userId = request.userId,
            kdfParams = paramsOf(request.salt, request.opsLimit, request.memLimitBytes),
            masterKeyByKek = request.masterKeyByKek,
            masterKeyByRecovery = request.masterKeyByRecovery,
            recoveryKeyByMaster = request.recoveryKeyByMaster,
            publicKey = request.publicKey,
            privateKeyByMaster = request.privateKeyByMaster,
            homeCollection = request.homeCollection,
        )
        val rebuilt = rebuildSignUpRequest(sodium, password, username, stored)
        assertTrue(bytesEqual(rebuilt.loginKey, request.loginKey))
        assertTrue(bytesEqual(rebuilt.recoveryLoginKey, request.recoveryLoginKey))
        assertTrue(bytesEqual(rebuilt.masterKeyByKek, request.masterKeyByKek))
        assertEquals(request.homeCollection.id, rebuilt.homeCollection.id)
        assertFailsWith<WrongPasswordError> { rebuildSignUpRequest(sodium, otherPassword, username, stored) }
    }
}
