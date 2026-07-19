package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass

/**
 * The CAPABILITY × STRATEGY EVIDENCE MATRIX (IF140, graduated from IG94).
 *
 * Operators are derived from the live catalog and accounted for one cell at a time. The remaining axes use the
 * independently declared [CapabilityId] universe, so deleting a row or adding an unclassified capability fails.
 * Coverage is tagged by cadence and runtime lane, and the certificate fragment is generated from these cells.
 *
 * Evidence names a test by [KClass], so deleting or renaming that class breaks compilation. [Static.Tri] means
 * the named test executes interpreter == Groovy-dynamic == generated Java. [Coverage.Excluded] is outside that
 * comparison and never inflates the strategy-evidence count.
 */
class CapabilityEvidenceMatrixTest {

    private enum class Axis { OPERATOR, FORMAL, COMPUTATION, CONFIG, CUSTOMIZATION, PLATFORM, ASSEMBLY }
    private enum class Cadence { CI, OPT_IN }
    private enum class RuntimeLane { JVM_ONLY, JVM_AND_JS }
    private data class CoverageTier(val cadence: Cadence, val runtime: RuntimeLane)

    private val ciJvm = CoverageTier(Cadence.CI, RuntimeLane.JVM_ONLY)
    private val ciJvmAndJs = CoverageTier(Cadence.CI, RuntimeLane.JVM_AND_JS)
    private val optInJvm = CoverageTier(Cadence.OPT_IN, RuntimeLane.JVM_ONLY)

    /** The independently declared non-operator capability universe. */
    private enum class CapabilityId(val axis: Axis, val title: String) {
        FORMAL_LENGTH(Axis.FORMAL, "length (min/max, UTF-16 units)"),
        FORMAL_PATTERN(Axis.FORMAL, "pattern (declared + predefined)"),
        FORMAL_VALUE_BOUNDS(Axis.FORMAL, "value bounds (min/max value)"),
        FORMAL_NUMBER_SHAPE(Axis.FORMAL, "scale / sign / precision"),
        FORMAL_LEGAL_CHARSET_RUNTIME(Axis.FORMAL, "legal charset matching (BMP / DIN-91379 / graphemes)"),
        FORMAL_LEGAL_CHARSET_DEFINITION(Axis.FORMAL, "legal charset definition boundary"),
        FORMAL_RAW_TYPE(Axis.FORMAL, "raw-type (noValueValidation)"),

        COMPUTATION_TIERED(Axis.COMPUTATION, "tiered / precondition-guarded"),
        COMPUTATION_CASCADE(Axis.COMPUTATION, "cascade (dependency order)"),
        COMPUTATION_MULTI_TARGET(Axis.COMPUTATION, "multiple computations for one target"),
        COMPUTATION_CROSS_ROOT(Axis.COMPUTATION, "cross-root target and pointer"),
        COMPUTATION_TOLERANCE(Axis.COMPUTATION, "tolerance comparison"),

        CONFIG_TIME_ZONE(Axis.CONFIG, "timeZone (datetime arithmetic)"),
        CONFIG_LOCALE(Axis.CONFIG, "locale (message language)"),

        CUSTOM_CONDITION(Axis.CUSTOMIZATION, "CustomCondition SPI"),
        CUSTOM_FIELD_TYPE_DISPATCH(Axis.CUSTOMIZATION, "CustomFieldType accept/reject and suppression"),
        CUSTOM_FIELD_TYPE_CONTEXT(Axis.CUSTOMIZATION, "CustomFieldType locale/bounds/stored-mode context"),
        CUSTOM_FIELD_TYPE_REJECTION(Axis.CUSTOMIZATION, "CustomFieldType project rejection-code identity"),
        CUSTOM_FIELD_TYPE_MESSAGE(Axis.CUSTOMIZATION, "CustomFieldType rejection-message projection"),
        CUSTOM_PREDEFINED_TYPE(Axis.CUSTOMIZATION, "predefined types"),

        PLATFORM_JVM(Axis.PLATFORM, "JVM library runtime"),
        PLATFORM_NODE(Axis.PLATFORM, "Node-hosted JS runtime"),
        PLATFORM_NATIVE(Axis.PLATFORM, "GraalVM native CLI runtime"),
        PLATFORM_BROWSER(Axis.PLATFORM, "browser host"),
        PLATFORM_UNSUPPORTED_SAFETY(Axis.PLATFORM, "multiplatform validateFull unsupported-result safety"),

        ASSEMBLY_PREPARED(Axis.ASSEMBLY, "prepared self-contained model"),
        ASSEMBLY_RAW_COMPOSITION(Axis.ASSEMBLY, "raw includes / typedefs / opaque-id composition"),
    }

    private data class Tracking(val references: Set<String>, val why: String)

    private sealed interface Static {
        data class Tri(val test: KClass<*>, val tier: CoverageTier = CoverageTier(Cadence.CI, RuntimeLane.JVM_ONLY)) : Static
    }

    private sealed interface Coverage {
        data class Evidence(
            val dynamic: KClass<*>,
            val static: Static,
            val dynamicTier: CoverageTier = CoverageTier(Cadence.CI, RuntimeLane.JVM_ONLY),
        ) : Coverage

        /**
         * The capability is outside the direct dynamic/static comparison. [lock] may prove an interpreter-only
         * invariant, but the cell remains an exclusion and cannot inflate the strategy-evidence count.
         */
        data class Excluded(
            val tracking: Tracking,
            val lock: KClass<*>? = null,
            val lockTier: CoverageTier? = null,
        ) : Coverage
    }

    private data class Capability(val id: CapabilityId, val coverage: Coverage)
    private data class OperatorCapability(val id: String, val coverage: Coverage.Evidence)
    private data class CapabilityKey(val axis: Axis, val title: String)
    private data class TierLane(val name: String, val tier: CoverageTier, val reference: String)

    private fun tracked(vararg references: String, why: String) = Tracking(references.toSet(), why)

    private val matrix: List<Capability> = listOf(
        // FORMAL
        Capability(CapabilityId.FORMAL_LENGTH, Coverage.Evidence(
            AbsLengthDiffTest::class,
            Static.Tri(AbsLengthDiffTest::class),
        )),
        Capability(CapabilityId.FORMAL_PATTERN, Coverage.Evidence(
            PatternDiffTest::class,
            Static.Tri(PatternDiffTest::class),
        )),
        Capability(CapabilityId.FORMAL_VALUE_BOUNDS, Coverage.Evidence(
            FieldConstraintFormalDiffTest::class,
            Static.Tri(FieldConstraintFormalDiffTest::class),
        )),
        Capability(CapabilityId.FORMAL_NUMBER_SHAPE, Coverage.Evidence(
            NumberSignFormalDiffTest::class,
            Static.Tri(NumberSignFormalDiffTest::class),
        )),
        Capability(CapabilityId.FORMAL_LEGAL_CHARSET_RUNTIME, Coverage.Evidence(
            SupportedCharactersGraphemeDiffTest::class,
            Static.Tri(SupportedCharactersGraphemeDiffTest::class),
        )),
        Capability(CapabilityId.FORMAL_LEGAL_CHARSET_DEFINITION, Coverage.Excluded(
            tracked("IF151", why = "charset definitions are structurally accepted or rejected before either runtime strategy executes"),
            SupportedCharactersDefinitionDiffTest::class,
            ciJvm,
        )),
        Capability(CapabilityId.FORMAL_RAW_TYPE, Coverage.Evidence(
            NvvRawTypeDiffTest::class,
            Static.Tri(NvvRawTypeDiffTest::class),
        )),

        // COMPUTATION
        Capability(CapabilityId.COMPUTATION_TIERED, Coverage.Evidence(
            ComputationGuardedValidationDiffTest::class,
            Static.Tri(ComputationGuardedValidationDiffTest::class),
        )),
        Capability(CapabilityId.COMPUTATION_CASCADE, Coverage.Evidence(
            ComputeCascadeDiffTest::class,
            Static.Tri(ComputeCascadeDiffTest::class),
        )),
        Capability(CapabilityId.COMPUTATION_MULTI_TARGET, Coverage.Evidence(
            MultiComputationFieldDiffTest::class,
            Static.Tri(MultiComputationFieldDiffTest::class),
        )),
        Capability(CapabilityId.COMPUTATION_CROSS_ROOT, Coverage.Evidence(
            MultiRootComputationPointerDiffTest::class,
            Static.Tri(MultiRootComputationPointerDiffTest::class),
        )),
        Capability(CapabilityId.COMPUTATION_TOLERANCE, Coverage.Evidence(
            ToleranceDiffTest::class,
            Static.Tri(ToleranceDiffTest::class),
        )),

        // CONFIGURATION
        Capability(CapabilityId.CONFIG_TIME_ZONE, Coverage.Evidence(
            DstTimeZoneDiffTest::class,
            Static.Tri(DstTimeZoneDiffTest::class),
        )),
        Capability(CapabilityId.CONFIG_LOCALE, Coverage.Excluded(
            tracked("IF3", why = "locale changes license-excluded message text; observable code/type/pointer are locale-independent"),
        )),

        // CUSTOMIZATION
        Capability(CapabilityId.CUSTOM_CONDITION, Coverage.Evidence(
            CustomConditionDiffTest::class,
            Static.Tri(CustomConditionDiffTest::class),
        )),
        Capability(CapabilityId.CUSTOM_FIELD_TYPE_DISPATCH, Coverage.Evidence(
            CustomFieldTypeBoundsDiffTest::class,
            Static.Tri(CustomFieldTypeBoundsDiffTest::class),
        )),
        Capability(CapabilityId.CUSTOM_FIELD_TYPE_CONTEXT, Coverage.Evidence(
            CustomFieldTypeContextDiffTest::class,
            Static.Tri(CustomFieldTypeContextDiffTest::class),
        )),
        Capability(CapabilityId.CUSTOM_FIELD_TYPE_REJECTION, Coverage.Evidence(
            CustomFieldTypeRejectionCodeDiffTest::class,
            Static.Tri(CustomFieldTypeRejectionCodeDiffTest::class),
        )),
        Capability(CapabilityId.CUSTOM_FIELD_TYPE_MESSAGE, Coverage.Evidence(
            CustomFieldTypeMessageDiffTest::class,
            Static.Tri(CustomFieldTypeMessageDiffTest::class),
        )),
        Capability(CapabilityId.CUSTOM_PREDEFINED_TYPE, Coverage.Evidence(
            PredefinedTypeValidityDiffTest::class,
            Static.Tri(PredefinedTypeValidityDiffTest::class),
        )),

        // PLATFORM — direct kernel strategy execution is JVM-only; portable locks are classified by tier below.
        Capability(CapabilityId.PLATFORM_JVM, Coverage.Evidence(
            ValidateFullDiffTest::class,
            Static.Tri(ValidateFullDiffTest::class),
        )),
        Capability(CapabilityId.PLATFORM_NODE, Coverage.Excluded(
            tracked("IF138", why = "kernel strategies execute on the JVM; Node uses the kernel-grounded portable replay tier"),
        )),
        Capability(CapabilityId.PLATFORM_NATIVE, Coverage.Excluded(
            tracked("IF122", why = "native end-to-end evidence is not a directly executable kernel-strategy comparison"),
        )),
        Capability(CapabilityId.PLATFORM_BROWSER, Coverage.Excluded(
            tracked("IF138", why = "browser support is an owner-deferred, unverified future capability"),
        )),
        Capability(CapabilityId.PLATFORM_UNSUPPORTED_SAFETY, Coverage.Excluded(
            tracked("IF139", why = "the kernel has no unsupported-result state; fail-loud behavior is an interpreter API invariant"),
            ValidateFullUnsupportedSafetyDiffTest::class,
            ciJvmAndJs,
        )),

        // ASSEMBLY
        Capability(CapabilityId.ASSEMBLY_PREPARED, Coverage.Evidence(
            CorpusModelPreparerTest::class,
            Static.Tri(CorpusModelPreparerTest::class),
        )),
        Capability(CapabilityId.ASSEMBLY_RAW_COMPOSITION, Coverage.Excluded(
            tracked("IG68", "IG87", why = "commonMain does not yet assemble raw includes, typedefs, opaque ids, and included paths"),
        )),
    )

    private val catalog = OperatorCatalog.load()
    private val operatorMatrix: List<OperatorCapability> = catalog.entries().map { entry ->
        val test = when (entry.id()) {
            "DateRange" -> DateRangeComputeDiffTest::class
            "CustomCondition" -> CustomConditionDiffTest::class
            "RepetitionNotUnique" -> RepetitionNotUniqueExactnessDiffTest::class
            "ValidType", "InvalidType" -> PredefinedTypeValidityDiffTest::class
            else -> {
                require(entry.validExample() != null) {
                    "operator ${entry.id()} has neither ordinary nor specialist strategy evidence"
                }
                CatalogOperatorStrategyDiffTest::class
            }
        }
        OperatorCapability(
            entry.id(),
            Coverage.Evidence(test, Static.Tri(test)),
        )
    }

    private val tierLanes = listOf(
        TierLane(
            "always-on generated-Java strategy differentials",
            ciJvm,
            CatalogOperatorStrategyDiffTest::class.qualifiedName!!,
        ),
        TierLane(
            "always-on portable conformance replay",
            ciJvmAndJs,
            "io.github.mbackschat.a12.dm.interpreter.conformance.CorpusReplayCommonTest",
        ),
        TierLane(
            "opt-in real-corpus breadth sweep",
            optInJvm,
            RealCorpusSweepTest::class.qualifiedName!!,
        ),
    )

    private val expectedUniverse: Set<CapabilityKey> =
        CapabilityId.entries.map { CapabilityKey(it.axis, it.title) }.toSet()

    private fun key(capability: Capability) = CapabilityKey(capability.id.axis, capability.id.title)

    private fun classificationProblems(
        expected: Set<CapabilityKey>,
        classified: List<Capability>,
    ): List<String> = buildList {
        val keys = classified.map(::key)
        val actual = keys.toSet()
        val missing = expected - actual
        val unexpected = actual - expected
        val duplicates = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val populatedAxes = actual.map { it.axis }.toSet() +
            if (operatorMatrix.isEmpty()) emptySet() else setOf(Axis.OPERATOR)
        val missingAxes = Axis.entries.toSet() - populatedAxes
        if (missing.isNotEmpty()) add("missing: ${missing.sortedBy { it.title }}")
        if (unexpected.isNotEmpty()) add("unexpected: ${unexpected.sortedBy { it.title }}")
        if (duplicates.isNotEmpty()) add("duplicates: ${duplicates.sortedBy { it.title }}")
        if (missingAxes.isNotEmpty()) add("axes without a capability: ${missingAxes.sortedBy { it.name }}")
    }

    @Test
    fun everyExpectedCapabilityIsClassifiedExactlyOnce() {
        val problems = classificationProblems(expectedUniverse, matrix)
        assertTrue(problems.isEmpty()) {
            "capability matrix differs from the independently declared universe:\n  ${problems.joinToString("\n  ")}"
        }
    }

    @Test
    fun classificationGuardRejectsOmissionNewUniverseEntryAndDuplicate() {
        val omitted = matrix.first()
        assertTrue(classificationProblems(expectedUniverse, matrix - omitted).any { it.startsWith("missing:") })

        val future = CapabilityKey(Axis.PLATFORM, "future platform capability")
        assertTrue(classificationProblems(expectedUniverse + future, matrix).any { future.title in it })

        assertTrue(classificationProblems(expectedUniverse, matrix + matrix.first()).any { it.startsWith("duplicates:") })
    }

    @Test
    fun selectedIncrementAxesAreExplicitAndPopulated() {
        assertEquals(
            setOf(Axis.OPERATOR, Axis.FORMAL, Axis.COMPUTATION, Axis.CONFIG, Axis.CUSTOMIZATION, Axis.PLATFORM, Axis.ASSEMBLY),
            Axis.entries.toSet(),
        )
        assertTrue(Axis.entries.all { axis ->
            axis == Axis.OPERATOR && operatorMatrix.isNotEmpty() || matrix.any { it.id.axis == axis }
        })
    }

    @Test
    fun everyLiveOperatorHasExactlyOneStrategyCell() {
        val expected = catalog.entries().map { it.id() }
        val actual = operatorMatrix.map { it.id }
        assertEquals(expected.toSet(), actual.toSet())
        assertEquals(actual.size, actual.toSet().size, "duplicate operator evidence cells")
        assertTrue(operatorMatrix.all { it.coverage.static is Static.Tri })
    }

    @Test
    fun trackedClassificationsCarryIssueOrFindingReferences() {
        val reference = Regex("^(IG|IF)\\d+$")
        val tracked = matrix.mapNotNull { (it.coverage as? Coverage.Excluded)?.tracking }
        val invalid = tracked.filter {
            it.why.isBlank() || it.references.isEmpty() || it.references.any { ref -> !reference.matches(ref) }
        }
        assertTrue(invalid.isEmpty()) { "classifications with blank/invalid tracking:\n  ${invalid.joinToString("\n  ")}" }
    }

    @Test
    fun crossRootAndUnsupportedSafetyUseTheirActualLocks() {
        val crossRoot = matrix.single { it.id == CapabilityId.COMPUTATION_CROSS_ROOT }.coverage as Coverage.Evidence
        assertEquals(MultiRootComputationPointerDiffTest::class, crossRoot.dynamic)
        assertEquals(MultiRootComputationPointerDiffTest::class, (crossRoot.static as Static.Tri).test)

        val safety = matrix.single { it.id == CapabilityId.PLATFORM_UNSUPPORTED_SAFETY }.coverage as Coverage.Excluded
        assertEquals(ValidateFullUnsupportedSafetyDiffTest::class, safety.lock)
    }

    @Test
    fun coverageMeterIsDerivedFromTruthfulCells() {
        val evidence = matrix.mapNotNull { it.coverage as? Coverage.Evidence } + operatorMatrix.map { it.coverage }
        val exclusions = matrix.filter { it.coverage is Coverage.Excluded }
        val tri = evidence.count { it.static is Static.Tri }
        println("[capability-matrix] ${matrix.size + operatorMatrix.size} cells: $tri tri-engine, " +
            "${exclusions.size} reasoned-exclusion")
        Axis.entries.forEach { axis ->
            val axisSize = if (axis == Axis.OPERATOR) operatorMatrix.size else matrix.count { it.id.axis == axis }
            val axisTri = if (axis == Axis.OPERATOR) {
                operatorMatrix.count { it.coverage.static is Static.Tri }
            } else {
                matrix.count { it.id.axis == axis && (it.coverage as? Coverage.Evidence)?.static is Static.Tri }
            }
            println("[capability-matrix]   ${axis.name.lowercase()}: $axisSize cells, $axisTri tri-engine")
        }
        assertEquals(expectedUniverse.size + operatorMatrix.size, tri + exclusions.size)
    }

    @Test
    fun coverageTiersNameCiOptInAndPortableLanes() {
        assertEquals(setOf(ciJvm, ciJvmAndJs, optInJvm), tierLanes.map { it.tier }.toSet())
        assertTrue(tierLanes.all { it.name.isNotBlank() && it.reference.isNotBlank() })
        val strategyTiers = matrix.mapNotNull { (it.coverage as? Coverage.Evidence)?.dynamicTier } +
            operatorMatrix.map { it.coverage.dynamicTier } +
            matrix.mapNotNull { (it.coverage as? Coverage.Evidence)?.static as? Static.Tri }.map { it.tier }
        assertEquals(setOf(ciJvm), strategyTiers.toSet())
    }

    @Test
    fun certificateSummaryIsGeneratedFromTheMatrix() {
        val summary = certificateSummary()
        val output = Path.of("build", "reports", "capability-evidence", "certificate-summary.md")
        Files.createDirectories(output.parent)
        Files.writeString(output, summary)

        assertEquals(summary, Files.readString(output))
        assertTrue(summary.contains("${operatorMatrix.size}/${catalog.entries().size} live operators"))
        assertTrue(summary.contains("${matrix.size + operatorMatrix.size} total cells"))
    }

    private fun certificateSummary(): String {
        val nonOperatorEvidence = matrix.count { it.coverage is Coverage.Evidence }
        val exclusions = matrix.count { it.coverage is Coverage.Excluded }
        val total = matrix.size + operatorMatrix.size
        return buildString {
            appendLine("<!-- Generated by CapabilityEvidenceMatrixTest; do not hand-edit the counts. -->")
            appendLine("**Capability × strategy evidence:** ${operatorMatrix.size}/${catalog.entries().size} live operators have")
            appendLine("Groovy-dynamic + generated-Java + interpreter evidence; $nonOperatorEvidence non-operator")
            appendLine("capabilities have the same tri-engine evidence; $exclusions cells are explicit reasoned exclusions;")
            appendLine("$total total cells are accounted for.")
            appendLine()
            appendLine("**Coverage tiers:**")
            tierLanes.forEach { lane ->
                appendLine("- ${lane.tier.cadence.name.lowercase()} / ${lane.tier.runtime.name.lowercase()}: " +
                    "${lane.name} (`${lane.reference}`)")
            }
        }
    }
}
