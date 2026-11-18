package dev.kodakiko.contract

const val API_PREFIX = "/api/v1"

object RoutePatterns {
    const val CONFIG = "$API_PREFIX/config"
    const val HEALTH_LIVE = "/health/live"
    const val HEALTH_READY = "/health/ready"
    const val AUTH_PARAMS = "$API_PREFIX/auth/params"
    const val AUTH_SIGN_UP = "$API_PREFIX/auth/sign-up"
    const val AUTH_SIGN_IN = "$API_PREFIX/auth/sign-in"
    const val AUTH_KEY_BUNDLE = "$API_PREFIX/auth/key-bundle"
    const val AUTH_RECOVERY_START = "$API_PREFIX/auth/recovery/start"
    const val AUTH_RECOVERY_COMPLETE = "$API_PREFIX/auth/recovery/complete"
    const val AUTH_PASSWORD = "$API_PREFIX/auth/password"
    const val AUTH_SIGN_OUT = "$API_PREFIX/auth/sign-out"
    const val AUTH_SESSION = "$API_PREFIX/auth/session"
    const val USERS_ME = "$API_PREFIX/users/me"
    const val USERS_ME_DELETE = "$API_PREFIX/users/me/delete"
    const val USERS_ME_DELETE_CANCEL = "$API_PREFIX/users/me/delete/cancel"
    const val OBJECTS_PRESIGN = "$API_PREFIX/objects/presign"
    const val OBJECT_PARTS = "$API_PREFIX/objects/:id/parts"
    const val OBJECT_CONFIRM = "$API_PREFIX/objects/:id/confirm"
    const val OBJECT_DOWNLOAD = "$API_PREFIX/objects/:id/download"
    const val COLLECTIONS = "$API_PREFIX/collections"
    const val COLLECTION = "$API_PREFIX/collections/:id"
    const val COLLECTION_DIFF = "$API_PREFIX/collections/:id/diff"
    const val COLLECTION_ENTRIES = "$API_PREFIX/collections/:id/entries"
    const val COLLECTION_ENTRY = "$API_PREFIX/collections/:id/entries/:entryId"
    const val COLLECTION_SHARES = "$API_PREFIX/collections/:id/shares"
    const val COLLECTION_SHARE = "$API_PREFIX/collections/:id/shares/:recipientUserId"
    const val USER_PUBLIC_KEY = "$API_PREFIX/users/:username/public-key"
    const val ADMIN_USERS = "$API_PREFIX/admin/users"
    const val ADMIN_USER = "$API_PREFIX/admin/users/:id"
    const val ADMIN_INVITES = "$API_PREFIX/admin/invites"
    const val ADMIN_INVITE = "$API_PREFIX/admin/invites/:id"
    const val ADMIN_STATS = "$API_PREFIX/admin/stats"
    const val PUBLIC_LINKS = "$API_PREFIX/public-links"
    const val PUBLIC_LINK = "$API_PREFIX/public-links/:linkId"
    const val PUBLIC_LINK_OPEN = "$API_PREFIX/public-links/:linkId/open"
    const val PUBLIC_LINK_ENTRIES = "$API_PREFIX/public-links/:linkId/entries"
    const val PUBLIC_LINK_OBJECT_CONTENT = "$API_PREFIX/public-links/:linkId/objects/:objectId/content"
    const val SYNC_STATE = "$API_PREFIX/sync/state"
    const val ENTRIES = "$API_PREFIX/entries"
    const val ENTRY = "$API_PREFIX/entries/:id"
    const val ENTRY_TRASH = "$API_PREFIX/entries/:id/trash"
    const val ENTRY_RESTORE = "$API_PREFIX/entries/:id/restore"
    const val ENTRY_MEDIA = "$API_PREFIX/entries/:id/media"
    const val MEDIA = "$API_PREFIX/media/:id"

    val all: Map<String, String> = mapOf(
        "config" to CONFIG,
        "healthLive" to HEALTH_LIVE,
        "healthReady" to HEALTH_READY,
        "authParams" to AUTH_PARAMS,
        "authSignUp" to AUTH_SIGN_UP,
        "authSignIn" to AUTH_SIGN_IN,
        "authKeyBundle" to AUTH_KEY_BUNDLE,
        "authRecoveryStart" to AUTH_RECOVERY_START,
        "authRecoveryComplete" to AUTH_RECOVERY_COMPLETE,
        "authPassword" to AUTH_PASSWORD,
        "authSignOut" to AUTH_SIGN_OUT,
        "authSession" to AUTH_SESSION,
        "usersMe" to USERS_ME,
        "usersMeDelete" to USERS_ME_DELETE,
        "usersMeDeleteCancel" to USERS_ME_DELETE_CANCEL,
        "objectsPresign" to OBJECTS_PRESIGN,
        "objectParts" to OBJECT_PARTS,
        "objectConfirm" to OBJECT_CONFIRM,
        "objectDownload" to OBJECT_DOWNLOAD,
        "collections" to COLLECTIONS,
        "collection" to COLLECTION,
        "collectionDiff" to COLLECTION_DIFF,
        "collectionEntries" to COLLECTION_ENTRIES,
        "collectionEntry" to COLLECTION_ENTRY,
        "collectionShares" to COLLECTION_SHARES,
        "collectionShare" to COLLECTION_SHARE,
        "userPublicKey" to USER_PUBLIC_KEY,
        "adminUsers" to ADMIN_USERS,
        "adminUser" to ADMIN_USER,
        "adminInvites" to ADMIN_INVITES,
        "adminInvite" to ADMIN_INVITE,
        "adminStats" to ADMIN_STATS,
        "publicLinks" to PUBLIC_LINKS,
        "publicLink" to PUBLIC_LINK,
        "publicLinkOpen" to PUBLIC_LINK_OPEN,
        "publicLinkEntries" to PUBLIC_LINK_ENTRIES,
        "publicLinkObjectContent" to PUBLIC_LINK_OBJECT_CONTENT,
        "syncState" to SYNC_STATE,
        "entries" to ENTRIES,
        "entry" to ENTRY,
        "entryTrash" to ENTRY_TRASH,
        "entryRestore" to ENTRY_RESTORE,
        "entryMedia" to ENTRY_MEDIA,
        "media" to MEDIA,
    )
}

object Routes {
    fun config(): String = "$API_PREFIX/config"

    fun healthLive(): String = "/health/live"

    fun healthReady(): String = "/health/ready"

    fun authParams(): String = "$API_PREFIX/auth/params"

    fun authSignUp(): String = "$API_PREFIX/auth/sign-up"

    fun authSignIn(): String = "$API_PREFIX/auth/sign-in"

    fun authKeyBundle(): String = "$API_PREFIX/auth/key-bundle"

    fun authRecoveryStart(): String = "$API_PREFIX/auth/recovery/start"

    fun authRecoveryComplete(): String = "$API_PREFIX/auth/recovery/complete"

    fun authPassword(): String = "$API_PREFIX/auth/password"

    fun authSignOut(): String = "$API_PREFIX/auth/sign-out"

    fun authSession(): String = "$API_PREFIX/auth/session"

    fun usersMe(): String = "$API_PREFIX/users/me"

    fun usersMeDelete(): String = "$API_PREFIX/users/me/delete"

    fun usersMeDeleteCancel(): String = "$API_PREFIX/users/me/delete/cancel"

    fun objectsPresign(): String = "$API_PREFIX/objects/presign"

    fun objectParts(id: String): String = "$API_PREFIX/objects/$id/parts"

    fun objectConfirm(id: String): String = "$API_PREFIX/objects/$id/confirm"

    fun objectDownload(id: String): String = "$API_PREFIX/objects/$id/download"

    fun collections(): String = "$API_PREFIX/collections"

    fun collection(id: String): String = "$API_PREFIX/collections/$id"

    fun collectionDiff(id: String): String = "$API_PREFIX/collections/$id/diff"

    fun collectionEntries(id: String): String = "$API_PREFIX/collections/$id/entries"

    fun collectionEntry(id: String, entryId: String): String = "$API_PREFIX/collections/$id/entries/$entryId"

    fun collectionShares(id: String): String = "$API_PREFIX/collections/$id/shares"

    fun collectionShare(id: String, recipientUserId: String): String = "$API_PREFIX/collections/$id/shares/$recipientUserId"

    fun userPublicKey(username: String): String = "$API_PREFIX/users/$username/public-key"

    fun adminUsers(): String = "$API_PREFIX/admin/users"

    fun adminUser(id: String): String = "$API_PREFIX/admin/users/$id"

    fun adminInvites(): String = "$API_PREFIX/admin/invites"

    fun adminInvite(id: String): String = "$API_PREFIX/admin/invites/$id"

    fun adminStats(): String = "$API_PREFIX/admin/stats"

    fun publicLinks(): String = "$API_PREFIX/public-links"

    fun publicLink(linkId: String): String = "$API_PREFIX/public-links/$linkId"

    fun publicLinkOpen(linkId: String): String = "$API_PREFIX/public-links/$linkId/open"

    fun publicLinkEntries(linkId: String): String = "$API_PREFIX/public-links/$linkId/entries"

    fun publicLinkObjectContent(linkId: String, objectId: String): String = "$API_PREFIX/public-links/$linkId/objects/$objectId/content"

    fun syncState(): String = "$API_PREFIX/sync/state"

    fun entries(): String = "$API_PREFIX/entries"

    fun entry(id: String): String = "$API_PREFIX/entries/$id"

    fun entryTrash(id: String): String = "$API_PREFIX/entries/$id/trash"

    fun entryRestore(id: String): String = "$API_PREFIX/entries/$id/restore"

    fun entryMedia(id: String): String = "$API_PREFIX/entries/$id/media"

    fun media(id: String): String = "$API_PREFIX/media/$id"
}
