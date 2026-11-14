package dev.kodakiko.tooling

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import java.io.File

data class CatalogEntry(val kind: String, val coordinate: String, val version: String)

object Catalog {
    private val section = Regex("""^\[(\w+)]\s*$""")
    private val assignment = Regex("""^([\w.-]+)\s*=\s*(.+)$""")

    private fun field(table: String, name: String): String? =
        Regex("""(?:^|[{,\s])${Regex.escape(name)}\s*=\s*"([^"]*)"""").find(table)?.groupValues?.get(1)

    fun parse(text: String): List<CatalogEntry> {
        val versions = mutableMapOf<String, String>()
        val raw = mutableListOf<Pair<String, String>>()
        var current = ""
        for (line in text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            section.find(line)?.let { current = it.groupValues[1]; return@let }
            if (section.matches(line)) continue
            val match = assignment.find(line) ?: continue
            val value = match.groupValues[2]
            when (current) {
                "versions" -> versions[match.groupValues[1]] = value.trim().removeSurrounding("\"")
                "libraries", "plugins" -> raw += current to value
            }
        }
        return raw.map { (kind, value) ->
            val resolvedVersion = { table: String ->
                field(table, "version.ref")?.let { versions[it] ?: error("missing version ref $it") }
                    ?: field(table, "version")
                    ?: "bom"
            }
            if (kind == "plugins") {
                if (value.startsWith("\"")) {
                    val parts = value.removeSurrounding("\"").split(":")
                    CatalogEntry("plugin", parts[0], parts.getOrElse(1) { "bom" })
                } else {
                    CatalogEntry("plugin", field(value, "id") ?: error("plugin without id: $value"), resolvedVersion(value))
                }
            } else {
                if (value.startsWith("\"")) {
                    val parts = value.removeSurrounding("\"").split(":")
                    CatalogEntry("lib", "${parts[0]}:${parts[1]}", parts.getOrElse(2) { "bom" })
                } else {
                    val module = field(value, "module") ?: "${field(value, "group")}:${field(value, "name")}"
                    CatalogEntry("lib", module, resolvedVersion(value))
                }
            }
        }
    }
}

object Allowlist {
    fun parse(dependenciesDoc: String): Set<CatalogEntry> {
        val start = dependenciesDoc.indexOf("### Catalog coordinates (machine-checked)")
        check(start >= 0) { "allowlist block missing in docs/dependencies.md" }
        val block = dependenciesDoc.substring(start).substringAfter("```\n").substringBefore("```")
        return block.lines().filter { it.isNotBlank() }.map { line ->
            val (kind, coordinate, version) = line.trim().split(Regex("\\s+"))
            CatalogEntry(kind, coordinate, version)
        }.toSet()
    }
}

class CatalogAllowlistTest {
    private val catalogFile = File(Repo.android, "gradle/libs.versions.toml")

    @Test
    fun everyCatalogEntryIsAllowlistedWithTheSameVersion() {
        val allowed = Allowlist.parse(File(Repo.root, "docs/dependencies.md").readText())
        val entries = Catalog.parse(catalogFile.readText())
        entries.shouldNotBeEmpty()
        entries.filterNot { it in allowed }.map { "${it.kind} ${it.coordinate} ${it.version}" }.shouldBeEmpty()
    }

    @Test
    fun parserUnderstandsAllCatalogForms() {
        val sample = """
            [versions]
            kotlin = "2.4.21"
            [libraries]
            a = { module = "g:a", version.ref = "kotlin" }
            b = { group = "g", name = "b", version = "1.0" }
            c = { module = "androidx.compose.ui:ui" }
            d = "g:d:3.0"
            [plugins]
            p = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
            [bundles]
            x = ["a", "b"]
        """.trimIndent()
        val parsed = Catalog.parse(sample)
        check(
            parsed == listOf(
                CatalogEntry("lib", "g:a", "2.4.21"),
                CatalogEntry("lib", "g:b", "1.0"),
                CatalogEntry("lib", "androidx.compose.ui:ui", "bom"),
                CatalogEntry("lib", "g:d", "3.0"),
                CatalogEntry("plugin", "org.jetbrains.kotlin.jvm", "2.4.21"),
            ),
        ) { parsed.toString() }
    }
}
