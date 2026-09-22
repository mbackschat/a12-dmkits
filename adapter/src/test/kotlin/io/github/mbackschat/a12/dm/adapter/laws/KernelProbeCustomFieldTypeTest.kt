package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A probe request can REGISTER the custom field types its model declares, so a filled CUSTOM cell becomes an
 * observation instead of a fault.
 *
 * **Why the probe grew it.** Without a registration route the whole CUSTOM family is unobservable, not merely
 * unvalidated: a filled custom cell raises out of the entire `validateFull` call on both strategies
 * ([KernelProbeFaultCaptureTest] pins that fault), which takes every other rule on that document down with it.
 * a12-kernel-lean parked three items on exactly this, one of them marked route-closed rather than pending
 * (their 2026-09-21 note §5). The kernel resolves a validator from the per-call
 * `DocumentProcessingConfig.customFieldTypeFactory` and falls back to the static `CustomFieldTypeService`,
 * which only an explicit host `register` populates — so both routes are empty unless a request supplies one.
 *
 * **What the artifact then carries is the HOST's error, verbatim.** The code is the validator's own
 * `getErrorKey()` rather than a fixed kernel code, and the message is its own text — KERNEL-FINDINGS KF54
 * states that from source, and [theHostsOwnErrorKeyAndMessageReachTheArtifactOnBothStrategies] is the runtime
 * measurement of the same claim on kernel 30.8.1.
 *
 * **The within-model control is the point of the `Reference` field.** One model carries a STRING field and a
 * CUSTOM field with the SAME declared `maxLength`, so [aCustomFieldsDeclaredBoundIsTheHostsToEnforce] varies
 * exactly one thing — the field's kind — and shows the kernel enforcing the bound itself on one
 * (`stringZuLang`) and delegating it entirely on the other. Two models could not separate that from anything
 * else that happened to differ between them.
 */
class KernelProbeCustomFieldTypeTest {

    /**
     * The capability, end to end: the same model+document that faults without a registry validates with one,
     * and the artifact carries the host validator's own key as the message code on BOTH strategies.
     */
    @Test
    fun theHostsOwnErrorKeyAndMessageReachTheArtifactOnBothStrategies() {
        val malformed = artifact.rowNamed("malformed")
        assertTrue(malformed.path("enginesAgree").asBoolean(), "a registered type resolves on both strategies")

        malformed.path("engines").forEach { engine ->
            val messages = engine.path("messages")
            assertEquals(1, messages.size(), "${engine.path("engine").asText()} — one message, the host's")
            val only = messages.path(0)
            assertEquals("customFieldType.pattern", only.path("code").asText(),
                "the code is ICustomFieldTypeCheckError.getErrorKey(), not a fixed kernel code")
            assertEquals("Order/CouponCode", only.path("field").asText())
            assertEquals("VALUE_ERROR", only.path("type").asText())
            assertEquals("value does not match the declared pattern [A-Z]{4}-[0-9]{4}",
                only.path("message").asText(), "…and the message is the host's own text")
        }
    }

    /**
     * A well-formed value and an empty cell are the liveness controls for the row above: without them a
     * registry that rejected EVERYTHING would pass it. The empty row additionally reproduces the reason the
     * fault is intermittent — an empty custom cell never reaches the validator at all.
     */
    @Test
    fun aRegisteredTypeAcceptsWhatItShouldAndNeverSeesAnEmptyCell() {
        listOf("empty", "well-formed").forEach { name ->
            artifact.rowNamed(name).path("engines").forEach { engine ->
                assertTrue(engine.path("messages").isEmpty,
                    "row '$name' on ${engine.path("engine").asText()} must be clean")
            }
        }
    }

    /**
     * KF54's delegation clause, measured rather than read: a CUSTOM field's declared `maxLength` is handed to
     * the host through `ICustomFieldTypeValidationParam` and enforced by nobody else, while the SAME bound on
     * a STRING field in the SAME model is the kernel's own (`stringZuLang`).
     *
     * The discriminator is the message COUNT, not its text. If the kernel also enforced a custom field's
     * bound there would be two messages on that row — the host's and its own — so `1` is what separates
     * delegation from belt-and-braces, and the STRING row proves the run could have produced a kernel code.
     */
    @Test
    fun aCustomFieldsDeclaredBoundIsTheHostsToEnforce() {
        artifact.rowNamed("over-the-custom-bound").path("engines").forEach { engine ->
            val messages = engine.path("messages")
            assertEquals(1, messages.size(),
                "only the host answers for a custom bound; a second message would mean the kernel checks too")
            assertEquals("field.maxLength", messages.path(0).path("code").asText(),
                "…and that one message is the host's, raised from the bound the kernel handed it")
        }

        artifact.rowNamed("over-the-string-bound").path("engines").forEach { engine ->
            assertEquals("stringZuLang", engine.path("messages").path(0).path("code").asText(),
                "the same bound on a STRING field IS kernel-enforced — the control that makes the row above "
                    + "a measurement rather than an absence")
        }
    }

    /** An empty registry declares nothing, so it is a mistake rather than the default it looks like. */
    @Test
    fun anEmptyRegistryIsRefusedRatherThanTreatedAsAbsent() {
        val empty = request("empty-registry", plainModel,
            rows = """{ "name": "r", "documentInline": { "Order": {} } }""", customFieldTypes = "")

        val refused = assertThrows(IllegalArgumentException::class.java) {
            KernelRuntimeProbe.main(arrayOf(empty.toString(), dir.resolve("unused.json").toString()))
        }
        assertTrue("must be a non-empty object" in refused.message.orEmpty(), refused.message)
    }

    /** A misspelled constraint is refused by name rather than silently ignored, which would read as a pass. */
    @Test
    fun anUnknownConstraintKeyIsRefusedByName() {
        val typo = request("typo", plainModel,
            rows = """{ "name": "r", "documentInline": { "Order": {} } }""",
            customFieldTypes = """"CouponCode": { "regex": "[A-Z]+" }""")

        val refused = assertThrows(IllegalArgumentException::class.java) {
            KernelRuntimeProbe.main(arrayOf(typo.toString(), dir.resolve("unused-typo.json").toString()))
        }
        assertTrue("""unknown key "regex"""" in refused.message.orEmpty(), refused.message)
    }

    /**
     * …and the published schema refuses the same two mistakes. An agent reads the schema before it runs the
     * probe, so a schema that accepts what the producer rejects teaches the wrong shape.
     */
    @Test
    fun theSchemaRefusesTheSameTwoRegistryMistakes() {
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(MAPPER.readTree(
                javaClass.classLoader.getResource("kernel-probe/request.schema.json")!!.readText()))

        fun invalidities(registry: String) = schema.validate(MAPPER.readTree(
            """{ "model": "m.dm.json", "customFieldTypes": $registry,
                 "rows": [ { "name": "r", "documentInline": {} } ] }"""))

        assertTrue(invalidities("""{ "CouponCode": { "pattern": "[A-Z]+", "minLength": 2 } }""").isEmpty(),
            "a well-formed registry must be ACCEPTED, or the refusals below prove nothing")
        assertTrue(invalidities("{}").isNotEmpty(), "an empty registry declares nothing and is a mistake")
        assertTrue(invalidities("""{ "CouponCode": { "regex": "[A-Z]+" } }""").isNotEmpty(),
            "an unknown constraint key must be refused rather than silently ignored")
    }

    /** The schema must DEMONSTRATE the key it advertises; validity of every example is
     *  [KernelProbeArtifactTest.theRequestSchemaAcceptsItsOwnExample]'s job. */
    @Test
    fun theRequestSchemaDemonstratesTheRegistry() {
        val schema = MAPPER.readTree(
            javaClass.classLoader.getResource("kernel-probe/request.schema.json")!!.readText())

        assertTrue(schema.path("examples").any { it.has("customFieldTypes") },
            "an advertised key with no worked example is how a consumer learns it from prose instead")
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private fun JsonNode.rowNamed(name: String): JsonNode =
        path("rows").first { it.path("name").asText() == name }

    companion object {

        private val MAPPER = ObjectMapper()

        @JvmStatic
        @TempDir
        lateinit var dir: Path

        /**
         * `CouponCode` carries the pattern; `LoyaltyId` declares NO type-level constraint, so the only bound
         * its row can trip is the one the kernel hands over from the field declaration.
         */
        private const val REGISTRY = """"CouponCode": { "pattern": "[A-Z]{4}-[0-9]{4}" }, "LoyaltyId": {}"""

        /**
         * An e-commerce order: a coupon code and a loyalty id as custom types, plus a plain string reference
         * carrying the SAME declared bound as the loyalty id — the within-model control.
         */
        private val model: String by lazy {
            DocumentModelDraft.named("probe-custom").locale("en_US")
                .rootGroup("Order") { order ->
                    order.stringField("Reference") { it.maxLength(6) }
                        .customField("CouponCode") { it.type("CouponCode") }
                        .customField("LoyaltyId") { it.type("LoyaltyId").maxLength(6) }
                }.build().toJson()
        }

        /** The same domain with no custom field at all — the refusal rows need a model, not a registration. */
        private val plainModel: String by lazy {
            DocumentModelDraft.named("probe-custom-plain").locale("en_US")
                .rootGroup("Order") { order -> order.stringField("Reference") { it.maxLength(6) } }
                .build().toJson()
        }

        private fun write(name: String, content: String): Path = Files.writeString(dir.resolve(name), content)

        private fun request(
            name: String,
            model: String,
            rows: String? = null,
            customFieldTypes: String? = null,
        ): Path {
            val keys = buildList {
                add(""" "model": "${write("$name.model.json", model).fileName}" """)
                if (customFieldTypes != null) add(""" "customFieldTypes": { $customFieldTypes } """)
                if (rows != null) add(""" "rows": [ $rows ] """)
            }
            return write("$name.request.json", keys.joinToString(",", "{", "}"))
        }

        private val artifact: JsonNode by lazy {
            val rows = listOf(
                """{ "name": "empty", "documentInline": { "Order": {} } }""",
                """{ "name": "well-formed", "documentInline": { "Order": { "CouponCode": "SAVE-2026" } } }""",
                """{ "name": "malformed", "documentInline": { "Order": { "CouponCode": "save-2026" } } }""",
                """{ "name": "over-the-custom-bound",
                     "documentInline": { "Order": { "LoyaltyId": "ABCDEFGHI" } } }""",
                """{ "name": "over-the-string-bound",
                     "documentInline": { "Order": { "Reference": "ABCDEFGHI" } } }""",
            ).joinToString(",")
            val out = dir.resolve("registered.json")
            KernelRuntimeProbe.main(
                arrayOf(request("registered", model, rows, REGISTRY).toString(), out.toString()))
            MAPPER.readTree(Files.readString(out))
        }
    }
}
