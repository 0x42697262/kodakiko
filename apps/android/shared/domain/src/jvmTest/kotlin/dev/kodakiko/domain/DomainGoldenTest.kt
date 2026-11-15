package dev.kodakiko.domain

import dev.kodakiko.testing.GoldenReplay
import kotlin.test.Test
import kotlin.test.assertEquals

class DomainGoldenTest {
    private val entryEditDomainFunctions = setOf("buildNewEntryMetadata", "applyEntryEdit", "MIN_OBJECT_BYTES")

    private val entryEditReplayedElsewhere = setOf("mergeMetadata", "metadataEquivalent", "attributesMatch")

    @Test
    fun entryTime() = GoldenReplay.run("domain/entry-time.json", DomainGoldenAdapter)

    @Test
    fun calendar() = GoldenReplay.run("domain/calendar.json", DomainGoldenAdapter)

    @Test
    fun tags() = GoldenReplay.run("domain/tags.json", DomainGoldenAdapter)

    @Test
    fun entryEdit() = GoldenReplay.run("domain/entry-edit.json", DomainGoldenAdapter, entryEditDomainFunctions)

    @Test
    fun entryEditCasesAreAllAccountedFor() {
        assertEquals(entryEditDomainFunctions + entryEditReplayedElsewhere, GoldenReplay.functions("domain/entry-edit.json"))
    }
}
