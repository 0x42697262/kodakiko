package dev.kodakiko.service.entries

import dev.kodakiko.account.AccountKeys
import dev.kodakiko.crypto.AdContext
import dev.kodakiko.crypto.KEY_BYTES
import dev.kodakiko.crypto.KdfContext
import dev.kodakiko.crypto.KdfSubkeyIds
import dev.kodakiko.crypto.buildSharePayload
import dev.kodakiko.crypto.wrapKey
import dev.kodakiko.data.db.CollectionKind
import dev.kodakiko.data.db.CollectionRole
import dev.kodakiko.data.db.MembershipRow
import dev.kodakiko.data.db.ObjectVariant
import dev.kodakiko.service.account.RowStorePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class EntryKeyCacheTest : EntriesTestBase() {
    private lateinit var fixture: AccountFixture

    @Volatile
    private var account: AccountKeys? = null

    @Before
    fun setUpFixture() {
        fixture = createAccountFixture()
        account = fixture.keys
    }

    private fun keysUnderTest(rowStore: RowStorePort = fixture.rowStore, accountKeys: () -> AccountKeys? = { account }): EntryKeyCache =
        EntryKeyCache(EntryKeysDeps(sodium = sodium, rowStore = rowStore, account = accountKeys, ioDispatcher = Dispatchers.IO))

    @Test
    fun resolvesTheHomeCollectionKeyForTheOwner() = runTest {
        val keys = keysUnderTest()
        assertArrayEquals(fixture.homeCollectionKey, keys.collectionKey(fixture.homeCollectionId))
    }

    @Test
    fun resolvesEntryMediaAndVariantKeysThroughTheMembership() = runTest {
        val local = createLocalEntry(fixture, listOf(byteArrayOf(1, 2, 3)))
        val keys = keysUnderTest()
        assertArrayEquals(local.entryKey, keys.entryKey(local.entryId))
        val mediaId = local.mediaIds[0]
        val mediaKey = keys.mediaKey(local.entryId, mediaId)
        assertEquals(KEY_BYTES, mediaKey.size)
        val original = keys.variantKey(local.entryId, mediaId, ObjectVariant.ORIGINAL)
        val thumbnail = keys.variantKey(local.entryId, mediaId, ObjectVariant.THUMBNAIL)
        assertArrayEquals(sodium.kdfDerive(KEY_BYTES, KdfSubkeyIds.MEDIA_ORIGINAL, KdfContext.MEDIA.value, mediaKey), original)
        assertArrayEquals(sodium.kdfDerive(KEY_BYTES, KdfSubkeyIds.MEDIA_THUMBNAIL, KdfContext.MEDIA.value, mediaKey), thumbnail)
    }

    @Test
    fun doesNotCacheVariantKeysSoWipingOneDoesNotBreakTheNextCall() = runTest {
        val local = createLocalEntry(fixture, listOf(byteArrayOf(1)))
        val keys = keysUnderTest()
        val mediaId = local.mediaIds[0]
        val first = keys.variantKey(local.entryId, mediaId, ObjectVariant.ORIGINAL)
        first.fill(0)
        val second = keys.variantKey(local.entryId, mediaId, ObjectVariant.ORIGINAL)
        assertFalse(isWiped(second))
    }

    @Test
    fun cachesCollectionEntryAndMediaKeys() = runTest {
        val local = createLocalEntry(fixture, listOf(byteArrayOf(1)))
        val keys = keysUnderTest()
        val mediaId = local.mediaIds[0]
        assertSame(keys.entryKey(local.entryId), keys.entryKey(local.entryId))
        assertSame(keys.mediaKey(local.entryId, mediaId), keys.mediaKey(local.entryId, mediaId))
        assertSame(keys.collectionKey(fixture.homeCollectionId), keys.collectionKey(fixture.homeCollectionId))
    }

    @Test
    fun throwsAccountLockedExceptionWhileTheAccountIsLocked() = runTest {
        account = null
        val keys = keysUnderTest()
        expectFailure<AccountLockedException> { keys.collectionKey(fixture.homeCollectionId) }
        expectFailure<AccountLockedException> { keys.entryKey(fixture.randomId()) }
    }

    @Test
    fun wipesEveryCachedKeyOnClearAndReDerivesAfterwards() = runTest {
        val local = createLocalEntry(fixture, listOf(byteArrayOf(1)))
        val keys = keysUnderTest()
        val mediaId = local.mediaIds[0]
        val collectionKey = keys.collectionKey(fixture.homeCollectionId)
        val entryKey = keys.entryKey(local.entryId)
        val mediaKey = keys.mediaKey(local.entryId, mediaId)
        keys.clear()
        for (key in listOf(collectionKey, entryKey, mediaKey)) assertTrue(isWiped(key))
        assertArrayEquals(local.entryKey, keys.entryKey(local.entryId))
    }

    @Test
    fun forgetWithAMediaIdWipesAndDropsOnlyThatMediaKey() = runTest {
        val local = createLocalEntry(fixture, listOf(byteArrayOf(1), byteArrayOf(2)))
        val keys = keysUnderTest()
        val (first, second) = local.mediaIds
        val entryKey = keys.entryKey(local.entryId)
        val firstKey = keys.mediaKey(local.entryId, first)
        val secondKey = keys.mediaKey(local.entryId, second)
        val entryCopy = entryKey.copyOf()
        keys.forget(local.entryId, first)
        assertTrue(isWiped(firstKey))
        assertFalse(isWiped(secondKey))
        assertArrayEquals(entryCopy, entryKey)
        assertNotSame(firstKey, keys.mediaKey(local.entryId, first))
    }

    @Test
    fun forgetWithoutAMediaIdWipesAndDropsTheEntryKeyAndAllItsMediaKeys() = runTest {
        val local = createLocalEntry(fixture, listOf(byteArrayOf(1)))
        val other = createLocalEntry(fixture, listOf(byteArrayOf(2)))
        val keys = keysUnderTest()
        val entryKey = keys.entryKey(local.entryId)
        val mediaKey = keys.mediaKey(local.entryId, local.mediaIds[0])
        val otherKey = keys.entryKey(other.entryId)
        keys.forget(local.entryId)
        assertTrue(isWiped(entryKey))
        assertTrue(isWiped(mediaKey))
        assertFalse(isWiped(otherKey))
        val again = keys.entryKey(local.entryId)
        assertNotSame(entryKey, again)
        assertArrayEquals(local.entryKey, again)
    }

    @Test
    fun throwsWhenTheEntryHasNoUsableMembership() = runTest {
        val keys = keysUnderTest()
        expectFailure<EntryKeyUnavailableException> { keys.entryKey(fixture.randomId()) }
    }

    @Test
    fun throwsWhenTheCollectionRowIsUnknown() = runTest {
        val keys = keysUnderTest()
        expectFailure<EntryKeyUnavailableException> { keys.collectionKey(fixture.randomId()) }
    }

    @Test
    fun opensASharedCollectionKeyFromTheSealedSharePayloadForAViewer() = runTest {
        val sharedId = fixture.randomId()
        val sharedKey = sodium.randomBytes(KEY_BYTES)
        val sealed = sodium.boxSeal(buildSharePayload(sharedId, sharedKey), fixture.keys.publicKey)
        fixture.rowStore.transaction { tx ->
            tx.collections.put(collectionRow(sharedId, CollectionKind.SHARED, "other", CollectionRole.VIEWER, sealed))
        }
        val keys = keysUnderTest()
        assertArrayEquals(sharedKey, keys.collectionKey(sharedId))
    }

    @Test
    fun derivesALinkCollectionKeyFromTheUnwrappedLinkSecret() = runTest {
        val linkId = fixture.randomId()
        val linkSecret = sodium.randomBytes(KEY_BYTES)
        val keyMaterial = wrapKey(sodium, fixture.keys.masterKey, linkSecret, AdContext.LINK_SECRET_BY_MASTER, linkId)
        fixture.rowStore.transaction { tx ->
            tx.collections.put(collectionRow(linkId, CollectionKind.LINK, FIXTURE_USER_ID, CollectionRole.OWNER, keyMaterial))
        }
        val keys = keysUnderTest()
        assertArrayEquals(
            sodium.kdfDerive(KEY_BYTES, KdfSubkeyIds.LINK_COLLECTION_KEY, KdfContext.LINK.value, linkSecret),
            keys.collectionKey(linkId),
        )
    }

    @Test
    fun prefersTheHomeMembershipAndSkipsCollectionsWhoseKeyCannotBeResolved() = runTest {
        val local = createLocalEntry(fixture, listOf(byteArrayOf(1)))
        val brokenId = fixture.randomId()
        fixture.rowStore.transaction { tx ->
            tx.collections.put(collectionRow(brokenId, CollectionKind.SHARED, "other", CollectionRole.VIEWER, byteArrayOf(9, 9, 9)))
            tx.memberships.put(MembershipRow(brokenId, local.entryId, byteArrayOf(1, 2, 3), 1))
        }
        val keys = keysUnderTest()
        assertArrayEquals(local.entryKey, keys.entryKey(local.entryId))
    }

    @Test
    fun throwsAccountLockedExceptionForACachedMediaKeyOnceTheAccountIsLocked() = runTest {
        val local = createLocalEntry(fixture, listOf(byteArrayOf(1)))
        val keys = keysUnderTest()
        val mediaId = local.mediaIds[0]
        keys.mediaKey(local.entryId, mediaId)
        account = null
        expectFailure<AccountLockedException> { keys.mediaKey(local.entryId, mediaId) }
    }

    @Test
    fun wipesAndRejectsAResolutionThatFinishesAfterClear() = runTest {
        val local = createLocalEntry(fixture, listOf(byteArrayOf(1)))
        val gated = GatedRowStore(fixture.rowStore, holdFirstOnly = false)
        val keys = keysUnderTest(gated) { fixture.keys }
        val pending = async(Dispatchers.IO) { failureOf { keys.collectionKey(fixture.homeCollectionId) } }
        assertTrue(gated.entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        keys.clear()
        gated.release()
        assertTrue(pending.await() is AccountLockedException)
        assertEquals(KEY_BYTES, keys.entryKey(local.entryId).size)
    }

    @Test
    fun doesNotCacheAnEntryKeyThatWasResolvedInAnOlderGeneration() = runTest {
        val local = createLocalEntry(fixture, listOf(byteArrayOf(1)))
        val gated = GatedRowStore(fixture.rowStore, holdFirstOnly = true)
        val keys = keysUnderTest(gated) { fixture.keys }
        val pending = async(Dispatchers.IO) { failureOf { keys.entryKey(local.entryId) } }
        assertTrue(gated.entered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        keys.clear()
        gated.release()
        assertTrue(pending.await() is AccountLockedException)
        val again = keys.entryKey(local.entryId)
        assertArrayEquals(local.entryKey, again)
        assertFalse(isWiped(again))
    }
}
