package dev.kodakiko.ui.feature.tags

import dev.kodakiko.index.TagCount
import dev.kodakiko.index.orderTags
import org.junit.Assert.assertEquals
import org.junit.Test

class TagOrderTest {
    private val counted = listOf(TagCount("beach", 3), TagCount("city", 1), TagCount("zoo", 2))

    @Test
    fun putsOrderedTagsFirstKeepsEmptyOrderedTagsWithAZeroCountAndAppendsTheRestAlphabetically() {
        assertEquals(
            listOf(TagCount("zoo", 2), TagCount("later", 0), TagCount("beach", 3), TagCount("city", 1)),
            orderTags(counted, listOf("zoo", "later", "beach")),
        )
    }

    @Test
    fun movesATagUpOrDownWithinTheFullOrderAndIgnoresMovesPastTheEnds() {
        val order = listOf("zoo", "beach", "city")
        assertEquals(listOf("beach", "zoo", "city"), moveTag(order, "beach", -1))
        assertEquals(listOf("zoo", "city", "beach"), moveTag(order, "beach", 1))
        assertEquals(order, moveTag(order, "zoo", -1))
    }

    @Test
    fun aMoveByMoreThanOneStepAndUnknownTagsKeepTheOrder() {
        val order = listOf("zoo", "beach", "city")
        assertEquals(listOf("beach", "city", "zoo"), moveTag(order, "zoo", 2))
        assertEquals(order, moveTag(order, "zoo", 3))
        assertEquals(order, moveTag(order, "missing", 1))
        assertEquals(order, moveTag(order, "city", 0))
    }

    @Test
    fun theInputListIsNotChanged() {
        val order = mutableListOf("zoo", "beach")
        val moved = moveTag(order, "beach", -1)
        assertEquals(listOf("zoo", "beach"), order)
        assertEquals(listOf("beach", "zoo"), moved)
    }
}
