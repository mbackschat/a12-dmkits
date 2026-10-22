package io.github.mbackschat.a12.dm.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.consistency.ConsistencyStatus;
import io.github.mbackschat.a12.dm.consistency.CoverageGap;
import io.github.mbackschat.a12.dm.consistency.ModelSource;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.Severity;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DiagnosticSource;

/**
 * The F3-a gate: a dmkits caller asks the Kernel-free checker for a verdict, with no kernel on the classpath.
 *
 * <p>Two things are held here that no other test in the estate can hold. The verdict must arrive in the
 * dmkits SHAPE — {@code ok()}, {@code diagnostics()}, a receipt — while carrying the checker's own codes; and
 * the provenance must be its own on every axis, because both neighbouring values are false claims. The
 * checker's correctness is not retested: {@code :consistency} owns that, and re-asserting it here would make
 * two places that must agree about A12.
 */
class PortableConsistencyGateTest {

    @Test
    void aRejectionArrivesAsAFailedResultCarryingTheCheckersOwnCode() {
        PortableGateRun run = PortableConsistencyGate.check(order("../NoSuchField", "FieldFilled(Code)"));

        assertFalse(run.validation().ok(), "an unresolvable error target is a measured A12 rejection");
        assertEquals(ConsistencyStatus.Invalid.INSTANCE, run.report().getStatus());
        assertTrue(codes(run).contains("MVK_ERROR_FIELD_MISSING"),
                "the checker's own vocabulary, not a re-derivation: " + codes(run));
        assertEquals(run.report().getFindings().stream().map(f -> f.getCode()).toList(), codes(run),
                "the gate carries every checker finding without collapsing the paired rejection");
        // A STRUCTURE locus. A `Where.rule` would promise a locus the caller can dereference into a rulekit
        // Rule handle, and the checker names a declared path — it holds no rulekit object at all.
        assertEquals(run.report().getFindings().stream().map(f -> Optional.ofNullable(f.getAt())).toList(),
                run.validation().diagnostics().stream().map(d -> d.where().path()).toList(),
                "every checker finding retains its structure locus, including findings sharing a path");
        assertEquals(Optional.of("/Order/CodeRequired"), run.validation().diagnostics().getFirst().where().path());
    }

    /**
     * An advisory must not reject. The checker separates a measured A12 constraint from a dmkits authoring
     * policy, and a mapping that flattened severities would turn the second into a refusal A12 never
     * issues — invisible precisely because no kernel is asked.
     */
    @Test
    void anAdvisoryIsCarriedWithoutRejectingTheModel() {
        PortableGateRun run = PortableConsistencyGate.check(duplicateMessageLocale());

        assertTrue(run.validation().ok(), "an authoring policy is not an A12 rejection: " + codes(run));
        assertEquals(List.of(Severity.WARNING),
                run.validation().diagnostics().stream().map(Diagnostic::severity).distinct().toList());
        assertTrue(codes(run).contains("MVK_LANGUAGE_DUPLICATE"), codes(run).toString());
    }

    /**
     * A type-definition IMPORT is a reference like an include and is resolved like one. It is its own row
     * because it is a different {@code purpose} in the header: a walk that read only the include entries
     * would leave the field's type unresolvable while passing every other row here.
     */
    @Test
    void aTypeDefinitionImportIsResolvedAsWellAsAnInclude() {
        PortableGateRun supplied = PortableConsistencyGate.check(
                new ModelSource(importingTypes(), "orders.dm.json"), resolver(Map.of("types", types())));
        PortableGateRun missing = PortableConsistencyGate.check(
                new ModelSource(importingTypes(), "orders.dm.json"), resolver(Map.of()));

        assertTrue(supplied.validation().ok(), "`Money` is declared in the imported model: " + codes(supplied));
        assertTrue(codes(missing).contains("CK_MODEL_NOT_BUILDABLE"),
                "and the positive control shows the import was load-bearing: " + codes(missing));
    }

    /**
     * The row that says what {@code ok()} does NOT mean. This checker publishes no VALID verdict, so an
     * accepted model comes back with nothing rejecting it AND a status of Inconclusive — and the coverage
     * evidence is what a consumer must read to tell those apart.
     */
    @Test
    void anAcceptedModelIsInconclusiveWithItsCoverageEvidenceRatherThanAPass() {
        PortableGateRun run = PortableConsistencyGate.check(order("../Code", "FieldFilled(Code)"));

        assertTrue(run.validation().ok(), "nothing rejected it");
        assertEquals(ConsistencyStatus.Inconclusive.INSTANCE, run.report().getStatus(),
                "and that is still not a pass — no coverage registry earns VALID");
        assertFalse(run.report().getChecksCompleted().isEmpty(),
                "the evidence that anything ran at all, which ok() cannot carry");
    }

    @Test
    void theReceiptIsThePortableCheckerOnEveryArmThatRanTheGate() {
        for (PortableGateRun run : List.of(
                PortableConsistencyGate.check(order("../Code", "FieldFilled(Code)")),
                PortableConsistencyGate.check(order("../NoSuchField", "FieldFilled(Code)")),
                PortableConsistencyGate.check("{\"header\":"))) {
            assertEquals(Optional.of(Verification.PORTABLE_CHECKED), run.validation().consistencyGate(),
                    "never KERNEL_CONFIRMED — no kernel saw the model — and never STRUCTURAL_ONLY either");
        }
    }

    /**
     * The origin, which is the half a caller routes on. Both neighbours would be false: KERNEL says the real
     * kernel arbitrated, PRECHECK says a rulekit heuristic refused the model, and neither happened.
     */
    @Test
    void everyDiagnosticSaysThePortableCheckerSpoke() {
        PortableGateRun run = PortableConsistencyGate.check(order("../NoSuchField", "FieldFilled(Code)"));

        assertFalse(run.validation().diagnostics().isEmpty(), "otherwise the row below passes vacuously");
        for (Diagnostic diagnostic : run.validation().diagnostics()) {
            assertEquals(DiagnosticSource.PORTABLE_CHECKER, diagnostic.source());
        }
    }

    @Test
    void anUnreadableSourceIsADiagnosticRatherThanAThrow() {
        PortableGateRun run = PortableConsistencyGate.check("{\"header\":");

        assertFalse(run.validation().ok(), "nothing was established, so the result cannot read as passing");
        assertEquals(List.of(Severity.ERROR),
                run.validation().diagnostics().stream().map(Diagnostic::severity).distinct().toList());
        assertTrue(codes(run).contains("SOURCE_NOT_WELL_FORMED"),
                "named in the checker's own vocabulary: " + codes(run));
    }

    /**
     * The same on the RESOLVER arm, which reaches the header reader first. An unreadable entry declares no
     * references to follow, and that reader's failure is not this façade's verdict to report — the checker's
     * own admission phase says it, with the label attached.
     */
    @Test
    void anUnreadableSourceOnTheResolverArmIsReportedRatherThanRaised() {
        PortableGateRun run = PortableConsistencyGate.check(
                new ModelSource("{\"header\":", "typo.dm.json"), resolver(Map.of("catalog", catalog(""))));

        assertFalse(run.validation().ok());
        assertTrue(codes(run).contains("SOURCE_NOT_WELL_FORMED"), codes(run).toString());
    }

    /**
     * The include path, through the kits' own resolver rather than a hand-assembled source list. The
     * condition names a field only the PROVIDER declares, so a gate that did not supply the provider would
     * report it missing — which is the row below.
     */
    @Test
    void anIncludeResolvesThroughTheKitsOwnModelResolver() {
        PortableGateRun run = PortableConsistencyGate.check(
                new ModelSource(mounting("FieldFilled(ShipTo/Zip) And FieldFilled(Code)"), "orders.dm.json"),
                resolver(Map.of("catalog", catalog(""))));

        assertTrue(run.validation().ok(), "`Zip` is declared in the provider: " + codes(run));
        assertEquals(List.of(), unexpandedMounts(run), "and nothing is left unexpanded");
    }

    @Test
    void aReferenceTheResolverCannotSupplyLeavesTheMountAsAGapRatherThanAFinding() {
        PortableGateRun run = PortableConsistencyGate.check(
                new ModelSource(mounting("FieldFilled(ShipTo/Zip) And FieldFilled(Code)"), "orders.dm.json"),
                resolver(Map.of()));

        assertEquals(List.of("/Order/ShipTo"), unexpandedMounts(run),
                "the mount is named as unexpanded: " + run.report().getGaps());
        assertFalse(codes(run).contains("MVK_INVALID_ENTITY"),
                "and the reference into it is NOT reported missing, which would reject a model A12 accepts");
    }

    /**
     * The closure is walked, not just the entry's own header: the provider mounts a third model, so a gate
     * that resolved one level would leave that inner mount unexpanded. Its absence from {@code gaps} is what
     * proves the third source was supplied.
     */
    @Test
    void aProvidersOwnReferenceIsResolvedTooRatherThanLeftAtOneLevel() {
        String catalogMountingGeo = catalog(
                ",{\"type\":\"Group\",\"id\":\"cg-region\",\"name\":\"Region\","
                        + "\"Group\":{\"modelAlias\":\"geo\"}}");
        ModelResolver both = resolver(Map.of("catalog", catalogMountingGeo, "geo", geo()));

        PortableGateRun resolved = PortableConsistencyGate.check(
                new ModelSource(mounting("FieldFilled(ShipTo/Region/Country) And FieldFilled(Code)"),
                        "orders.dm.json"), both);
        PortableGateRun oneLevel = PortableConsistencyGate.check(
                new ModelSource(mounting("FieldFilled(ShipTo/Region/Country) And FieldFilled(Code)"),
                        "orders.dm.json"), resolver(Map.of("catalog", catalogMountingGeo)));

        assertEquals(List.of(), unexpandedMounts(resolved), "both levels expanded");
        assertEquals(List.of("/Order/ShipTo/Region"), unexpandedMounts(oneLevel),
                "and without the third source the INNER mount is what goes unexpanded: "
                        + oneLevel.report().getGaps());
    }

    /**
     * The gaps that name a PLACE, which are the mounts. Every report also carries the checker's five standing
     * gaps — the families it does not implement — and those have no {@code at}, so filtering on one separates
     * "this model has an unexpanded include" from "this checker never checks everything".
     */
    private static List<String> unexpandedMounts(PortableGateRun run) {
        return run.report().getGaps().stream().map(CoverageGap::getAt).filter(java.util.Objects::nonNull).toList();
    }

    private static List<String> codes(PortableGateRun run) {
        return run.validation().diagnostics().stream().map(d -> d.code().orElse("")).toList();
    }

    /** A map-backed resolver — the kits' own {@code ReferenceResolvers.map} shape, answering null on a miss. */
    private static ModelResolver resolver(Map<String, String> byId) {
        return byId::get;
    }

    private static String order(String errorTarget, String condition) {
        return """
            {"header":{"id":"orders","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f-code","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Rule","id":"r-code","name":"CodeRequired","Rule":{
                  "errorCondition":"%s","errorEntityRelPath":"%s",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"the order code"}]}}
              ]}}
             ]}}}
            """.formatted(condition, errorTarget);
    }

    private static String mounting(String condition) {
        return """
            {"header":{"id":"orders","locales":[{"code":"en_US"}],
              "modelReferences":[{"alias":"catalog","reference":"catalog"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f-code","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g-ship","name":"ShipTo","Group":{"modelAlias":"catalog"}},
                {"type":"Rule","id":"r-code","name":"CodeRequired","Rule":{
                  "errorCondition":"%s","errorEntityRelPath":"../Code",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"the order code"}]}}
              ]}}
             ]}}}
            """.formatted(condition);
    }

    private static String catalog(String extraElements) {
        return """
            {"header":{"id":"catalog","locales":[{"code":"en_US"}],
              "modelReferences":[{"alias":"geo","reference":"geo"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"cg0","name":"Address","Group":{"elements":[
                {"type":"Field","id":"cf0","name":"Zip","Field":{"fieldType":{"type":"StringType"}}}%s
              ]}}
             ]}}}
            """.formatted(extraElements);
    }

    private static String duplicateMessageLocale() {
        return """
            {"header":{"id":"orders","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f-code","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Rule","id":"r-code","name":"CodeRequired","Rule":{
                  "errorCondition":"FieldFilled(Code)","errorEntityRelPath":"../Code","errorCode":"C1",
                  "errorMessage":[{"locale":"en_US","text":"the order code"},
                                  {"locale":"en_US","text":"the order code, again"}]}}
              ]}}
             ]}}}
            """;
    }

    private static String importingTypes() {
        return """
            {"header":{"id":"orders","locales":[{"code":"en_US"}],
              "modelReferences":[{"alias":"types","reference":"types","purpose":"typeDefinitions"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f-code","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f-total","name":"Total","Field":{"fieldType":{
                  "type":"TypeDefType","TypeDefType":{"typeDefinitionId":"Money"}}}},
                {"type":"Rule","id":"r-code","name":"CodeRequired","Rule":{
                  "errorCondition":"FieldFilled(Code)","errorEntityRelPath":"../Code",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"the order code"}]}}
              ]}}
             ]}}}
            """;
    }

    /** A library declares no root group: A12 does not expand an import whose root holds one (KF594). */
    private static String types() {
        return """
            {"header":{"id":"types","locales":[{"code":"en_US"}]},
             "content":{"typeDefinitions":[{"id":"Money","name":"Money","fieldType":{"type":"NumberType"}}],
              "modelRoot":{"rootGroups":[]}}}
            """;
    }

    private static String geo() {
        return """
            {"header":{"id":"geo","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"gg0","name":"Territory","Group":{"elements":[
                {"type":"Field","id":"gf0","name":"Country","Field":{"fieldType":{"type":"StringType"}}}
              ]}}
             ]}}}
            """;
    }
}
