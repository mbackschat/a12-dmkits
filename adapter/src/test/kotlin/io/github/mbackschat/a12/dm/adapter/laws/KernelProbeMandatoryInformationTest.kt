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
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [KernelRuntimeProbe]'s kernel-backed acceptance for the `mandatoryInformation` observation — the kernel's
 * own derivation of which fields and root groups a rule collection makes mandatory
 * (`IRuleCollectionMetaInformation.getMandatoryInformationFromRules`), reached through a request file.
 *
 * **Why the probe grew a third observation.** a12-kernel-lean's `EXP-2026-08-30-01` needs to compare the
 * derived sets across two models differing in one numeric literal, to settle whether a literal that narrows to
 * the kernel's absent-value sentinel silently drops a rule from the derivation. That is a MODEL-and-rule query:
 * it reads rule conditions, not a document, so neither `validateFull` nor `compute` can expose it and no
 * document row can carry it. It is therefore the probe's first model-level observation, reported once for the
 * request rather than per row or per strategy, and a request may ask for it with no `rows` at all.
 *
 * **Read-only, and it adds no evaluation surface.** The derivation runs over the already-loaded rule
 * collection; the kernel types stay inside `KernelAdapter.mandatoryInformation`, which returns plain
 * `Set<String>` — so nothing about dmtool's kernel boundary or its native build changes.
 *
 * Own domain: an insurance claim. The four rules are chosen so each derived set is reached by a different
 * condition shape and one rule reaches none of them.
 */
class KernelProbeMandatoryInformationTest {

    /**
     * All three sets from one model, pinned exactly. The negative control is `AmountCap`: it references
     * `Amount` in an ordinary comparison, so a derivation that merely collected referenced fields would list it
     * and this assertion would fail.
     *
     * **Two things here were predicted wrong and are recorded as measured, not explained.** The
     * `GroupFilled ∧ FieldNotFilled` shape the kernel's own documentation offers for "mandatory regarding its
     * root group" puts `Adjuster/Name` in **both** field sets rather than only the second — consistent with
     * that documentation's other note, that a mandatory field is also mandatory regarding its root group, but
     * this model does not separate the two readings and no claim about which is made here. And
     * `GroupNotFilled(/Claim/Adjuster)` yields the ROOT group `Claim`, not the named nested group.
     *
     * **The two channels also spell paths differently** — fields carry a leading slash, the root group does
     * not. Pinned because a consumer joining these names against model paths has to know.
     */
    @Test
    fun theDerivationReportsAllThreeSetsAndOnlyPresenceConditionsReachThem() {
        assertEquals(
            mapOf(
                "mandatory" to listOf("/Claim/Adjuster/Name", "/Claim/PolicyNumber"),
                "mandatoryForRootGroup" to listOf("/Claim/Adjuster/Name", "/Claim/PolicyNumber"),
                "mandatoryRootGroups" to listOf("Claim"),
            ),
            artifact.mandatorySets(),
            "Amount appears nowhere though two rules read it, so the derivation reads condition SHAPE and not " +
                "the set of referenced fields",
        )
    }

    /**
     * The starred negative control, stated as its own case because it is the rule the kernel's own
     * documentation singles out: `NoFieldFilled` over the flatten of a starred group does **not** make that
     * field mandatory, since it does not hold for every repetition. It is in the model above, so the assertion
     * there already depends on this — this test says so where a reader will look for it.
     */
    @Test
    fun aStarredNegativeQuantifierMakesNothingMandatory() {
        assertTrue(
            artifact.mandatorySets().values.none { names -> names.any { "Attachments" in it } },
            "no Attachments path appears in any set: ${artifact.mandatorySets()}",
        )
    }

    /**
     * **A count shape names no FIELD, reaching only `mandatoryRootGroups`, and only in the `<` direction.**
     * Measured after a12-kernel-lean reported both facts from their own `EXP-2026-08-30-01` run; re-derived here
     * rather than carried on their report, because it is our capability's documentation that would otherwise
     * imply a count shape behaves like a presence one.
     *
     * **The pair varies direction and nothing else.** `< 2` and `>= 2` over a two-field count share the
     * literal, and both are satisfiable and non-trivial there — `< 2` is false at two filled, `>= 2` is false
     * at none. Comparing `< 3` against `>= 3` would have moved satisfiability too, since `>= 3` cannot hold
     * over two fields, and an empty result would then have had two explanations.
     *
     * **The presence control is what makes the count row readable, and it corrected a prediction.** A single
     * `FieldNotFilled` rule reaches all three sets, root group included — so `mandatoryRootGroups` is not a
     * count-shape peculiarity, and the count row's real content is the two EMPTY field sets beside a root group
     * the kernel still reports. It knows the group cannot be left alone; it cannot name which field settles it.
     */
    @Test
    fun aCountShapeNamesNoFieldAndReachesTheRootGroupOnlyInTheLessThanDirection() {
        assertEquals(
            mapOf(
                "NumberOfFilledFields(…) < 2" to mapOf(
                    "mandatory" to emptyList(),
                    "mandatoryForRootGroup" to emptyList(),
                    "mandatoryRootGroups" to listOf("Claim"),
                ),
                "NumberOfFilledFields(…) >= 2" to mapOf(
                    "mandatory" to emptyList<String>(),
                    "mandatoryForRootGroup" to emptyList(),
                    "mandatoryRootGroups" to emptyList(),
                ),
                "FieldNotFilled(…) — live control" to mapOf(
                    "mandatory" to listOf("/Claim/PolicyNumber"),
                    "mandatoryForRootGroup" to listOf("/Claim/PolicyNumber"),
                    "mandatoryRootGroups" to listOf("Claim"),
                ),
            ),
            singleRuleModels.mapValues { (_, artifact) -> artifact.mandatorySets() },
            "a count shape contributes no FIELD at all, and reverses to nothing when the comparison does",
        )
    }

    /** A model-level request needs no document at all — the whole point of the capability. */
    @Test
    fun aModelLevelRequestCarriesNoRowsAndStillObserves() {
        assertEquals(0, artifact.path("rows").size(), "no row was requested, so none was observed")
        assertTrue(artifact.path("rows").isArray, "…and `rows` is still present as an empty array (schema v1)")
        assertTrue(artifact.path("mandatoryInformation").isObject, "the model-level block is what carries it")
    }

    /** Two runs over one request are byte-identical, so a consumer can pin a derivation by SHA-256. */
    @Test
    fun twoRunsProduceByteIdenticalArtifacts() {
        artifact
        val repeat = dir.resolve("mandatory-repeat.json")
        KernelRuntimeProbe.main(arrayOf(mandatoryRequest.toString(), repeat.toString()))

        assertEquals(
            Files.readString(dir.resolve("mandatory-artifact.json")), Files.readString(repeat),
            "the derivation is a function of the model alone",
        )
    }

    /** The artifact conforms to the published schema, so the schema cannot drift from the producer. */
    @Test
    fun aModelLevelArtifactConformsToThePublishedSchema() {
        val schemaNode = MAPPER.readTree(
            KernelProbeMandatoryInformationTest::class.java.classLoader
                .getResource("kernel-probe/artifact.schema.json")!!.readText(),
        )
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode)
        assertEquals(emptySet<Any>(), schema.validate(artifact), "the real model-level artifact must conform")
    }

    /**
     * A row nobody observes is refused, which is the same rule the `validateFull` requirement enforces from the
     * other side: the artifact carries no key saying a channel went unobserved, so a silently unread row would
     * be indistinguishable from one where nothing fired.
     */
    @Test
    fun aRowSuppliedToAModelLevelOnlyRequestIsRefused() {
        val bad = request(
            "mandatory-with-row", model,
            """{ "name": "any", "documentInline": { "Claim": { "PolicyNumber": "P-1" } } }""",
            observe = MANDATORY_ONLY,
        )
        val refused = assertThrows(IllegalArgumentException::class.java) {
            KernelRuntimeProbe.main(arrayOf(bad.toString(), dir.resolve("never.json").toString()))
        }
        assertTrue(
            "model-level" in refused.message.orEmpty(),
            "the refusal says why rather than only that something is wrong: ${refused.message}",
        )
    }

    /**
     * The published request schema refuses exactly what the producer refuses. Two guards is not redundancy:
     * an agent reads the schema before it ever runs the probe, so a schema that accepts a request the producer
     * rejects teaches the wrong shape, and one that rejects a request the producer accepts hides the
     * capability outright.
     */
    @Test
    fun theSchemaAndTheProducerAgreeOnTheModelLevelRules() {
        val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(
                MAPPER.readTree(
                    KernelProbeMandatoryInformationTest::class.java.classLoader
                        .getResource("kernel-probe/request.schema.json")!!.readText(),
                ),
            )
        val row = """{ "name": "r", "documentInline": {} }"""

        fun json(body: String) = MAPPER.readTree("""{ "model": "m.dm.json", $body }""")

        assertEquals(
            emptySet<Any>(),
            schema.validate(json(""" "observe": ["mandatoryInformation"] """)),
            "a model-level-only request with no rows is the shape the capability exists for",
        )
        assertTrue(
            schema.validate(json(""" "observe": ["mandatoryInformation"], "rows": [ $row ] """)).isNotEmpty(),
            "…and a row nothing observes is refused by the schema, as the producer refuses it",
        )
        assertTrue(
            schema.validate(json(""" "observe": ["compute"], "rows": [ $row ] """)).isNotEmpty(),
            "the pre-existing rule survives the conditional rewrite: a per-row request still needs validateFull",
        )
        assertTrue(
            schema.validate(json(""" "observe": ["validateFull", "mandatoryInformation"] """)).isNotEmpty(),
            "…and mixing the two still needs rows, since a per-row observation was asked for",
        )
    }

    /** A request that does not ask for it carries no such key, which is what keeps pinned artifacts valid. */
    @Test
    fun aRequestThatDoesNotAskForItProducesNoSuchKey() {
        val rowsOnly = probe(
            request(
                "rows-only", model,
                """{ "name": "any", "documentInline": { "Claim": { "PolicyNumber": "P-1" } } }""",
            ),
            "rows-only-artifact.json",
        )
        assertEquals(1, rowsOnly.path("rows").size(), "the positive control that anything was observed")
        assertTrue(
            rowsOnly.path("mandatoryInformation").isMissingNode,
            "not observed is reported by ABSENCE, never by an empty block",
        )
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private fun JsonNode.mandatorySets(): Map<String, List<String>> {
        val block = path("mandatoryInformation")
        return block.fieldNames().asSequence().associateWith { key ->
            block.path(key).map { it.asText() }
        }
    }

    companion object {

        private val MAPPER = ObjectMapper()

        @JvmStatic
        @TempDir
        lateinit var dir: Path

        /**
         * Four rules on one claim model. `PolicyRequired` and `AdjusterRequired` reach two different sets;
         * `AdjusterNamed` reaches the third by pairing the group's presence with the field's absence, which is
         * the kernel's own documented shape for "mandatory regarding its root group"; `AttachmentReferences`
         * and `AmountCap` are the two negative controls.
         */
        /** The rule-free claim model, shared by the five-rule fixture and the single-rule shape probes. */
        private val claimModel: String by lazy {
            DocumentModelDraft.named("probe-mandatory").locale("en_US")
                .rootGroup("Claim") { claim ->
                    claim.field("PolicyNumber", FieldKind.STRING)
                        .numberField("Amount") { it.fractionDigits(2, 2) }
                        .group("Adjuster") { adjuster ->
                            adjuster.field("Name", FieldKind.STRING).field("Phone", FieldKind.STRING)
                        }
                        .group("Attachments") { attachments ->
                            attachments.repeatable(4)
                                .field("Reference", FieldKind.STRING)
                                .field("Kind", FieldKind.STRING)
                        }
                }.build().toJson()
        }

        private val model: String by lazy {
            val base = BoundModel.ofJson(claimModel)
            val policyNumber = base.field("/Claim/PolicyNumber")
            val adjuster = base.group("/Claim/Adjuster")
            val adjusterName = base.field("/Claim/Adjuster/Name")
            base.addAll(
                listOf(
                    rule(base, "PolicyRequired", policyNumber, Dsl.fieldNotFilled(policyNumber)),
                    rule(base, "AdjusterRequired", adjusterName, Dsl.groupNotFilled(adjuster)),
                    rule(
                        base, "AdjusterNamed", adjusterName,
                        Dsl.groupFilled(adjuster).and(Dsl.fieldNotFilled(adjusterName)),
                    ),
                    rule(
                        base, "AttachmentReferences", base.field("/Claim/Amount"),
                        Dsl.noFieldFilled(base.flatten("/Claim/Attachments*/Reference"))
                            .and(base.num("/Claim/Amount").gt(0)),
                    ),
                    rule(base, "AmountCap", base.field("/Claim/Amount"), base.num("/Claim/Amount").gt(1000)),
                ),
                emptyList(),
            ).toJson()
        }

        private fun rule(
            base: BoundModel,
            name: String,
            errorField: io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef,
            condition: Condition,
        ): Rule = base.rule(base.group("/Claim")).name(name).errorField(errorField).`when`(condition)
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

        /** The one observation this request asks for, as the JSON array element the request helper splices. */
        private const val MANDATORY_ONLY = "\"mandatoryInformation\""

        private val mandatoryRequest: Path by lazy {
            request("mandatory", model, observe = MANDATORY_ONLY)
        }

        private fun probe(request: Path, out: String): JsonNode {
            KernelRuntimeProbe.main(arrayOf(request.toString(), dir.resolve(out).toString()))
            return MAPPER.readTree(Files.readString(dir.resolve(out)))
        }

        private val artifact: JsonNode by lazy { probe(mandatoryRequest, "mandatory-artifact.json") }

        /**
         * One model per rule, so a set that stays empty names the rule that failed to reach it. A single model
         * carrying all three would report their union and could not attribute anything.
         */
        private val singleRuleModels: Map<String, JsonNode> by lazy {
            val base = BoundModel.ofJson(claimModel)
            val counted = listOf(base.field("/Claim/PolicyNumber"), base.field("/Claim/Amount"))
            mapOf(
                "NumberOfFilledFields(…) < 2" to Dsl.numberOfFilledFields(counted).lt(2),
                "NumberOfFilledFields(…) >= 2" to Dsl.numberOfFilledFields(counted).ge(2),
                "FieldNotFilled(…) — live control" to Dsl.fieldNotFilled(base.field("/Claim/PolicyNumber")),
            ).entries.withIndex().associate { (index, entry) ->
                val model = base.add(
                    rule(base, "Shape$index", base.field("/Claim/PolicyNumber"), entry.value),
                ).toJson()
                entry.key to probe(
                    request("shape-$index", model, observe = MANDATORY_ONLY), "shape-$index-artifact.json",
                )
            }
        }
    }
}
