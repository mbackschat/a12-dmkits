package io.github.mbackschat.a12.dm.bridge

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import io.github.mbackschat.a12.dm.core.ConfigFlag
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal
import io.github.mbackschat.a12.dm.core.ModelIndex
import io.github.mbackschat.a12.dm.core.ModelReader
import io.github.mbackschat.a12.dm.core.ModelResolver
import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult
import io.github.mbackschat.a12.dm.rulekit.spi.ConditionReadBack

/**
 * The kernel-free [ConditionReadBack] (KERNEL-DECOUPLING-PROPOSAL ruling 6) — the peer of `:adapter`'s
 * kernel-backed provider, reading conditions through the interpreter's parser and [ConditionBridge] instead.
 * Registered via `META-INF/services`, so a classpath carrying this module and no kernel resolves read-back to
 * it.
 *
 * Tolerant by contract: every failure becomes [ReadResult.Opaque], including a rulekit rejection. That is
 * right for the port, whose callers only ask what a rule says, and wrong for measuring what rulekit catches —
 * for that, drive [ConditionBridge] directly, where a rejection and an unmapped construct are different
 * exceptions.
 *
 * The port is the module's only API. Everything the mapping is built from is `internal`, which the Kotlin
 * compiler enforces against a Kotlin consumer — but `internal` classes are PUBLIC in JVM bytecode, so a Java
 * consumer can still reach them. They are implementation detail and change without notice; what keeps that
 * honest is the committed API golden, which makes any movement in the compiled surface a reviewed diff.
 */
public class InterpreterConditionReadBack : ConditionReadBack {

    override fun readAll(modelJson: String, resolver: ModelResolver): Map<String, ReadResult> {
        val reader = ModelReader.expand(modelJson, resolver)
        val index = ModelIndex.of(reader)
        val model = BoundModel.of(index)
        val bridge = ConditionBridge(
            model,
            index,
            shortNameRefsAllowed(modelJson),
        )
        val language = languageOf(modelJson)

        val out = LinkedHashMap<String, ReadResult>()
        try {
            collect(MAPPER.readTree(reader.toJson()).path("content").path("modelRoot"), "", bridge, language, out)
        } catch (e: Exception) {
            throw IllegalStateException("could not read ${modelJson.length} chars of DM-JSON", e)
        }
        return out
    }

    private companion object {

        /**
         * Configured to match A12's own model mapper, as `core`'s `ModelReader` is: a commented DM-JSON model
         * is a legal A12 artifact (A12 enables `ALLOW_COMMENTS` on both of its mappers), so a plain mapper
         * would refuse a file the A12 tooling round-trips. The number settings keep this site identical to the
         * other DM-JSON readers rather than depending on this one happening to read no numbers today.
         */
        private val MAPPER: ObjectMapper = JsonMapper.builder()
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build()

        private fun collect(
            node: JsonNode,
            path: String,
            bridge: ConditionBridge,
            language: ConditionLanguage,
            out: MutableMap<String, ReadResult>,
        ) {
            if (node.isArray) {
                node.forEach { child -> collect(child, path, bridge, language, out) }
                return
            }
            if (!node.isObject) {
                return
            }
            val name = node.path("name").asText("")
            when (node.path("type").asText("")) {
                "Group" -> {
                    val groupPath = "$path/$name"
                    node.forEach { child -> collect(child, groupPath, bridge, language, out) }
                }
                "Rule" -> {
                    val condition = node.path("Rule").path("errorCondition").asText("")
                    if (condition.isNotEmpty()) {
                        out["$path/$name"] = read(bridge, condition, path, language)
                    }
                }
                else -> node.forEach { child -> collect(child, path, bridge, language, out) }
            }
        }

        private fun read(
            bridge: ConditionBridge,
            condition: String,
            basePath: String,
            language: ConditionLanguage,
        ): ReadResult = try {
            ReadResult.Converted(bridge.translate(condition, basePath, language))
        } catch (e: UnmappedConstructException) {
            ReadResult.Opaque(condition, "not mapped by the interpreter bridge: " + e.message)
        } catch (e: KernelCodeRefusal) {
            // The mapping SUCCEEDED and a rulekit gate refused what it built, which is the opposite message
            // to an unmapped construct — this bridge's contract makes a refusal one of its legitimate
            // outcomes rather than a failure (DEV-API-GAPS DG1001).
            ReadResult.Refused(condition, e.message.orEmpty(), e.code())
        } catch (e: RuntimeException) {
            ReadResult.Opaque(condition, e.javaClass.simpleName + ": " + e.message)
        }

        /**
         * The model's `fieldRefByShortNameAllowed`, which widens how a bare reference resolves.
         *
         * Read through A12's own POJO binding rather than `asBoolean`, which the two disagree on: a
         * capitalized `"True"` is on to A12 and off to `asBoolean`, so a bare name A12 binds through the
         * model-wide tier would be read back against the wrong resolution rule.
         */
        private fun shortNameRefsAllowed(modelJson: String): Boolean = try {
            val flag = MAPPER.readTree(modelJson).path("content").path("modelConfig")
                .path("fieldRefByShortNameAllowed")
            ConfigFlag.ofJsonText(if (flag.isMissingNode) null else flag.toString()) == ConfigFlag.ON
        } catch (e: Exception) {
            false
        }

        /** The expanded model's authoring language, which decides which keyword vocabulary the parser accepts. */
        private fun languageOf(modelJson: String): ConditionLanguage = try {
            val code = MAPPER.readTree(modelJson).path("content").path("modelConfig")
                .path("conditionLanguage").path("code").asText("en_US")
            if (code.startsWith("de")) ConditionLanguage.DE else ConditionLanguage.EN
        } catch (e: Exception) {
            ConditionLanguage.EN
        }
    }
}
