package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [KernelRuntimeProbe]'s kernel-backed acceptance: the probe answers a runtime question end-to-end, from a
 * request file to a pinnable artifact, over the REAL kernel — the route that replaces a bespoke Kotlin test per
 * question. The shape-only half (a strategy split surviving, message ordering, the renderer) is
 * [KernelProbeArtifactTest].
 *
 * Everything runs through `main`, not through the internals: parse, the consistency gate, both strategies,
 * rendering, and the file write are what an external consumer actually invokes, and a test that skipped any of
 * them would leave that step unmeasured.
 *
 * The two models are authored with modelkit + the rulekit typed DSL and the conditions rendered to the canonical
 * string, so every path is written once and validated at the call site. The group-star aggregate used to be the
 * one authored string here; it is typed since `AggregateOperand` closed DG32.
 */
class KernelRuntimeProbeTest {

    // ---- acceptance case 2: coordinate identity round-trips ---------------------------------------

    /**
     * Acceptance case 2 — KERNEL-FINDINGS `kf183` reproduced through the probe instead of through a
     * hand-written test: a **starred** group operand's expanded reference carries the wildcard repetition, an
     * **unstarred** one is concrete at the firing row.
     *
     * Both readings arrive in dmtool's published `a12-message-address` form, which is the same distinction said
     * in the contract an artifact consumer already reads: an address that names one cell is canonical A12 (no
     * leading slash, the implicit `[1]` omitted), and one that does not — here, because a wildcard sits above the
     * last part — passes through as the house form. So the leading slash is the discriminator, and no coordinate
     * is lost: only A12's own implicit `[1]` is unwritten.
     */
    @Test
    fun aStarredGroupOperandWildcardsItsCoordinateWhereAnUnstarredOneIsConcrete() {
        val referenced = coordinateArtifact.rowNamed("billing-filled").anchor().referencedByCode()

        assertEquals(
            listOf("/Order[1]/Items[0]/Count"),
            referenced.getValue(COORD_STARRED).filter { "Items" in it },
            "a starred group's expansion wildcards its repetition, so the address names a column, not a cell",
        )
        assertEquals(
            listOf("Order/Billing/City", "Order/Billing/Contact/Email", "Order/Billing/Street"),
            referenced.getValue(COORD_UNSTARRED),
            "an unstarred group expands recursively and concretely at every level, the deeper Contact included",
        )
    }

    /**
     * Acceptance case 1's positive control, on the real kernel. Both strategies are always reported separately;
     * here they agree, which is what the whole exercised estate measures (zero java-static splits across both
     * real corpora, no committed conformance case carrying a `divergences` entry). The constructed
     * disagreement lives in [KernelProbeArtifactTest].
     */
    @Test
    fun bothKernelStrategiesAreReportedSeparatelyAndAgreeHere() {
        val row = coordinateArtifact.rowNamed("billing-filled")
        assertEquals(
            listOf(KernelRuntimeProbe.GROOVY_DYNAMIC, KernelRuntimeProbe.JAVA_STATIC),
            row.path("engines").map { it.path("engine").asText() },
            "every row carries one block per strategy, in a fixed order",
        )
        assertTrue(row.path("enginesAgree").asBoolean(), "the two strategies agree on this observation")
        assertEquals(
            row.anchor().referencedByCode(), row.path("engines")[1].referencedByCode(),
            "…and agreement means the generated-Java block really does carry the same sets",
        )
    }

    /** The published artifact schema is checked against real output, so it cannot drift away from the producer. */
    @Test
    fun realOutputConformsToThePublishedArtifactSchema() {
        val schemaNode = MAPPER.readTree(
            KernelRuntimeProbeTest::class.java.classLoader
                .getResource("kernel-probe/artifact.schema.json")!!.readText(),
        )
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode)
        assertEquals(emptySet<Any>(), schema.validate(coordinateArtifact), "the real artifact must conform")
    }

    // ---- acceptance case 3: polarity and fillToFix stay coupled -----------------------------------

    /**
     * Acceptance case 3 — on ONE document and one strategy: the OMISSION firing names the operands that would
     * clear it, and the VALUE firing names none. Reading both from the same row is what makes this a coupling
     * rather than two separate observations that happen to agree.
     */
    @Test
    fun anOmissionCarriesFillToFixWhereAValueErrorCarriesNone() {
        val row = polarityArtifact.rowNamed("filled-and-unfilled")
        assertTrue(row.path("enginesAgree").asBoolean(), "both strategies type the polarity identically")
        val messages = row.anchor().path("messages").associateBy { it.path("code").asText() }

        val omission = messages.getValue(POLARITY_OMISSION)
        assertEquals("OMISSION_ERROR", omission.path("type").asText())
        assertEquals(
            listOf("Order/BackorderQuantity", "Order/Quantity"),
            omission.path("fillToFix").map { it.asText() },
            "an omission names the operands whose filling could clear it — measured as ALL of them, not only " +
                "the empty one, which is the reading a single-operand expectation would have gotten wrong",
        )

        val value = messages.getValue(POLARITY_VALUE)
        assertEquals("VALUE_ERROR", value.path("type").asText())
        assertTrue(value.path("fillToFix").isEmpty, "no fill fixes a value error, so the channel stays empty")
        assertFalse(value.path("referenced").isEmpty, "…which is a coupling to polarity, not an empty message")
    }

    // ---- acceptance case 4: determinism -----------------------------------------------------------

    /**
     * Acceptance case 4 — two runs over identical input produce byte-identical artifacts, which is what lets a
     * consumer pin one by SHA-256. Compared as bytes rather than as parsed trees: a consumer pins the file.
     */
    @Test
    fun twoRunsOverIdenticalInputProduceByteIdenticalArtifacts() {
        polarityArtifact   // force the first run, whichever test order this method lands in
        val repeat = dir.resolve("polarity-repeat.json")
        KernelRuntimeProbe.main(arrayOf(polarityRequest.toString(), repeat.toString()))

        assertEquals(
            Files.readString(dir.resolve("polarity-artifact.json")), Files.readString(repeat),
            "the artifact is a function of its input alone — no clock reading, no emission order, no platform " +
                "line separator",
        )
    }

    // ---- the locale the request may pin ----------------------------------------------------------

    /**
     * The request's `locale` is not a convenience, and this is the measurement that says so: the kernel refuses
     * a locale the model has not DECLARED, outright. A probe pinned at `en_US` would therefore be unable to
     * observe a German-only model at all — not merely report English text — which is why the key exists.
     *
     * Both directions are asserted, so neither half can rot into a vacuous pass: `en_US` on a `de_DE`-only model
     * fails and names the locale, and the same model observed at its declared `de_DE` produces the firing.
     */
    @Test
    fun theRequestPinsAnEvaluationLocaleBecauseTheKernelRefusesAnUndeclaredOne() {
        val refused = assertThrows(IllegalArgumentException::class.java) {
            KernelRuntimeProbe.main(
                arrayOf(germanRequest("").toString(), dir.resolve("de-default.json").toString()),
            )
        }
        assertTrue(
            "en_US" in refused.message.orEmpty() && "not supported" in refused.message.orEmpty(),
            "the kernel's own refusal must surface, naming the locale: ${refused.message}",
        )

        val artifact = probe(germanRequest(""" "locale": "de_DE", """), "de-declared.json")
        assertEquals("de_DE", artifact.path("request").path("locale").asText(), "the artifact states the locale")
        assertEquals(
            listOf("POLVALUE"),
            artifact.rowNamed("filled").anchor().path("messages").map { it.path("code").asText() },
            "and the observation really is made at the declared locale, rather than merely not failing",
        )
    }

    // ---- acceptance case 5: a statically refused model refuses ------------------------------------

    /**
     * Acceptance case 5 — a model the kernel's consistency oracle rejects fails the run WITH its diagnostics,
     * and writes nothing. The alternative an implementation falls into by accident is worse than a crash: an
     * inconsistent model evaluates to no messages at all, and an empty observation reads as "nothing fired".
     */
    @Test
    fun aStaticallyRefusedModelIsARefusalCarryingItsDiagnostics() {
        val out = dir.resolve("refused-artifact.json")
        val refused = assertThrows(KernelRuntimeProbe.Refused::class.java) {
            KernelRuntimeProbe.main(arrayOf(refusedRequest.toString(), out.toString()))
        }

        assertTrue(
            refused.diagnostics().any { "MVK_ERROR_FIELD_NOT_REFERENCED" in it },
            "the refusal must carry the kernel's own diagnostics, not merely report failure: ${refused.diagnostics()}",
        )
        assertFalse(Files.exists(out), "a refused run must leave no artifact that could be pinned as evidence")
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private fun JsonNode.rowNamed(name: String): JsonNode =
        path("rows").single { it.path("name").asText() == name }

    /** The production-normative block: dynamic Groovy is the anchor, generated Java the co-evidence (IF136). */
    private fun JsonNode.anchor(): JsonNode =
        path("engines").single { it.path("engine").asText() == KernelRuntimeProbe.GROOVY_DYNAMIC }

    /** `code → the complete sorted referenced set`, read off one engine block. */
    private fun JsonNode.referencedByCode(): Map<String, List<String>> =
        path("messages").associate { message ->
            message.path("code").asText() to message.path("referenced").map { it.asText() }.sorted()
        }

    companion object {

        private val MAPPER = ObjectMapper()

        private const val COORD_UNSTARRED = "COORDUNSTAR"
        private const val COORD_STARRED = "COORDSTAR"
        private const val POLARITY_OMISSION = "POLOMISSION"
        private const val POLARITY_VALUE = "POLVALUE"

        /** A star over the GROUP — KF183's measured witness, now authored typed (`AggregateOperand`, DG32). */
        private fun groupStarSum(m: BoundModel) = Dsl.sum(m.flattenGroup("/Order/Items*"))

        @JvmStatic
        @TempDir
        lateinit var dir: Path

        /** An e-commerce order: a nonrepeatable billing group with a deeper nonrepeatable one below it (so
         *  recursive and direct-child expansion differ), and a repeatable line-item group for the star. */
        private val coordinateModel: String by lazy {
            val base = DocumentModelDraft.named("probe-coordinates").locale("en_US")
                .rootGroup("Order") { g ->
                    g.stringField("Reference") { }
                    g.group("Billing") { b ->
                        b.stringField("Street") { }
                        b.stringField("City") { }
                        b.group("Contact") { c -> c.stringField("Email") { } }
                    }
                    g.group("Items") { i -> i.repeatable(10).numberField("Count") { it.fractionDigits(0, 0) } }
                }.build().toJson()
            val m = BoundModel.ofJson(base)
            listOf(
                Triple(COORD_UNSTARRED, render(m.group("/Order/Billing").filled()), "../Billing/Street"),
                Triple(
                    COORD_STARRED,
                    render(m.str("/Order/Billing/Street").filled().and(groupStarSum(m).gt(0))),
                    "../Billing/Street",
                ),
            ).fold(base) { json, (code, condition, errorField) ->
                RuntimeLaws.withRule(json, "/Order", "R$code", errorField, code, condition)
            }
        }

        /** A second order model whose two rules fire with opposite polarity on one document. */
        private val polarityModel: String by lazy {
            val base = DocumentModelDraft.named("probe-polarity").locale("en_US")
                .rootGroup("Order") { g ->
                    g.numberField("Quantity") { it.fractionDigits(0, 0) }
                    g.numberField("BackorderQuantity") { it.fractionDigits(0, 0) }
                }.build().toJson()
            val m = BoundModel.ofJson(base)
            listOf(
                // Quantity filled (the value branch holds) with BackorderQuantity present but empty (reads as a
                // fillable 0, so the omission branch holds) — the shape that types the firing OMISSION.
                Triple(
                    POLARITY_OMISSION,
                    render(m.num("/Order/Quantity").gt(5).and(m.num("/Order/BackorderQuantity").ge(0))),
                    "../Quantity",
                ),
                Triple(POLARITY_VALUE, render(m.num("/Order/Quantity").lt(100)), "../Quantity"),
            ).fold(base) { json, (code, condition, errorField) ->
                RuntimeLaws.withRule(json, "/Order", "R$code", errorField, code, condition)
            }
        }

        /**
         * The polarity model's German twin: it declares `de_DE` and **only** `de_DE`, so `en_US` is a locale
         * this model does not support. Its condition keywords stay English — the keyword language is
         * independent of the display locale, and A12 persists English keywords either way.
         */
        private val germanModel: String by lazy {
            val base = DocumentModelDraft.named("probe-german").locale("de_DE")
                .rootGroup("Order") { g -> g.numberField("Quantity") { it.fractionDigits(0, 0) } }
                .build().toJson()
            RuntimeLaws.withRule(
                base, "/Order", "R$POLARITY_VALUE", "../Quantity", POLARITY_VALUE,
                render(BoundModel.ofJson(base).num("/Order/Quantity").lt(100)),
            )
        }

        /** `localeKey` is either empty (take the default) or a complete `"locale": "…",` member. */
        private fun germanRequest(localeKey: String): Path {
            val model = write("german.model.json", germanModel).fileName
            return write(
                if (localeKey.isEmpty()) "german-default.request.json" else "german-declared.request.json",
                """{ "model": "$model", $localeKey
                     "rows": [ { "name": "filled",
                                 "documentInline": { "Order": { "Quantity": 50 } } } ] }""",
            )
        }

        /** The kernel refuses a rule whose error field its condition never references — a static ERROR. */
        private val refusedModel: String by lazy {
            val base = DocumentModelDraft.named("probe-refused").locale("en_US")
                .rootGroup("Order") { g ->
                    g.stringField("Reference") { }
                    g.numberField("Quantity") { it.fractionDigits(0, 0) }
                }.build().toJson()
            RuntimeLaws.withRule(
                base, "/Order", "RUNREFERENCED", "../Quantity", "UNREF",
                render(BoundModel.ofJson(base).str("/Order/Reference").filled()),
            )
        }

        private fun render(condition: Condition): String = DslRenderer().render(condition)

        private fun write(name: String, content: String): Path =
            Files.writeString(dir.resolve(name), content)

        private fun request(name: String, model: String, rows: String): Path =
            write(name, """{ "model": "${write("$name.model.json", model).fileName}", "rows": [ $rows ] }""")

        private val coordinateRequest: Path by lazy {
            request(
                "coordinates", coordinateModel,
                """{ "name": "billing-filled", "documentInline": {
                       "Order": {
                         "Reference": "ORD-7",
                         "Billing": { "Street": "12 Kingsway", "City": "Leeds",
                                      "Contact": { "Email": "buyer@example.com" } },
                         "Items": [ { "Count": 3 } ]
                       } } }""",
            )
        }

        private val polarityRequest: Path by lazy {
            request(
                "polarity", polarityModel,
                """{ "name": "filled-and-unfilled",
                     "documentInline": { "Order": { "Quantity": 50, "BackorderQuantity": null } } }""",
            )
        }

        private val refusedRequest: Path by lazy {
            request(
                "refused", refusedModel,
                """{ "name": "any", "documentInline": { "Order": { "Reference": "ORD-9" } } }""",
            )
        }

        private fun probe(request: Path, out: String): JsonNode {
            KernelRuntimeProbe.main(arrayOf(request.toString(), dir.resolve(out).toString()))
            return MAPPER.readTree(Files.readString(dir.resolve(out)))
        }

        private val coordinateArtifact: JsonNode by lazy {
            probe(coordinateRequest, "coordinates-artifact.json")
        }

        private val polarityArtifact: JsonNode by lazy { probe(polarityRequest, "polarity-artifact.json") }
    }
}
