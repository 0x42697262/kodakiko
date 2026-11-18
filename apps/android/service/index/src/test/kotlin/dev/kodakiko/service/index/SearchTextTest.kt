package dev.kodakiko.service.index

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTextTest {
    @Test
    fun joinsTheNoteAndEveryCaptionSoSearchFindsCaptions() {
        val media = listOf(descriptorOf("m1", 1, caption = "Sunset"), descriptorOf("m2", 1), descriptorOf("m3", 1, caption = "Swim"))
        assertEquals("Lake day\nSunset\nSwim", searchTextOf(metadataOf("Lake day", emptyList(), media)))
    }

    @Test
    fun isTheNoteAloneWithoutCaptions() {
        assertEquals("Only note", searchTextOf(metadataOf("Only note", emptyList(), listOf(descriptorOf("m1", 1)))))
    }
}
