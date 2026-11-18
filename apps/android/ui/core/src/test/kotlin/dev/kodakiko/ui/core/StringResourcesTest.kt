package dev.kodakiko.ui.core

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
class StringResourcesTest {
    private val resources: Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private val specs = UiFixtures.stringResources

    private val britishSpellings = Regex(
        "\\b(" + listOf(
            "colours?", "favourit\\w*", "behaviour\\w*", "organis\\w*", "recognis\\w*", "licence\\w*", "cancell(ed|ing)",
            "centre\\w*", "metre\\w*", "grey\\w*", "analys(e|ed|es|ing)", "catalogue\\w*", "travell(ed|ing)", "labell(ed|ing)",
            "modell(ed|ing)", "synchronis\\w*", "customis\\w*", "authoris\\w*", "optimis\\w*", "minimis\\w*", "maximis\\w*",
            "realis(e|ed|es|ing)", "apologis\\w*", "prioritis\\w*", "utilis\\w*", "summaris\\w*", "honour\\w*", "neighbour\\w*",
            "programme\\w*", "judgement\\w*", "enrols?", "fulfil(s)?", "whilst", "amongst", "artefact\\w*",
            "defence\\w*", "offence\\w*", "initialis\\w*", "finalis\\w*", "serialis\\w*", "normalis\\w*", "categoris\\w*",
            "memoris\\w*", "visualis\\w*", "personalis\\w*", "randomis\\w*", "sanitis\\w*", "learnt", "spelt",
        ).joinToString("|") + ")\\b",
        RegexOption.IGNORE_CASE,
    )

    private data class XmlResource(val directory: String, val type: String, val name: String, val texts: List<String>)

    private fun xmlResources(): List<XmlResource> {
        val directories = UiFixtures.resDirectory.listFiles { file -> file.isDirectory && file.name.startsWith("values") }.orEmpty()
        return directories.sortedBy { it.name }.flatMap { directory ->
            directory.listFiles { file: File -> file.name.endsWith(".xml") }.orEmpty().sortedBy { it.name }.flatMap { file ->
                val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
                val children = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<Element>()
                children.filter { it.tagName == "string" || it.tagName == "plurals" }.map { element ->
                    val items = element.getElementsByTagName("item")
                    val texts = if (element.tagName == "plurals") (0 until items.length).map { items.item(it).textContent } else listOf(element.textContent)
                    XmlResource(directory.name, element.tagName, element.getAttribute("name"), texts)
                }
            }
        }
    }

    @Test
    fun theFixtureHoldsEveryKeyUsedByAKeptV031Flow() {
        assertEquals(886, UiFixtures.strings.getValue("keptKeyCount").jsonPrimitive.int)
        assertEquals(886, specs.map { it.key }.distinct().size)
        assertEquals(890, UiFixtures.strings.getValue("resourceCount").jsonPrimitive.int)
        assertEquals(890, specs.size)
        assertEquals(890, specs.map { it.name }.distinct().size)
        assertTrue(specs.all { spec -> spec.name.matches(Regex("[a-z][a-z0-9_]*")) })
        assertEquals(setOf("string", "plurals"), specs.map { it.type }.toSet())
    }

    @Test
    fun everyKeptKeyHasAResourceOfTheRightKind() {
        val missing = specs.filter { UiFixtures.resourceId(it.type, it.name) == 0 }.map { "${it.type}/${it.name} (${it.key})" }
        assertTrue("${missing.size} missing, first: ${missing.take(25)}", missing.isEmpty())
    }

    @Test
    fun everyResourceRendersTheV031EnglishText() {
        val wrong = specs.filter { UiFixtures.resourceId(it.type, it.name) != 0 }.flatMap { spec ->
            spec.cases.mapNotNull { case ->
                val actual = runCatching { UiFixtures.render(resources, spec, case) }.getOrElse { "<${it::class.simpleName}: ${it.message}>" }
                if (actual == case.text) null else "${spec.name} ${case.quantity ?: ""} ${case.args}: expected <${case.text}> but was <$actual>"
            }
        }
        assertTrue("${wrong.size} wrong, first: ${wrong.take(25).joinToString("\n")}", wrong.isEmpty())
    }

    @Test
    fun pluralResourcesAreRealPluralsAndPlainTextsAreNot() {
        val plurals = specs.filter { it.type == "plurals" }.map { it.name }.toSet()
        assertEquals(21, plurals.size)
        val declared = xmlResources()
        val declaredPlurals = declared.filter { it.type == "plurals" }.map { it.name }.toSet()
        val declaredStrings = declared.filter { it.type == "string" }.map { it.name }.toSet()
        assertTrue("must be <plurals>: ${plurals - declaredPlurals}", (plurals - declaredPlurals).isEmpty())
        val strings = specs.filter { it.type == "string" }.map { it.name }.toSet()
        assertTrue("must be <string>: ${strings - declaredStrings}", (strings - declaredStrings).isEmpty())
    }

    @Test
    fun resourceNamesAreUniqueAndEnglishOnly() {
        val declared = xmlResources()
        val duplicates = declared.groupBy { it.name }.filterValues { it.size > 1 }.keys
        assertTrue("duplicate names: $duplicates", duplicates.isEmpty())
        val translated = declared.filter { it.directory != "values" }.map { "${it.directory}/${it.name}" }
        assertTrue("strings outside res/values: $translated", translated.isEmpty())
    }

    @Test
    fun everyTextUsesAmericanSpelling() {
        val british = xmlResources().flatMap { resource ->
            resource.texts.flatMap { text -> britishSpellings.findAll(text).map { "${resource.name}: ${it.value}" }.toList() }
        }
        assertTrue("British spelling: $british", british.isEmpty())
        val expected = specs.flatMap { spec -> spec.cases.flatMap { case -> britishSpellings.findAll(case.text).map { "${spec.name}: ${it.value}" }.toList() } }
        assertTrue("fixture spelling: $expected", expected.isEmpty())
        val canceled = specs.single { it.key == "settings.exportCancelled" }
        assertEquals("Export canceled. Finished files were kept.", canceled.cases.single().text)
    }
}
