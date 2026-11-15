package dev.kodakiko.data.db

object StorageKeys {
    const val ACCOUNT_USER_ID = "account.userId"
    const val ACCOUNT_USERNAME = "account.username"
    const val ACCOUNT_KDF_PARAMS = "account.kdfParams"
    const val ACCOUNT_MASTER_KEY_BY_KEK = "account.masterKeyByKek"
    const val ACCOUNT_MASTER_KEY_BY_RECOVERY = "account.masterKeyByRecovery"
    const val ACCOUNT_MODE = "account.mode"
    const val ACCOUNT_RECOVERY_KEY_BY_MASTER = "account.recoveryKeyByMaster"
    const val ACCOUNT_PUBLIC_KEY = "account.publicKey"
    const val ACCOUNT_PRIVATE_KEY_BY_MASTER = "account.privateKeyByMaster"
    const val ACCOUNT_RECOVERY_CONFIRMED = "account.recoveryConfirmed"
    const val ACCOUNT_HOME_COLLECTION_ID = "account.homeCollectionId"
    const val ACCOUNT_ROLE = "account.role"
    const val ACCOUNT_DELETION_DUE_AT = "account.deletionDueAt"
    const val DEVICE_STORAGE_PERSISTED = "device.storagePersisted"
    const val DEVICE_NOTIFICATION_PERMISSION_ASKED = "device.notificationPermissionAsked"
    const val SETTINGS_DEVICE = "settings.device"
    const val DIAGNOSTICS_PROBE = "diagnostics.probe"

    val ALL: List<String> = listOf(
        ACCOUNT_USER_ID,
        ACCOUNT_USERNAME,
        ACCOUNT_KDF_PARAMS,
        ACCOUNT_MASTER_KEY_BY_KEK,
        ACCOUNT_MASTER_KEY_BY_RECOVERY,
        ACCOUNT_MODE,
        ACCOUNT_RECOVERY_KEY_BY_MASTER,
        ACCOUNT_PUBLIC_KEY,
        ACCOUNT_PRIVATE_KEY_BY_MASTER,
        ACCOUNT_RECOVERY_CONFIRMED,
        ACCOUNT_HOME_COLLECTION_ID,
        ACCOUNT_ROLE,
        ACCOUNT_DELETION_DUE_AT,
        DEVICE_STORAGE_PERSISTED,
        DEVICE_NOTIFICATION_PERMISSION_ASKED,
        SETTINGS_DEVICE,
        DIAGNOSTICS_PROBE,
    )
}
