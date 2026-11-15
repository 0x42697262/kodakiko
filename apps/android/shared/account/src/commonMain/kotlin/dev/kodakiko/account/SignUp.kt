package dev.kodakiko.account

import dev.kodakiko.contract.CollectionMetadataV1
import dev.kodakiko.contract.ContractSchemas
import dev.kodakiko.contract.sealPayload
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.Sodium
import dev.kodakiko.crypto.encodeRecoveryKey
import dev.kodakiko.crypto.wipe
import dev.kodakiko.crypto.wrapKey

class SignUpInput(
    val username: String,
    val password: String,
    val homeCollectionName: String,
    val createdAtUtc: String,
    val randomId: () -> String,
)

class HomeCollectionBytes(val id: String, val keyByOwner: ByteArray, val metadataBlob: ByteArray)

class SignUpRequestBytes(
    val userId: String,
    val username: String,
    val salt: ByteArray,
    val opsLimit: Long,
    val memLimitBytes: Long,
    val loginKey: ByteArray,
    val recoveryLoginKey: ByteArray,
    val publicKey: ByteArray,
    val masterKeyByKek: ByteArray,
    val masterKeyByRecovery: ByteArray,
    val recoveryKeyByMaster: ByteArray,
    val privateKeyByMaster: ByteArray,
    val homeCollection: HomeCollectionBytes,
)

class AccountSecrets(
    val userId: String,
    val masterKey: ByteArray,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val homeCollectionId: String,
    val homeCollectionKey: ByteArray,
)

class SignUpMaterial(val request: SignUpRequestBytes, val secrets: AccountSecrets, val recoveryWords: List<String>)

private fun parseCollectionMetadata(name: String, createdAtUtc: String): CollectionMetadataV1 {
    val schema = ContractSchemas.collectionMetadataV1
    return schema.decode(schema.encode(CollectionMetadataV1(name, createdAtUtc)))
}

fun createSignUpMaterial(sodium: Sodium, input: SignUpInput, startParams: (ByteArray) -> KdfParams = ::startKdfParams): SignUpMaterial {
    val salt = sodium.randomBytes(SALT_BYTES)
    val derived = deriveWithAdaptiveParams(sodium, input.password, salt, startParams)
    val params = derived.params
    val material = derived.material
    val masterKey = sodium.randomBytes(KEY_BYTES)
    val recoveryKey = sodium.randomBytes(KEY_BYTES)
    val homeCollectionKey = sodium.randomBytes(KEY_BYTES)
    val keypair = sodium.boxKeypair()
    val userId = input.randomId()
    val homeCollectionId = input.randomId()
    val metadata = parseCollectionMetadata(input.homeCollectionName, input.createdAtUtc)
    val recoveryLogin = recoveryLoginKey(sodium, recoveryKey)
    val masterKeyByKek = wrapKey(sodium, material.kek, masterKey, AdContext.MASTER_KEY_BY_KEK, userId)
    val masterKeyByRecovery = wrapKey(sodium, recoveryKey, masterKey, AdContext.MASTER_KEY_BY_RECOVERY, userId)
    val recoveryKeyByMaster = wrapKey(sodium, masterKey, recoveryKey, AdContext.RECOVERY_KEY_BY_MASTER, userId)
    val privateKeyByMaster = wrapKey(sodium, masterKey, keypair.privateKey, AdContext.PRIVATE_KEY_BY_MASTER, userId)
    val keyByOwner = wrapKey(sodium, masterKey, homeCollectionKey, AdContext.COLLECTION_KEY_BY_MASTER, homeCollectionId)
    val metadataBlob = sealPayload(sodium, homeCollectionKey, metadata, ContractSchemas.collectionMetadataV1, AdContext.COLLECTION_METADATA, homeCollectionId)
    val request = SignUpRequestBytes(
        userId = userId,
        username = input.username,
        salt = salt,
        opsLimit = params.opsLimit,
        memLimitBytes = params.memLimitBytes,
        loginKey = material.loginKey,
        recoveryLoginKey = recoveryLogin,
        publicKey = keypair.publicKey,
        masterKeyByKek = masterKeyByKek,
        masterKeyByRecovery = masterKeyByRecovery,
        recoveryKeyByMaster = recoveryKeyByMaster,
        privateKeyByMaster = privateKeyByMaster,
        homeCollection = HomeCollectionBytes(homeCollectionId, keyByOwner, metadataBlob),
    )
    val recoveryWords = encodeRecoveryKey(sodium, recoveryKey)
    wipe(material.kek)
    wipe(recoveryKey)
    return SignUpMaterial(
        request = request,
        secrets = AccountSecrets(userId, masterKey, keypair.privateKey, keypair.publicKey, homeCollectionId, homeCollectionKey),
        recoveryWords = recoveryWords,
    )
}
