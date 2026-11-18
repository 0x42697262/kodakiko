package dev.kodakiko.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DebugLogFormatTest {
    private enum class Phase { WRITING_FILES }

    @Test
    fun aLineHasTheStampThePaddedLevelAndServiceAndTheMessage() {
        assertEquals(
            "2026-11-18 04:13:00.000  INFO   lock       unlock_start method=password",
            DebugLogFormat.line("2026-11-18 04:13:00.000", LogLevel.INFO, LogServices.LOCK, "unlock_start method=password"),
        )
        assertEquals("s  ERROR  crash      x", DebugLogFormat.line("s", LogLevel.ERROR, LogServices.CRASH, "x"))
    }

    @Test
    fun controlCharactersBecomeSpacesAndTheMessageIsTrimmedAndCapped() {
        assertEquals("s  WARN   app        a b c", DebugLogFormat.line("s", LogLevel.WARN, LogServices.APP, "  a\nb\tc  "))
        val long = DebugLogFormat.line("s", LogLevel.DEBUG, LogServices.APP, "x".repeat(MAX_MESSAGE + 10))!!
        assertEquals("x".repeat(MAX_MESSAGE), long.substringAfterLast(" "))
    }

    @Test
    fun anEmptyMessageWritesNoLine() {
        assertNull(DebugLogFormat.line("s", LogLevel.INFO, LogServices.APP, ""))
        assertNull(DebugLogFormat.line("s", LogLevel.INFO, LogServices.APP, " \n "))
    }

    @Test
    fun theServiceIsLowercasedCleanedAndCapped() {
        assertEquals("s  INFO   my_svc     x", DebugLogFormat.line("s", LogLevel.INFO, " My Svc ", "x"))
        assertEquals("s  INFO   unknown    x", DebugLogFormat.line("s", LogLevel.INFO, "  ", "x"))
        assertEquals("s  INFO   ${"a".repeat(MAX_SERVICE)}  x", DebugLogFormat.line("s", LogLevel.INFO, "a".repeat(MAX_SERVICE + 5), "x"))
    }

    @Test
    fun anEventListsItsFieldsInOrderWithCamelCaseKeysInSnakeCase() {
        assertEquals(
            "unlock_end method=device duration_ms=420 ok=true",
            DebugLogFormat.event(LogEvents.UNLOCK_END, mapOf("method" to LogValues.METHOD_DEVICE, "durationMs" to 420L, "ok" to true)),
        )
        assertEquals("event", DebugLogFormat.event("  ", emptyMap()))
        assertEquals("sync_pass", DebugLogFormat.event("Sync Pass", emptyMap()))
    }

    @Test
    fun forbiddenFieldNamesAreDroppedWhateverTheirValue() {
        val fields = linkedMapOf<String, Any?>(
            "count" to 2,
            "fileName" to "IMG_1.jpg",
            "note" to "x",
            "caption" to "y",
            "password" to "z",
            "userName" to "alice",
            "path" to "/data",
            "location" to "here",
            "tags" to 3,
            "mediaUri" to "content:x",
            "Title" to "t",
            "phase" to "done",
        )
        assertEquals("export count=2 phase=done", DebugLogFormat.event("export", fields))
    }

    @Test
    fun forbiddenWordsAreMatchedAsWholeKeyParts() {
        assertTrue(DebugLogFormat.isForbidden("noteText"))
        assertTrue(DebugLogFormat.isForbidden("PASSWORD"))
        assertTrue(DebugLogFormat.isForbidden("recovery_key"))
        assertTrue(DebugLogFormat.isForbidden("file_count"))
        assertFalse(DebugLogFormat.isForbidden("count"))
        assertFalse(DebugLogFormat.isForbidden("mediaCount"))
        assertFalse(DebugLogFormat.isForbidden("notebook"))
        assertFalse(DebugLogFormat.isForbidden("profile"))
    }

    @Test
    fun valuesThatAreNotPlainTokensAreRedacted() {
        val fields = linkedMapOf<String, Any?>(
            "a" to "ok_value-1.2:3",
            "b" to "two words",
            "c" to "/storage/emulated/0",
            "d" to "x".repeat(MAX_VALUE),
            "e" to "x".repeat(MAX_VALUE + 1),
            "f" to "",
            "g" to "ünïcode",
        )
        assertEquals("e a=ok_value-1.2:3 b=redacted c=redacted d=${"x".repeat(MAX_VALUE)} e=redacted f=redacted g=redacted", DebugLogFormat.event("e", fields))
    }

    @Test
    fun numbersBooleansNullsAndEnumsAreWrittenAsThemselves() {
        val fields = linkedMapOf<String, Any?>(
            "n" to 7,
            "big" to 9_000_000_000L,
            "ratio" to 0.5,
            "nan" to Double.NaN,
            "inf" to Float.POSITIVE_INFINITY,
            "flag" to false,
            "missing" to null,
            "phase" to Phase.WRITING_FILES,
        )
        assertEquals(
            "e n=7 big=9000000000 ratio=0.5 nan=invalid_number inf=invalid_number flag=false missing=null phase=WRITING_FILES",
            DebugLogFormat.event("e", fields),
        )
    }

    @Test
    fun framesKeepClassMethodAndLineAndLoseAnythingElse() {
        assertEquals("dev.kodakiko.app.MainActivity.onCreate(MainActivity.kt:42)", DebugLogFormat.frame("dev.kodakiko.app.MainActivity.onCreate(MainActivity.kt:42)"))
        assertEquals("a.B$1.invoke(<init>)", DebugLogFormat.frame("a.B$1.invoke(<init>)"))
        assertEquals("x.y(Native_Method)_name_alice", DebugLogFormat.frame("x.y(Native Method) name=alice"))
        assertEquals(MAX_FRAME, DebugLogFormat.frame("a".repeat(MAX_FRAME + 40)).length)
        assertEquals("unknown", DebugLogFormat.frame(""))
    }

    @Test
    fun aFrameMessageIsAnAtEvent() {
        assertEquals("at=k.C.m(C.kt:1)", DebugLogFormat.frameMessage("k.C.m(C.kt:1)"))
    }

    private companion object {
        const val MAX_MESSAGE = 2_048
        const val MAX_SERVICE = 24
        const val MAX_VALUE = 64
        const val MAX_FRAME = 160
    }
}
