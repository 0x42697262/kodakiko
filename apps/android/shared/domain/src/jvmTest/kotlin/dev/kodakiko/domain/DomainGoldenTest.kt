package dev.kodakiko.domain

import dev.kodakiko.testing.GoldenReplay
import kotlin.test.Test

class DomainGoldenTest {
    @Test
    fun entryTime() = GoldenReplay.run("domain/entry-time.json", DomainGoldenAdapter)

    @Test
    fun calendar() = GoldenReplay.run("domain/calendar.json", DomainGoldenAdapter)

    @Test
    fun tags() = GoldenReplay.run("domain/tags.json", DomainGoldenAdapter)

    @Test
    fun entryEdit() = GoldenReplay.run("domain/entry-edit.json", DomainGoldenAdapter)
}
