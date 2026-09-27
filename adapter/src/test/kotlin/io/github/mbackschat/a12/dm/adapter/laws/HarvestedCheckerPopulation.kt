package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.core.ElementType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.ElementDraft
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec
import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText
import io.github.mbackschat.a12.dm.rulekit.dsl.RoundingMode
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals

/** Original P4 bytes remain separate from hosts completed through the typed authoring surface. */
internal object HarvestedCheckerPopulation {
    private val mapper = ObjectMapper()
    private val validator = RuleValidator()

    fun completed(original: String, expectedHostClasses: Set<String> = emptySet()): String {
        val source = ModelDocument.read(original)
        val state = source.authoredState()
        val root = state.groups().keys.single { it.count { c -> c == '/' } == 1 }
        val host = ModelDocument.read(DocumentModelDraft.named(source.id()).locale("en_US")
            .rootGroup(root.removePrefix("/")) { }.build().toJson())
        source.config().conditionLanguage().takeIf { it.isNotEmpty() }?.let { host.setConditionLanguage(it) }
        source.baseYear().ifPresent { host.setBaseYear(it) }
        assertEquals(source.baseYear(), host.baseYear(), "Base Year survives completion")
        for (element in state.elements()) {
            val path = element.path()
            val parent = path.substringBeforeLast('/')
            val name = path.substringAfterLast('/')
            when (element.type()) {
                ElementType.GROUP -> if (path != root) {
                    val repeatability = state.groups().getValue(path).structure().repeatability()
                    if (repeatability > 1) host.addRepeatableGroup(parent, name, repeatability)
                    else host.addGroup(parent, name)
                }
                ElementType.FIELD -> {
                    val field = state.fields().getValue(path)
                    host.addField(parent, ElementDraft.Field(name, field.type(),
                        field.requirements().requiredness(), field.meta()))
                }
                else -> Unit
            }
        }
        state.groups().forEach { (path, group) ->
            group.structure().indexField().ifPresent { host.setIndexField(path, it) }
        }
        assertEquals(source.config().conditionLanguage().ifEmpty { "en_US" }, host.config().conditionLanguage(),
            "completion materializes absent condition language as English")
        val bareNumber = FieldTypeSpec.NumberSpec.builder().build()
        val fields = state.fields().mapValues { (_, field) ->
            if (field.type() != bareNumber) field else ModelDocument.FieldState(field.path(),
                FieldTypeSpec.NumberSpec.builder().maxFractionalDigits(0).build(), field.requirements(), field.meta())
        }
        assertEquals(fields, host.authoredState().fields(), "only bare Number scale is materialized as zero")
        assertEquals(state.groups().mapValues { it.value.structure() },
            host.authoredState().groups().mapValues { it.value.structure() }, "group structure survives completion")
        val json = host.toJson()
        assertEquals(expectedHostClasses, kernelModelClasses(json), "completed host control")
        val declaration = state.elements().single { it.type() == ElementType.RULE || it.type() == ElementType.COMPUTATION }
        val tree = mapper.readTree(original)
        val body = tree.findValues(declaration.type().wireName()).single()
        val group = declaration.path().substringBeforeLast('/')
        val name = declaration.path().substringAfterLast('/')
        val messages = body.path("errorMessage").map {
            LocalizedText(it.path("locale").asText(), it.path("text").asText())
        }
        val completed = when (declaration.type()) {
            ElementType.RULE -> validator.inject(json, DraftRule.builder().groupPath(group).name(name)
                .errorEntityRelPath(body.path("errorEntityRelPath").asText())
                .errorCondition(body.path("errorCondition").asText()).errorCode(body.path("errorCode").asText())
                .severity(body.path("severity").asText("ERROR")).messages(messages).build())
            ElementType.COMPUTATION -> validator.inject(json, DraftComputation.builder().groupPath(group).name(name)
                .computedFieldRelPath(body.path("computedFieldRelPath").asText())
                .commonPrecondition(body.get("commonPrecondition")?.asText())
                .errorCodesToSuppress(body.path("errorCodesToSuppress").map { it.asText() })
                .roundingMode(body.get("roundingMode")?.asText()?.let(RoundingMode::fromWire))
                .alternatives(body.path("computationAlternatives").map {
                    DraftComputation.Alternative(it.get("precondition")?.asText(), it.path("operation").asText())
                }).messages(messages).build())
            else -> error("not a harvested declaration")
        }
        val completedDeclaration = ModelDocument.read(completed).authoredState().elements()
            .single { it.type() == declaration.type() }
        assertEquals(declaration.path(), completedDeclaration.path(), "declaring location survives completion")
        val expectedBody = body.deepCopy<ObjectNode>()
        when (declaration.type()) {
            ElementType.RULE -> expectedBody.put("severity", body.path("severity").asText("ERROR"))
            ElementType.COMPUTATION -> {
                expectedBody.remove("errorCode")
                if (expectedBody.path("errorCodesToSuppress").isArray &&
                    expectedBody.path("errorCodesToSuppress").isEmpty) expectedBody.remove("errorCodesToSuppress")
            }
            else -> error("not a harvested declaration")
        }
        assertEquals(expectedBody, mapper.readTree(completed).findValues(declaration.type().wireName()).single(),
            "declaration survives completion, except default severity, empty suppression and the stray computation errorCode")
        return completed
    }
}
