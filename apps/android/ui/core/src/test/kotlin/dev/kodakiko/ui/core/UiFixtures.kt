package dev.kodakiko.ui.core

import android.content.res.Resources
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class StringCase(val quantity: Int?, val args: List<Any>, val text: String)

data class StringResourceSpec(val key: String, val name: String, val type: String, val variant: String?, val cases: List<StringCase>)

object UiFixtures {
    val repoRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "AGENTS.md").isFile && File(it, "apps/android").isDirectory }
            ?: error("repository root not found from ${File("").absolutePath}")
    }

    val fixtureDirectory: File get() = File(repoRoot, "tests/fixtures/ui-strings")

    val moduleDirectory: File get() = File(repoRoot, "apps/android/ui/core")

    val resDirectory: File get() = File(moduleDirectory, "src/main/res")

    private fun json(name: String): JsonObject = Json.parseToJsonElement(File(fixtureDirectory, name).readText()).jsonObject

    val strings: JsonObject by lazy { json("strings.json") }

    val testTags: JsonObject by lazy { json("test-tags.json") }

    private fun argument(value: JsonPrimitive): Any = value.intOrNull ?: value.content

    val stringResources: List<StringResourceSpec> by lazy {
        strings.getValue("resources").jsonArray.map { element ->
            val resource = element.jsonObject
            StringResourceSpec(
                key = resource.getValue("key").jsonPrimitive.content,
                name = resource.getValue("name").jsonPrimitive.content,
                type = resource.getValue("type").jsonPrimitive.content,
                variant = resource["variant"]?.jsonPrimitive?.content,
                cases = resource.getValue("cases").jsonArray.map { case ->
                    val fields = case.jsonObject
                    StringCase(
                        quantity = fields["quantity"]?.jsonPrimitive?.int,
                        args = fields.getValue("args").jsonArray.map { argument(it.jsonPrimitive) },
                        text = fields.getValue("text").jsonPrimitive.content,
                    )
                },
            )
        }
    }

    val keptTags: List<String> by lazy {
        testTags.getValue("tags").jsonArray.map { it.jsonObject.getValue("tag").jsonPrimitive.content }
    }

    fun resourceId(type: String, name: String): Int =
        runCatching { Class.forName("dev.kodakiko.ui.core.R\$$type").getField(name).getInt(null) }.getOrDefault(0)

    fun render(resources: Resources, spec: StringResourceSpec, case: StringCase): String {
        val id = resourceId(spec.type, spec.name)
        val args = case.args.toTypedArray()
        return when {
            spec.type == "plurals" -> resources.getQuantityString(id, requireNotNull(case.quantity), *args)
            args.isEmpty() -> resources.getString(id)
            else -> resources.getString(id, *args)
        }
    }
}
