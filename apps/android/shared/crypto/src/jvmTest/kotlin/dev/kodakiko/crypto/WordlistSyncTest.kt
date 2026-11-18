package dev.kodakiko.crypto

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class WordlistSyncTest {
    @Test
    fun kotlinWordlistEqualsTheTypeScriptWordlist() {
        val source = File(TestSupport.repoRoot, "packages/core/src/crypto/wordlist-en.ts").readText()
        val words = Regex("""^\s*'([a-z]+)',?$""", RegexOption.MULTILINE).findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(RECOVERY_WORDLIST_SIZE, words.size)
        assertEquals(words, WORDLIST_EN)
    }
}
