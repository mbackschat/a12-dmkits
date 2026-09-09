package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [KernelRuntimeProbe]'s kernel-backed acceptance for the `dynamicEnumerations` observation — the kernel's
 * dynamic-enumeration derivation over a rule collection
 * (`IRuleCollectionMetaInformation.getDynamicEnumerationStringValues`), reached through a request file.
 *
 * **Why the probe grew a fourth observation.** `DynamicEnumerationService`, whose `DynEnumErrorType` declares
 * twelve classes, is constructed at two sites in the 30.8.1 checkout — that facade method, and
 * `ServiceFactory.getDynamicEnumerationService(…)`, which has no in-kernel caller at all — and neither sits on
 * a `DocumentModelConsistencyRule`. So no `model check` / `rule check` / `--dry-run` route can produce any of
 * the twelve however the model is shaped, and the derivation's field→values output has no route at all.
 * Reported to us by a12-kernel-lean, 2026-09-07, who suggested exactly this shape; the class grid and the
 * per-rule attribution are locked in `RuleCollectionDerivationDiagnosticLawsTest`, and the measurement is
 * KERNEL-FINDINGS KF277.
 *
 * **The block's diagnostics are not a defect list**, which is the misreading this capability invites: the
 * derivation checks every rule and reports one problem per rule that is not a dynamic enumeration IN FORM.
 * [aRuleThatIsNotADynamicEnumerationIsReportedBesideOneThatIs] is the shape that says so — the same artifact
 * carries an extracted enumeration and a diagnostic, and neither is a fault.
 *
 * Own domain: an e-commerce catalogue whose service tier is enumerated by rule rather than by a declared
 * enumeration.
 */
class KernelProbeDynamicEnumerationsTest {

    /**
     * The canonical form's two halves in one artifact: `valuesByField` names the target field and the constants
     * the kernel extracted for it, in condition order; `diagnostics` is empty because this rule IS a dynamic
     * enumeration. This is the positive control the whole capability rests on — without a rule the derivation
     * accepts, "one problem per non-dyn-enum rule" and "the derivation errors on everything" are
     * indistinguishable.
     */
    @Test
    fun theCanonicalFormYieldsItsExtractedConstantsAndNoDiagnostic() {
        assertEquals(
            mapOf("/Catalogue/Tier" to listOf("STANDARD", "EXPRESS")),
            canonical.valuesByField(),
            "both calculation operations contribute, in the order the condition names them",
        )
        assertEquals(emptyList<String>(), canonical.diagnosticCodes())
    }

    /**
     * One rule the derivation accepts and one it does not, in ONE model — a12-kernel-lean's acceptance
     * condition verbatim. The artifact carries both channels at once, which is the fact a consumer has to
     * understand before reading `diagnostics`: the extracted enumeration is unaffected by the other rule's
     * class, and that class means "not a dynamic enumeration", not "malformed rule".
     */
    @Test
    fun aRuleThatIsNotADynamicEnumerationIsReportedBesideOneThatIs() {
        assertEquals(
            mapOf("/Catalogue/Tier" to listOf("STANDARD", "EXPRESS")),
            mixed.valuesByField(),
            "the accepted rule's values survive the other rule's diagnostic",
        )
        assertEquals(listOf("MVK_DYNENUM_INVALID_GATE"), mixed.diagnosticCodes())
    }

    /**
     * The collection-wide call leaves `rule` null, because the kernel's own problem carries a rule only where
     * its message names one. Pinned because a consumer reading the artifact needs to know the attribution is
     * absent here rather than empty by accident — the attributable question is
     * `KernelAdapter.dynamicEnumerationDiagnostics(ruleFullName)`, which asks about one rule.
     */
    @Test
    fun theArtifactsDiagnosticCarriesTheKernelsOwnAttributionWhichNamesNoRule() {
        val diagnostic = mixed.path("dynamicEnumerations").path("diagnostics").first()
        assertEquals("ERROR", diagnostic.path("severity").asText())
        assertTrue(diagnostic.path("rule").isNull, "the kernel's own message names no rule: $diagnostic")
        assertTrue(
            "canonical form" in diagnostic.path("message").asText(),
            "the kernel's own text rides through verbatim: ${diagnostic.path("message").asText()}",
        )
    }

    /** A model-level request needs no document at all — the whole point of the capability. */
    @Test
    fun aModelLevelRequestCarriesNoRowsAndStillObserves() {
        assertEquals(0, canonical.path("rows").size(), "no row was requested, so none was observed")
        assertTrue(canonical.path("rows").isArray, "…and `rows` is still present as an empty array (schema v1)")
        assertTrue(canonical.path("dynamicEnumerations").isObject, "the model-level block carries it")
    }

    /** A request that does not ask for it carries no such key, which is what keeps pinned artifacts valid. */
    @Test
    fun aRequestThatDoesNotAskForItProducesNoSuchKey() {
        val rowsOnly = probe(
            request(
                "dynenum-rows-only", canonicalModel,
                """{ "name": "any", "documentInline": { "Catalogue": { "Tier": "STANDARD" } } }""",
            ),
            "dynenum-rows-only-artifact.json",
        )
        assertEquals(1, rowsOnly.path("rows").size(), "the positive control that anything was observed")
        assertTrue(
            rowsOnly.path("dynamicEnumerations").isMissingNode,
            "not observed is reported by ABSENCE, never by an empty block",
        )
    }

    /** Two runs over one request are byte-identical, so a consumer can pin a derivation by SHA-256. */
    @Test
    fun twoRunsProduceByteIdenticalArtifacts() {
        canonical
        val repeat = dir.resolve("dynenum-repeat.json")
        KernelRuntimeProbe.main(arrayOf(canonicalRequest.toString(), repeat.toString()))

        assertEquals(
            Files.readString(dir.resolve("dynenum-artifact.json")), Files.readString(repeat),
            "the derivation is a function of the model alone",
        )
    }

    /** The artifact conforms to the published schema, so the schema cannot drift from the producer. */
    @Test
    fun aDynamicEnumerationArtifactConformsToThePublishedSchema() {
        assertEquals(emptySet<Any>(), publishedSchema("artifact").validate(mixed), "the real artifact conforms")
    }

    /**
     * The published request schema accepts the new observation on the same model-level terms the producer
     * does. An agent reads the schema before it ever runs the probe, so a schema that omitted the entry would
     * hide the capability outright and one that let it carry rows would teach a shape the producer refuses.
     */
    @Test
    fun theSchemaAdmitsTheNewObservationOnTheSameModelLevelTermsAsItsSibling() {
        val schema = publishedSchema("request")
        fun json(body: String) = MAPPER.readTree("""{ "model": "m.dm.json", $body }""")

        assertEquals(
            emptySet<Any>(),
            schema.validate(json(""" "observe": ["dynamicEnumerations"] """)),
            "a model-level-only request with no rows is the shape the capability exists for",
        )
        assertEquals(
            emptySet<Any>(),
            schema.validate(json(""" "observe": ["mandatoryInformation", "dynamicEnumerations"] """)),
            "…and two model-level observations in one request stay model-level",
        )
        assertTrue(
            schema.validate(
                json(""" "observe": ["dynamicEnumerations"], "rows": [ { "name": "r", "documentInline": {} } ] """),
            ).isNotEmpty(),
            "a row nothing observes is refused by the schema, as the producer refuses it",
        )
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private fun JsonNode.valuesByField(): Map<String, List<String>> {
        val values = path("dynamicEnumerations").path("valuesByField")
        return values.fieldNames().asSequence().associateWith { key -> values.path(key).map { it.asText() } }
    }

    private fun JsonNode.diagnosticCodes(): List<String> =
        path("dynamicEnumerations").path("diagnostics").map { it.path("code").asText() }

    companion object {

        private val MAPPER = ObjectMapper()

        @JvmStatic
        @TempDir
        lateinit var dir: Path

        /**
         * The rule-free catalogue. `Tier` is an unrestricted STRING because the dyn-enum shape admits only
         * that, an enumeration, or a NUMBER with no fractional digits and no bounds — a length or a pattern on
         * the target field is itself `MVK_DYNENUM_INVALID_TYPE`.
         */
        private val catalogue: String by lazy {
            DocumentModelDraft.named("probe-dynenum").locale("en_US")
                .rootGroup("Catalogue") { catalogue ->
                    catalogue.field("Tier", FieldKind.STRING).field("Region", FieldKind.STRING)
                }.build().toJson()
        }

        /**
         * `FieldFilled(Tier) And [Tier] != "STANDARD" And [Tier] != "EXPRESS"` — the canonical form: the gate
         * naming the rule's own error field, then one calculation operation per enumerated constant. The right
         * side of each comparison must be a constant, since with no safe guard the set of secured operands is
         * empty and any field reference there is `MVK_DYNENUM_INVALID_FIELD_REFERENCE`.
         */
        private val canonicalModel: String by lazy {
            val base = BoundModel.ofJson(catalogue)
            val tier = base.field("/Catalogue/Tier")
            base.add(
                rule(
                    base, "TierEnum", tier,
                    Dsl.fieldFilled(tier)
                        .and(base.str("/Catalogue/Tier").ne("STANDARD"))
                        .and(base.str("/Catalogue/Tier").ne("EXPRESS")),
                ),
            ).toJson()
        }

        /** The canonical rule plus an ordinary presence rule, which is not a dynamic enumeration in form. */
        private val mixedModel: String by lazy {
            val base = BoundModel.ofJson(canonicalModel)
            val region = base.field("/Catalogue/Region")
            base.add(rule(base, "RegionGiven", region, Dsl.fieldFilled(region))).toJson()
        }

        private fun rule(base: BoundModel, name: String, errorField: FieldRef, condition: Condition): Rule =
            base.rule(base.group("/Catalogue")).name(name).errorField(errorField).`when`(condition)
                .code(name).severity(Severity.ERROR).message(Dsl.en("x")).build()

        private fun write(name: String, content: String): Path = Files.writeString(dir.resolve(name), content)

        /** Assembled key by key rather than by string splicing, so an omitted `rows` cannot leave a comma. */
        private fun request(name: String, model: String, rows: String? = null, observe: String? = null): Path {
            val keys = buildList {
                add(""" "model": "${write("$name.model.json", model).fileName}" """)
                if (observe != null) add(""" "observe": [ $observe ] """)
                if (rows != null) add(""" "rows": [ $rows ] """)
            }
            return write("$name.request.json", keys.joinToString(",", "{", "}"))
        }

        private const val DYNENUM_ONLY = "\"dynamicEnumerations\""

        private val canonicalRequest: Path by lazy {
            request("dynenum", canonicalModel, observe = DYNENUM_ONLY)
        }

        private fun probe(request: Path, out: String): JsonNode {
            KernelRuntimeProbe.main(arrayOf(request.toString(), dir.resolve(out).toString()))
            return MAPPER.readTree(Files.readString(dir.resolve(out)))
        }

        private fun publishedSchema(which: String) = JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(
                MAPPER.readTree(
                    KernelProbeDynamicEnumerationsTest::class.java.classLoader
                        .getResource("kernel-probe/$which.schema.json")!!.readText(),
                ),
            )

        private val canonical: JsonNode by lazy { probe(canonicalRequest, "dynenum-artifact.json") }

        private val mixed: JsonNode by lazy {
            probe(request("dynenum-mixed", mixedModel, observe = DYNENUM_ONLY), "dynenum-mixed-artifact.json")
        }
    }
}
