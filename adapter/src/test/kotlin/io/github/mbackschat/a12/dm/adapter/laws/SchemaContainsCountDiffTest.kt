package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.resource.DisallowSchemaLoader
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.schemakit.JsonSchemaExporter
import io.github.mbackschat.a12.dm.schemakit.TranscodeReport
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.Requiredness
import io.github.mbackschat.a12.dm.schemakit.MappingProfile
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchemaContainsCountDiffTest {
    @org.junit.jupiter.api.BeforeEach
    fun selectKernelReadBack() = io.github.mbackschat.a12.dm.rulekit.spi.ReadBackends.override(
        io.github.mbackschat.a12.dm.adapter.KernelConditionReadBack())

    @org.junit.jupiter.api.AfterEach
    fun resetReadBack() = io.github.mbackschat.a12.dm.rulekit.spi.ReadBackends.reset()

    private val schemas = JsonSchemaFactory.builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
        .schemaLoaders { it.values { loaders -> loaders.clear() }.add(DisallowSchemaLoader.getInstance()) }.build()

    @Test
    fun rulePatternsRemainCarriedWhenANativeScalarWouldLoseApplicabilityOrConjunction() {
        for (guarded in listOf(false, true)) for (multiple in listOf(false, true)) {
            val structure = DocumentModelDraft.named("parcel-export").locale("en_US")
                .rootGroup("Parcel") { it.stringField("Reference") { }.stringField("Mode") { } }.build().toJson()
            val session = EditSession.read(structure)
            val parcel = session.group("/Parcel")
            val reference = parcel.str("Reference")
            val mode = parcel.str("Mode")
            val violation = reference.filled().and(reference.violates("[A-Z]+"))
            parcel.addRule { it.name("ReferenceFormat").errorField(reference)
                .`when`(if (guarded) violation.and(mode.eq("active")) else violation)
                .code("REFERENCE_FORMAT").message(Dsl.en("Check the reference format.")).build() }
            if (multiple) parcel.addRule { it.name("ReferenceDigits").errorField(reference)
                .`when`(reference.filled().and(reference.violates("[0-9]+")))
                .code("REFERENCE_DIGITS").message(Dsl.en("Check the reference digits.")).build() }
            val model = session.toJson()
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
            val exported = JsonSchemaExporter().export(model)
            val validator = schemas.getSchema(exported.jsonSchema())
            val mapper = ObjectMapper()
            val expectedRules = if (multiple) 2 else 1
            val carried = mapper.readTree(exported.jsonSchema()).path("x-a12-rule")
            assertEquals(expectedRules, carried.size())
            val imported = JsonSchemaImporter().importSchema(exported.jsonSchema()).dmJson()
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(imported))
            for ((value, modeValue) in listOf("" to "active", "ABC" to "active", "123" to "active", "bad" to "idle")) {
                val body = mapper.writeValueAsString(mapOf("Reference" to value, "Mode" to modeValue))
                val document = "{\"Parcel\":$body}"
                val dynamic = DocumentDifferential.kernel(model, document, dynamic = true)
                assertEquals(dynamic, DocumentDifferential.kernel(model, document, dynamic = false), "$guarded/$multiple/$body static")
                assertEquals(dynamic, DocumentDifferential.interpreter(model, document), "$guarded/$multiple/$body interpreter")
                assertEquals(dynamic, DocumentDifferential.kernel(imported, document, dynamic = true), "$guarded/$multiple/$body restored")
                val expectedCodes = buildList {
                    if (value.isNotEmpty() && !value.matches(Regex("[A-Z]+")) && (!guarded || modeValue == "active")) add("REFERENCE_FORMAT")
                    if (multiple && value.isNotEmpty() && !value.matches(Regex("[0-9]+"))) add("REFERENCE_DIGITS")
                }.sorted()
                assertEquals(expectedCodes, dynamic.messages.map { it.code }.sorted(), "$guarded/$multiple/$body")
                assertTrue(validator.validate(mapper.readTree(body)).isEmpty(),
                    "the unsupported native projection remains carriage-only: $guarded/$multiple/$body")
            }
            assertTrue(mapper.readTree(exported.jsonSchema()).path("properties").path("Reference").path("pattern").isMissingNode)
            assertTrue(exported.report().diagnostics().any { it.contains("pattern") && it.contains("carried") })
            assertEquals(expectedRules, exported.report().dispositions().count { it.tier() == TranscodeReport.Tier.CARRIED && it.target() == "x-a12-rule" })
        }
    }

    @Test
    fun nativeMinimumsUseTheWholePredicateRequireTheirRootArrayAndIntersectIndependentRules() {
        val mapper = ObjectMapper()
        for (guarded in listOf(false, true)) for (reversed in listOf(false, true)) {
            val structure = DocumentModelDraft.named("parcel-count-export").locale("en_US")
                .rootGroup("Parcel") { root -> root.stringField("Mode") { }.group("Legs") { legs ->
                    legs.repeatable(4).stringField("Label") { }
                } }.build().toJson()
            val session = EditSession.read(structure)
            val parcel = session.group("/Parcel")
            val legs = parcel.group("Legs")
            val label = parcel.str("Legs/Label")
            val mode = parcel.str("Mode")
            for (bound in if (reversed) listOf(2, 1) else listOf(1, 2)) {
                val count = Dsl.numberOfFilledGroups(legs).lt(bound.toLong())
                parcel.addRule { it.name("MinLegs$bound").errorField(label)
                    .`when`(if (guarded) count.and(mode.eq("active")) else count)
                    .code("MIN_LEGS_$bound").message(Dsl.en("Check the number of delivery legs.")).build() }
            }
            val model = session.toJson()
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
            val exported = JsonSchemaExporter().export(model)
            val schema = mapper.readTree(exported.jsonSchema())
            val validator = schemas.getSchema(exported.jsonSchema())
            for (size in listOf<Int?>(null, 0, 1, 2)) for (modeValue in listOf("active", "idle")) {
                val body = linkedMapOf<String, Any>("Mode" to modeValue)
                if (size != null) body["Legs"] = List(size) { emptyMap<String, String>() }
                val tree = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(body)
                val document = mapper.writeValueAsString(mapOf("Parcel" to body))
                val dynamic = DocumentDifferential.kernel(model, document, dynamic = true)
                assertEquals(dynamic, DocumentDifferential.kernel(model, document, dynamic = false), "$guarded/$reversed/$body static")
                assertEquals(dynamic, DocumentDifferential.interpreter(model, document), "$guarded/$reversed/$body interpreter")
                val expected = if (!guarded || modeValue == "active") (1..2).filter { (size ?: 0) < it }.map { "MIN_LEGS_$it" } else emptyList()
                assertEquals(expected, dynamic.messages.map { it.code }.sorted(), "$guarded/$reversed/$body")
                assertEquals(if (guarded) true else expected.isEmpty(), validator.validate(tree).isEmpty(), "$guarded/$reversed/$body native")
            }
            assertEquals(if (guarded) null else 2, schema.path("properties").path("Legs").get("minItems")?.asInt())
            assertEquals(!guarded, schema.path("required").any { it.asText() == "Legs" })
            if (guarded) assertTrue(exported.report().diagnostics().any { it.contains("whole") && it.contains("carried") })
            val imported = JsonSchemaImporter().importSchema(exported.jsonSchema()).dmJson()
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(imported))
            assertEquals(schema.path("x-a12-rule"), mapper.readTree(JsonSchemaExporter().export(imported).jsonSchema()).path("x-a12-rule"))
        }
    }

    @Test
    fun activeStringPredicatesConflateAbsentAndEmptyWithPopulatedControls() {
        val base = DocumentModelDraft.named("parcel-presence").locale("en_US")
            .rootGroup("Parcel") { root -> root.group("Legs") { legs ->
                legs.repeatable(4).stringField("Marker") { }.stringField("Kind") { }
            } }.build().toJson()
        val model = BoundModel.ofJson(base)
        val legs = model.group("/Parcel/Legs")
        val marker = model.str("/Parcel/Legs/Marker")
        val kind = model.str("/Parcel/Legs/Kind")
        var json = base
        for ((code, condition) in listOf("FILLED" to kind.filled(), "EMPTY" to kind.notFilled(),
            "EXPRESS" to kind.eq("express"), "OTHER" to kind.ne("express"))) {
            json = TypedRules.inject(json, model.rule(legs).name("Check$code").errorField(marker.field())
                .`when`(marker.filled().and(condition)).code(code).message(Dsl.en("Review this delivery leg.")).build())
        }
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(json))
        val observations = listOf(null, "", "express", "ground").map { value ->
            val row = linkedMapOf("Marker" to "kept")
            if (value != null) row["Kind"] = value
            val document = ObjectMapper().writeValueAsString(mapOf("Parcel" to mapOf("Legs" to listOf(row))))
            val dynamic = DocumentDifferential.kernel(json, document, dynamic = true)
            assertEquals(dynamic, DocumentDifferential.kernel(json, document, dynamic = false), "$value static")
            assertEquals(dynamic, DocumentDifferential.interpreter(json, document), "$value interpreter")
            dynamic
        }
        assertEquals(observations[0], observations[1], "active predicates retain the same absent/empty observation")
        assertEquals(listOf("EXPRESS", "FILLED"), observations[2].messages.map { it.code }.sorted())
        assertEquals(listOf("FILLED", "OTHER"), observations[3].messages.map { it.code }.sorted())
        assertTrue(observations[0].messages.any { it.code == "EMPTY" && it.type == "OMISSION_ERROR" })
    }

    @Test
    fun importedMatchingCountsAgreeAcrossBothKernelStrategies() {
        val numeric = """
            {"type":"object","required":["legs"],"properties":{"legs":{"type":"array","maxItems":8,
             "items":{"type":"object","properties":{"marker":{"type":"string"},"amount":{"type":"integer"},"units":{"type":"integer"}}},
             "contains":{"properties":{"amount":{"minimum":5},"units":{"maximum":10}}},"minContains":2,"maxContains":2}}}
        """.trimIndent()
        val strings = """
            {"type":"object","required":["legs"],"properties":{"legs":{"type":"array","maxItems":8,
             "items":{"type":"object","properties":{"marker":{"type":"string"},"kind":{"type":"string"}}},
             "contains":{"properties":{"kind":{"enum":["","express"]}}},"minContains":2,"maxContains":2}}}
        """.trimIndent()
        val cases = listOf(
            Triple(numeric, """[{"marker":"absent"},{"amount":5,"units":10},{"amount":4,"units":11},{"amount":5,"units":11}]""", true),
            Triple(numeric, """[{"marker":"absent"},{"amount":4,"units":11}]""", false),
            Triple(strings, """[{"marker":"absent"},{"kind":""},{"kind":"ground"}]""", true),
            Triple(strings, """[{"marker":"absent"},{"kind":""},{"kind":"express"}]""", false),
        )
        for ((i, case) in cases.withIndex()) {
            val (schema, rows, accepted) = case
            val model = JsonSchemaImporter().importSchema(schema).dmJson()
            val document = """{"Root":{"legs":$rows}}"""
            val dynamic = DocumentDifferential.kernel(model, document, dynamic = true)
            assertEquals(accepted, dynamic.messages.isEmpty(), "case $i")
            assertEquals(dynamic, DocumentDifferential.kernel(model, document, dynamic = false), "case $i static")
            assertEquals(dynamic, DocumentDifferential.interpreter(model, document), "case $i interpreter")
        }
    }

    @Test
    fun enumOnlyScalarItemsPreserveEmptyAdmissionAcrossBothKernelStrategies() {
        val mapper = ObjectMapper()
        for (typed in listOf(false, true)) for (emptyAllowed in listOf(false, true)) {
            val items = linkedMapOf<String, Any>("enum" to
                if (emptyAllowed) listOf("", "express", "ground") else listOf("express", "ground"))
            if (typed) items["type"] = "string"
            val source = mapper.writeValueAsString(mapOf("type" to "object", "properties" to mapOf(
                "tags" to mapOf("type" to "array", "maxItems" to 4, "items" to items))))
            val imported = JsonSchemaImporter().importSchema(source)
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(imported.dmJson()))
            assertTrue(imported.report().dispositions().none { it.tier() == TranscodeReport.Tier.OMITTED })
            val validator = schemas.getSchema(source)
            for (value in listOf("", "express", "air")) {
                val expected = value == "express" || emptyAllowed && value.isEmpty()
                val cell = "$typed/$emptyAllowed/$value"
                val nativeBody = mapper.writeValueAsString(mapOf("tags" to listOf(value)))
                assertEquals(expected, validator.validate(mapper.readTree(nativeBody)).isEmpty(), "$cell source")
                val document = mapper.writeValueAsString(mapOf("Root" to mapOf("tags" to listOf(mapOf("value" to value)))))
                val dynamic = DocumentDifferential.kernel(imported.dmJson(), document, dynamic = true)
                assertEquals(expected, dynamic.messages.isEmpty(), "$cell dynamic")
                assertEquals(dynamic, DocumentDifferential.kernel(imported.dmJson(), document, dynamic = false), "$cell static")
                assertEquals(dynamic, DocumentDifferential.interpreter(imported.dmJson(), document), "$cell interpreter")
            }
        }
    }

    @Test
    fun valueListPreservesLengthAndMakesClosedEnumLossExplicit() {
        val mapper = ObjectMapper()
        val lengthSource = """
            {"type":"object","properties":{"tags":{"type":"array","maxItems":4,
             "items":{"type":"string","minLength":2}}}}
        """.trimIndent()
        val enumSource = """
            {"type":"object","properties":{"tags":{"type":"array","maxItems":4,
             "items":{"type":"string","enum":["express","ground"]}}}}
        """.trimIndent()
        val cases = listOf(
            Triple(lengthSource, MappingProfile.ArrayOfScalar.VALUE_LIST, listOf("x" to false, "xx" to true)),
            Triple(enumSource, MappingProfile.ArrayOfScalar.MULTI_SELECT, listOf("air" to false, "express" to true, "ground" to true)),
        )
        for ((source, strategy, values) in cases) {
            val imported = JsonSchemaImporter().importSchema(source, MappingProfile.defaults().withArrayOfScalar(strategy))
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(imported.dmJson()))
            assertTrue(imported.report().dispositions().none { it.tier() == TranscodeReport.Tier.OMITTED })
            val validator = schemas.getSchema(source)
            for ((value, expected) in values) {
                val nativeBody = mapper.writeValueAsString(mapOf("tags" to listOf(value)))
                assertEquals(expected, validator.validate(mapper.readTree(nativeBody)).isEmpty(), "$strategy/$value source")
                val document = mapper.writeValueAsString(mapOf("Root" to mapOf("tags" to listOf(mapOf("value" to value)))))
                val dynamic = DocumentDifferential.kernel(imported.dmJson(), document, dynamic = true)
                assertEquals(expected, dynamic.messages.isEmpty(), "$strategy/$value dynamic")
                assertEquals(dynamic, DocumentDifferential.kernel(imported.dmJson(), document, dynamic = false), "$strategy/$value static")
                assertEquals(dynamic, DocumentDifferential.interpreter(imported.dmJson(), document), "$strategy/$value interpreter")
            }
        }
        val omitted = JsonSchemaImporter().importSchema(enumSource,
            MappingProfile.defaults().withArrayOfScalar(MappingProfile.ArrayOfScalar.VALUE_LIST))
        assertTrue(omitted.report().dispositions().any { it.source() == "/Root/tags" && it.tier() == TranscodeReport.Tier.OMITTED })
        assertTrue(omitted.report().diagnostics().any { it.contains("enum") && it.contains("MULTI_SELECT") })
        assertEquals(emptyList<String>(), io.github.mbackschat.a12.dm.modelkit.ModelDocument.read(omitted.dmJson()).fieldPaths())
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(omitted.dmJson()))
    }

    @Test
    fun scalarCarrierAdmissionAndEmptyItemRuntimeAreMeasuredBeforeContains() {
        val mapper = ObjectMapper()
        for (template in listOf(false, true)) for (enumeration in listOf(false, true)) {
            val builder = DocumentModelDraft.named("parcel-scalar-carrier").locale("en_US")
                .rootGroup("Parcel") { root ->
                    if (template) root.multiSelectGroup("Tags") {
                        if (enumeration) it.enumField("value", "", "express", "ground").maxRows(4)
                        else it.stringField("value").maxRows(4)
                    } else root.group("Tags") { tags ->
                        tags.repeatable(4)
                        if (enumeration) tags.enumField("value") { it.value("").value("express").value("ground") }
                        else tags.stringField("value") { }
                    }
                }
            val model = builder.build().toJson()
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model), "$template/$enumeration source")
            val edit = EditSession.read(model)
            edit.field("/Parcel/Tags/value").setRequiredness(Requiredness.OPTIONAL)
            val notifications = RuntimeLaws.consistencyMessages(edit.toJson())
            if (template) {
                assertEquals(1, notifications.size, notifications.toString())
                assertTrue(notifications.single().startsWith("ERROR:") && notifications.single().contains("must be marked as required"), notifications.toString())
            } else assertEquals(emptyList<String>(), notifications)
            for (values in listOf<List<String>?>(null, emptyList(), listOf(""), listOf("express"))) {
                val body = linkedMapOf<String, Any>()
                if (values != null) body["Tags"] = values.map { mapOf("value" to it) }
                val document = mapper.writeValueAsString(mapOf("Parcel" to body))
                val dynamic = DocumentDifferential.kernel(model, document, dynamic = true)
                assertEquals(dynamic, DocumentDifferential.kernel(model, document, dynamic = false), "$template/$enumeration/$values static")
                assertEquals(dynamic, DocumentDifferential.interpreter(model, document), "$template/$enumeration/$values interpreter")
                println("scalar carrier template=$template enum=$enumeration values=$values observation=$dynamic")
                val expected = template && values == listOf("")
                assertEquals(if (expected) listOf("mandatoryField") else emptyList(), dynamic.messages.map { it.code }, "$template/$enumeration/$values")
                if (expected) {
                    assertEquals("OMISSION_ERROR", dynamic.messages.single().type)
                    assertEquals("/Parcel[1]/Tags[1]/value", dynamic.messages.single().pointer)
                }
            }
        }
    }

    @Test
    fun scalarStringContainsCountsPresentEmptyItemsAcrossThresholdsAndBothKernelStrategies() {
        val mapper = ObjectMapper()
        for ((predicate, matches) in listOf("""{"const":""}""" to 1,
                """{"enum":["","express"]}""" to 2, """{"pattern":""}""" to 3,
                """{"const":"express"}""" to 1)) {
            for (bound in listOf(matches - 1, matches, matches + 1)) for (enumeration in listOf(false, true)) {
                val domain = if (enumeration) ",\"enum\":[\"\",\"express\",\"ground\"]" else ""
                val schema = """
                    {"type":"object","required":["tags"],"properties":{"tags":{"type":"array","maxItems":4,
                     "items":{"type":"string"$domain},"contains":$predicate,"minContains":$bound,"maxContains":$bound}}}
                """.trimIndent()
                val imported = JsonSchemaImporter().importSchema(schema)
                assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(imported.dmJson()))
                assertTrue(imported.report().dispositions().none { it.tier() == TranscodeReport.Tier.OMITTED }, imported.report().toString())
                val body = mapper.writeValueAsString(mapOf("Root" to mapOf("tags" to listOf("", "express", "ground").map { mapOf("value" to it) })))
                val dynamic = DocumentDifferential.kernel(imported.dmJson(), body, dynamic = true)
                assertEquals(dynamic, DocumentDifferential.kernel(imported.dmJson(), body, dynamic = false), "$predicate/$bound static")
                assertEquals(dynamic, DocumentDifferential.interpreter(imported.dmJson(), body), "$predicate/$bound interpreter")
                val expected = when {
                    bound < matches -> listOf("MAX_CONTAINS_TAGS_VALUE")
                    bound > matches -> listOf("MIN_CONTAINS_TAGS_VALUE")
                    else -> emptyList()
                }
                assertEquals(expected, dynamic.messages.map { it.code }, "$predicate/$bound")
            }
        }
    }

    @Test
    fun objectEnumContainsRetainsPropertyVacuityAndEmptyMembershipAcrossThresholds() {
        val mapper = ObjectMapper()
        val rows = listOf(mapOf("marker" to "absent"), mapOf("kind" to ""),
            mapOf("kind" to "express"), mapOf("kind" to "ground"))
        val nativeBody = mapper.writeValueAsString(mapOf("legs" to rows))
        val document = mapper.writeValueAsString(mapOf("Root" to mapOf("legs" to rows)))
        for ((predicate, matches) in listOf("""{"const":""}""" to 2,
                """{"enum":["","express"]}""" to 3)) {
            for (bound in listOf(matches - 1, matches, matches + 1)) {
                val source = """
                    {"type":"object","required":["legs"],"properties":{"legs":{"type":"array","maxItems":4,
                     "items":{"type":"object","properties":{"marker":{"type":"string"},
                       "kind":{"type":"string","enum":["","express","ground"]}}},
                     "contains":{"properties":{"kind":$predicate}},"minContains":$bound,"maxContains":$bound}}}
                """.trimIndent()
                val imported = JsonSchemaImporter().importSchema(source)
                assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(imported.dmJson()), "$predicate/$bound admission")
                assertTrue(imported.report().dispositions().none { it.tier() == TranscodeReport.Tier.OMITTED }, imported.report().toString())
                assertEquals(bound == matches, schemas.getSchema(source).validate(mapper.readTree(nativeBody)).isEmpty(), "$predicate/$bound native")
                val dynamic = DocumentDifferential.kernel(imported.dmJson(), document, dynamic = true)
                assertEquals(dynamic, DocumentDifferential.kernel(imported.dmJson(), document, dynamic = false), "$predicate/$bound static")
                assertEquals(dynamic, DocumentDifferential.interpreter(imported.dmJson(), document), "$predicate/$bound interpreter")
                val expected = when {
                    bound < matches -> listOf("MAX_CONTAINS_LEGS_KIND")
                    bound > matches -> listOf("MIN_CONTAINS_LEGS_KIND")
                    else -> emptyList()
                }
                assertEquals(expected, dynamic.messages.map { it.code }, "$predicate/$bound")
            }
        }
    }


    @Test
    fun objectEnumEmptyDomainsReportUnsupportedNonemptyContainsPredicates() {
        val mapper = ObjectMapper()
        val checks = mutableListOf<org.junit.jupiter.api.function.Executable>()
        for (predicate in listOf("""{"const":"express"}""", """{"enum":["express"]}""")) {
            val source = """
                {"type":"object","required":["legs"],"properties":{"legs":{"type":"array","maxItems":4,
                 "items":{"type":"object","properties":{"marker":{"type":"string"},
                   "kind":{"type":"string","enum":["","express","ground"]}}},
                 "contains":{"properties":{"kind":$predicate}},"minContains":0,"maxContains":0}}}
            """.trimIndent()
            val imported = JsonSchemaImporter().importSchema(source)
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(imported.dmJson()), "$predicate admission")
            assertTrue(imported.report().dispositions().any { it.tier() == TranscodeReport.Tier.OMITTED && it.source() == "/Root/legs" })
            assertTrue(imported.report().diagnostics().any { it.contains("absent/empty") })
            val validator = schemas.getSchema(source)
            for (value in listOf<String?>(null, "", "express", "ground")) checks.add(org.junit.jupiter.api.function.Executable {
                val row = linkedMapOf("marker" to "kept")
                if (value != null) row["kind"] = value
                val body = mapOf("legs" to listOf(row))
                val native = validator.validate(mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(body)).isEmpty()
                assertEquals(value != null && value != "express", native, "$predicate/$value native discriminator")
                val document = mapper.writeValueAsString(mapOf("Root" to body))
                val dynamic = DocumentDifferential.kernel(imported.dmJson(), document, dynamic = true)
                assertEquals(dynamic, DocumentDifferential.kernel(imported.dmJson(), document, dynamic = false), "$predicate/$value static")
                assertEquals(dynamic, DocumentDifferential.interpreter(imported.dmJson(), document), "$predicate/$value interpreter")
                assertEquals(emptyList<String>(), dynamic.messages.map { it.code }, "$predicate/$value omitted translator")
            })
        }
        org.junit.jupiter.api.Assertions.assertAll(checks)
    }

}
