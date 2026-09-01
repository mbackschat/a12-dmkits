package io.github.mbackschat.a12.dm.rulekit.catalog;

import io.github.mbackschat.a12.dm.Checkout;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * CG2's two-source operator-prose lock. The catalog derives the complete prose-unit denominator. A
 * separately authored review content-binds and classifies each exact unit. Rejection claims exist only as
 * structured, outcome-bound claims; an arbitrary test name cannot discharge them. Admission claims name
 * only an owner-and-outcome-bound kernel claim probe. Runtime claims name a reviewed law or an owner-bound
 * catalog facet. Test relevance remains authored judgment because the guard can prove that a test exists,
 * not infer what its code proves. A prose change therefore demands fresh judgment; the digest is only the
 * change detector.
 */
class CatalogProseReviewGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    private static final String REVIEW_RESOURCE = "catalog/operator-prose-review.json";
    private static final Pattern MVK = Pattern.compile("MVK_[A-Z0-9_]+");
    private static final Pattern TEST_ID = Pattern.compile(
            "(?:^|\\.)([A-Za-z0-9_]+Test)(?:[.#]([A-Za-z0-9_]+))?$");
    private static final Pattern DEFAULT_FACET = Pattern.compile(
            "^emptyOperandDefaults\\.([^.]+)\\.probes\\[(\\d+)]$");
    private static final Pattern EMPTY_FACET = Pattern.compile(
            "^([^.]+)\\.semantics\\.emptyOperand\\[(\\d+)]\\.probes\\[(\\d+)]$");
    private static final Pattern OPERATOR_FACET = Pattern.compile(
            "^([^.]+)\\.semantics\\.(boundary|iterationRange|aggregateIdentity|polarity)\\.probes\\[(\\d+)]$");
    /** Deliberately narrow tripwire, not a prose parser: explicit refusal words must never be runtime-only. */
    private static final Pattern REFUSAL_WORDING = Pattern.compile(
            "(?i)\\b(?:rejects?|rejected|refuses?|refused|not allowed|must not)\\b");
    private static final OperatorCatalog CATALOG = OperatorCatalog.load();
    private static final List<Path> TEST_SOURCES = testSources();

    @Test
    void everyOperatorHasAContentBoundReviewAndEveryClaimResolves() {
        List<LiveOperator> live = liveOperators(CATALOG);
        ReviewFile review = loadReview();
        List<String> violations = new ArrayList<>(audit(live, review));
        violations.addAll(auditClaimProbeClosure(CatalogKernelClaimEvidenceTest.claimProbeIds(), review));

        assertTrue(live.size() >= 110, "the derived operator denominator must not collapse");
        assertTrue(live.stream().mapToInt(operator -> operator.units().size()).sum() > 400,
                "the content digest must span the whole prose surface");
        assertTrue(violations.isEmpty(), () -> "catalog prose review violations:\n  "
                + String.join("\n  ", violations));
    }

    @Test
    void theSameAuditRejectsMissingChangedStaleAndOutOfRangeReviews() {
        LiveOperator one = liveOperator("One", List.of(liveUnit(Lane.MEANING, 0, "description")));
        LiveOperator two = liveOperator("Two", List.of(liveUnit(Lane.CONSTRAINT, 0, "restriction")));
        UnitReview oneReview = review(one, one.units().getFirst(), List.of(Classification.DESCRIPTIVE), List.of());

        assertContains(audit(List.of(one, two), file(List.of(oneReview), List.of())), "missing review");
        LiveOperator changed = liveOperator("One", List.of(liveUnit(Lane.MEANING, 0, "changed")));
        assertContains(audit(List.of(changed), file(List.of(oneReview), List.of())), "digest mismatch");
        assertContains(audit(List.of(), file(List.of(oneReview), List.of())), "stale review");
        Claim outOfRange = claim("out-of-range", "One", Lane.GOTCHA, 9, Verdict.REJECTED,
                "MVK_SYNTHETIC", evidence(EvidenceType.CLAIM_PROBE, "unused"));
        assertContains(audit(List.of(one), file(List.of(oneReview), List.of(outOfRange))),
                "does not resolve to live prose");
        Claim misplaced = claim("misplaced", "One", Lane.MEANING, 0, Verdict.REJECTED,
                "MVK_SYNTHETIC", evidence(EvidenceType.CLAIM_PROBE, "unused"));
        assertContains(audit(List.of(one), file(List.of(oneReview), List.of(misplaced))),
                "targets a unit not classified REJECTION");
    }

    @Test
    void rejectionAdmissionAndRuntimeClassificationsNeedTheirOwnOracle() {
        LiveUnit unit = liveUnit(Lane.CONSTRAINT, 0, "a semantic assertion");
        LiveOperator operator = liveOperator("One", List.of(unit));

        UnitReview rejection = review(operator, unit, List.of(Classification.REJECTION), List.of());
        assertContains(audit(List.of(operator), file(List.of(rejection), List.of())),
                "rejection classification has no structured claim");
        UnitReview unrelatedTest = review(operator, unit, List.of(Classification.REJECTION),
                List.of(new UnitEvidence(Classification.REJECTION, UnitEvidenceType.TEST,
                        "CatalogProseReviewGuardTest#audit", null, null)));
        assertContains(audit(List.of(operator), file(List.of(unrelatedTest), List.of())),
                "rejection classification has no structured claim");
        Claim acceptedOnly = claim("accepted-only", "One", Lane.CONSTRAINT, 0, Verdict.ACCEPTED,
                null, evidence(EvidenceType.CLAIM_PROBE, "unused"));
        assertContains(audit(List.of(operator), file(List.of(rejection), List.of(acceptedOnly))),
                "rejection classification has no structured claim");
        UnitReview runtime = review(operator, unit, List.of(Classification.RUNTIME), List.of());
        assertContains(audit(List.of(operator), file(List.of(runtime), List.of())),
                "runtime classification has no evidence");

        UnitReview admission = review(operator, unit, List.of(Classification.ADMISSION), List.of());
        assertContains(audit(List.of(operator), file(List.of(admission), List.of())),
                "admission classification has no kernel admission evidence");
        UnitReview arbitraryAdmissionTest = review(operator, unit, List.of(Classification.ADMISSION),
                List.of(new UnitEvidence(Classification.ADMISSION, UnitEvidenceType.TEST,
                        "CatalogProseReviewGuardTest#rejectionAdmissionAndRuntimeClassificationsNeedTheirOwnOracle",
                        null, null)));
        assertContains(audit(List.of(operator), file(List.of(arbitraryAdmissionTest), List.of())),
                "admission evidence must be an owner-bound kernel claim probe or a method-level kernel law");
        LiveOperator equal = liveOperator("Equal", List.of(unit));
        UnitReview kernelLaw = review(equal, unit, List.of(Classification.ADMISSION),
                List.of(new UnitEvidence(Classification.ADMISSION, UnitEvidenceType.KERNEL_LAW,
                        "NumberLawsTest#onlyACapableSmallerScaleSideMayBePadded", null, null)));
        assertFalse(audit(List.of(equal), file(List.of(kernelLaw), List.of())).stream()
                .anyMatch(violation -> violation.contains("admission")),
                "a method-level law in the dedicated kernel-law package is valid admission evidence");

        UnitReview mixed = review(operator, unit,
                List.of(Classification.REJECTION, Classification.RUNTIME),
                List.of(new UnitEvidence(Classification.RUNTIME, UnitEvidenceType.TEST,
                        "CatalogProseReviewGuardTest#rejectionAdmissionAndRuntimeClassificationsNeedTheirOwnOracle",
                        null, null)));
        assertContains(audit(List.of(operator), file(List.of(mixed), List.of())),
                "rejection classification has no structured claim");

        UnitReview contradictory = review(operator, unit,
                List.of(Classification.DESCRIPTIVE, Classification.RUNTIME), List.of());
        assertContains(audit(List.of(operator), file(List.of(contradictory), List.of())),
                "descriptive classification is exclusive");

        UnitReview unresolvedFacet = review(operator, unit, List.of(Classification.RUNTIME),
                List.of(new UnitEvidence(Classification.RUNTIME, UnitEvidenceType.FACET,
                        "One.semantics.boundary.probes[99]", null, null)));
        assertContains(audit(List.of(operator), file(List.of(unresolvedFacet), List.of())),
                "unresolved facet evidence");

        UnitReview runtimeOnlyConstraint = review(operator, unit, List.of(Classification.RUNTIME),
                List.of(new UnitEvidence(Classification.RUNTIME, UnitEvidenceType.TEST,
                        "CatalogProseReviewGuardTest#rejectionAdmissionAndRuntimeClassificationsNeedTheirOwnOracle",
                        null, null)));
        assertContains(audit(List.of(operator), file(List.of(runtimeOnlyConstraint), List.of())),
                "constraint needs an admission or rejection classification");

        LiveUnit refusal = liveUnit(Lane.MEANING, 0, "the operator rejects a malformed token");
        LiveOperator refusalOperator = liveOperator("One", List.of(refusal));
        UnitReview refusalRuntime = review(refusalOperator, refusal, List.of(Classification.RUNTIME),
                List.of(new UnitEvidence(Classification.RUNTIME, UnitEvidenceType.TEST,
                        "CatalogProseReviewGuardTest#rejectionAdmissionAndRuntimeClassificationsNeedTheirOwnOracle",
                        null, null)));
        assertContains(audit(List.of(refusalOperator), file(List.of(refusalRuntime), List.of())),
                "explicit refusal wording is not classified REJECTION");
    }

    @Test
    void literalCodesAndEvidenceCannotBorrowAnotherOperatorOrOutcome() {
        LiveUnit unit = liveUnit(Lane.GOTCHA, 0, "no MVK_ERROR_FIELD_NOT_REFERENCED");
        LiveOperator operator = liveOperator("GroupFilled", List.of(unit));
        UnitReview review = review(operator, unit, List.of(Classification.REJECTION),
                List.of(new UnitEvidence(Classification.REJECTION, UnitEvidenceType.TEST,
                        "CatalogProseReviewGuardTest#literalCodesAndEvidenceCannotBorrowAnotherOperatorOrOutcome",
                        null, null)));

        assertContains(audit(List.of(operator), file(List.of(review), List.of())),
                "literal MVK_ERROR_FIELD_NOT_REFERENCED has no claim");

        Claim wrongOwner = claim("wrong-owner", "GroupFilled", Lane.GOTCHA, 0, Verdict.REJECTED,
                "MVK_NEG_CONDITION_IN_ITERATION",
                evidence(EvidenceType.CLAIM_PROBE, "NoGroupFilled.repeatable-negative"));
        assertContains(audit(List.of(operator), file(List.of(review), List.of(wrongOwner))),
                "does not belong to GroupFilled");

        Claim wrongOutcome = claim("wrong-outcome", "GroupFilled", Lane.GOTCHA, 0, Verdict.REJECTED,
                "MVK_ERROR_FIELD_NOT_REFERENCED",
                evidence(EvidenceType.CLAIM_PROBE, "GroupFilled.references-descendant"));
        assertContains(audit(List.of(operator), file(List.of(review), List.of(wrongOutcome))),
                "verdict/code do not match claim probe");

        UnitReview borrowedProbe = review(operator, unit, List.of(Classification.ADMISSION),
                List.of(new UnitEvidence(Classification.ADMISSION, UnitEvidenceType.CLAIM_PROBE,
                        "NoGroupFilled.repeatable-negative", EvidenceSide.CONTROL,
                        new Expected(Verdict.ACCEPTED, null))));
        assertContains(audit(List.of(operator), file(List.of(borrowedProbe), List.of())),
                "does not belong to GroupFilled");

        UnitReview borrowedFacet = review(operator, unit, List.of(Classification.RUNTIME),
                List.of(new UnitEvidence(Classification.RUNTIME, UnitEvidenceType.FACET,
                        "LessThan.semantics.boundary.probes[0]", null, null)));
        assertContains(audit(List.of(operator), file(List.of(borrowedFacet), List.of())),
                "does not belong to GroupFilled");

        UnitReview wrongProbeOutcome = review(operator, unit, List.of(Classification.ADMISSION),
                List.of(new UnitEvidence(Classification.ADMISSION, UnitEvidenceType.CLAIM_PROBE,
                        "GroupFilled.references-descendant", EvidenceSide.TRIGGER,
                        new Expected(Verdict.ACCEPTED, null))));
        assertContains(audit(List.of(operator), file(List.of(wrongProbeOutcome), List.of())),
                "outcome does not match claim-probe evidence");
    }

    @Test
    void testEvidenceNeedsAMethodDeclarationRatherThanACallSite() {
        assertTrue(testEvidenceResolves("NewShapesSnippetsTest#dateRangeEqualitySnippet"));
        assertFalse(testEvidenceResolves("NewShapesSnippetsTest#dateRange"),
                "a Dsl.dateRange(...) call must not masquerade as a test method named dateRange");
    }

    @Test
    void aKernelLawMustReachTheOracleForTheReferencedOperatorInsideTheExactMethod() {
        String source = """
                class SyntheticLaw {
                    void target() { accepts("AllFieldsFilled(Name)"); }
                    void sibling() { KernelLaws.acceptsOrder("/Order", "../Name", "NoFieldFilled(Name)"); }
                    void accepts(String condition) { KernelLaws.acceptsOrder("/Order", "../Name", condition); }
                    void neutered() { helper("AllFieldsFilled(Name)"); }
                    void helper(String condition) { }
                }
                """;
        assertTrue(kernelLawMethodSupports(source, "target", "AllFieldsFilled"),
                "the exact method may reach the owner through a same-class helper");
        assertFalse(kernelLawMethodSupports(source, "sibling", "AllFieldsFilled"),
                "a sibling operator call in the same method must not discharge this operator");
        assertFalse(kernelLawMethodSupports(source, "neutered", "AllFieldsFilled"),
                "an empty or neutered method must not remain kernel-law evidence");
    }

    @Test
    void theClaimProbeInventoryAndReviewReferencesAreBidirectionallyClosed() {
        UnitEvidence used = new UnitEvidence(Classification.ADMISSION, UnitEvidenceType.CLAIM_PROBE,
                "used", EvidenceSide.TRIGGER, new Expected(Verdict.ACCEPTED, null));
        ReviewFile review = file(List.of(new UnitReview("One", Lane.MEANING, 0, "digest",
                List.of(Classification.ADMISSION), List.of(used))), List.of());

        assertContains(auditClaimProbeClosure(Set.of("used", "unreferenced"), review),
                "unreferenced kernel claim probe unreferenced");
        assertContains(auditClaimProbeClosure(Set.of("other"), review),
                "unknown kernel claim probe used");
    }

    private static ReviewFile file(List<UnitReview> units, List<Claim> claims) {
        return new ReviewFile(2, units, claims);
    }

    private static LiveOperator liveOperator(String operator, List<LiveUnit> units) {
        return new LiveOperator(operator, List.copyOf(units));
    }

    private static LiveUnit liveUnit(Lane lane, int ordinal, String text) {
        return new LiveUnit(lane, ordinal, text);
    }

    private static UnitReview review(
            LiveOperator operator, LiveUnit unit, List<Classification> classifications,
            List<UnitEvidence> evidence) {
        return new UnitReview(operator.operator(), unit.lane(), unit.ordinal(), digest(unit.text()),
                classifications, evidence);
    }

    private static Claim claim(String id, String operator, Lane lane, int ordinal,
            Verdict verdict, String code, Evidence evidence) {
        return new Claim(id, operator, Oracle.CONDITION, List.of(new UnitLocator(lane, ordinal)),
                new Expected(verdict, code), List.of(evidence));
    }

    private static Evidence evidence(EvidenceType type, String id) {
        return new Evidence(type, id, null, null, null);
    }

    private static void assertContains(List<String> violations, String fragment) {
        assertTrue(violations.stream().anyMatch(value -> value.contains(fragment)),
                () -> "expected violation containing '" + fragment + "', got " + violations);
    }

    private static List<String> audit(List<LiveOperator> live, ReviewFile file) {
        List<String> violations = new ArrayList<>();
        if (file.schemaVersion() != 2) {
            violations.add("unsupported schemaVersion " + file.schemaVersion());
        }
        Map<String, LiveOperator> liveById = uniqueLive(live, violations);
        Map<Locator, UnitReview> reviews = uniqueReviews(file.units(), violations);
        Map<Locator, List<Claim>> claims = claimsByLocator(file.claims(), violations);

        for (LiveOperator operator : live) {
            for (LiveUnit unit : operator.units()) {
                Locator locator = new Locator(operator.operator(), unit.lane(), unit.ordinal());
                List<Claim> unitClaims = claims.getOrDefault(locator, List.of());
                UnitReview review = reviews.get(locator);
                if (review == null) {
                    violations.add(locator + " missing review");
                } else if (!digest(unit.text()).equals(review.digest())) {
                    violations.add(locator + " prose digest mismatch; after reviewing this exact unit, replace "
                            + "it with " + digest(unit.text()));
                } else {
                    auditClassification(locator, unit, review, unitClaims, violations);
                }
                auditLiteralCodes(locator, unit.text(), unitClaims, violations);
            }
        }

        Set<Locator> liveLocators = new HashSet<>();
        live.forEach(operator -> operator.units().forEach(unit ->
                liveLocators.add(new Locator(operator.operator(), unit.lane(), unit.ordinal()))));
        reviews.keySet().stream().filter(locator -> !liveLocators.contains(locator))
                .forEach(locator -> violations.add(locator + " stale review has no live prose unit"));
        for (Claim claim : file.claims()) {
            LiveOperator operator = liveById.get(claim.operator());
            for (UnitLocator unit : claim.units()) {
                Locator locator = new Locator(claim.operator(), unit.lane(), unit.ordinal());
                if (operator == null || !operator.has(locator)) {
                    violations.add(locator + " claim " + claim.id() + " does not resolve to live prose");
                } else {
                    UnitReview review = reviews.get(locator);
                    if (review != null && claim.expected().verdict() == Verdict.REJECTED
                            && !review.classifications().contains(Classification.REJECTION)) {
                        violations.add(locator + " rejected claim " + claim.id()
                                + " targets a unit not classified REJECTION");
                    } else if (review != null && claim.expected().verdict() != Verdict.REJECTED
                            && !review.classifications().contains(Classification.ADMISSION)
                            && !review.classifications().contains(Classification.REJECTION)) {
                        violations.add(locator + " accepted control " + claim.id()
                                + " targets a unit with no admission or rejection boundary");
                    }
                }
            }
            auditClaim(claim, violations);
        }

        return violations;
    }

    private static List<String> auditClaimProbeClosure(Set<String> liveProbeIds, ReviewFile file) {
        Set<String> referenced = new HashSet<>();
        file.units().stream().flatMap(unit -> unit.evidence().stream())
                .filter(evidence -> evidence.type() == UnitEvidenceType.CLAIM_PROBE)
                .map(UnitEvidence::id).forEach(referenced::add);
        file.claims().stream().flatMap(claim -> claim.evidence().stream())
                .filter(evidence -> evidence.type() == EvidenceType.CLAIM_PROBE)
                .map(Evidence::id).forEach(referenced::add);

        List<String> violations = new ArrayList<>();
        liveProbeIds.stream().filter(id -> !referenced.contains(id)).sorted()
                .forEach(id -> violations.add("unreferenced kernel claim probe " + id));
        referenced.stream().filter(id -> !liveProbeIds.contains(id)).sorted()
                .forEach(id -> violations.add("unknown kernel claim probe " + id));
        return violations;
    }

    private static void auditClassification(
            Locator locator, LiveUnit unit, UnitReview review, List<Claim> claims, List<String> violations) {
        if (review.classifications().isEmpty()) {
            violations.add(locator + " has no classification");
            return;
        }
        EnumSet<Classification> kinds = EnumSet.copyOf(review.classifications());
        if (kinds.size() != review.classifications().size()) {
            violations.add(locator + " repeats a classification");
        }
        if (kinds.contains(Classification.DESCRIPTIVE) && kinds.size() != 1) {
            violations.add(locator + " descriptive classification is exclusive");
        }
        if (locator.lane() == Lane.CONSTRAINT
                && !kinds.contains(Classification.ADMISSION)
                && !kinds.contains(Classification.REJECTION)) {
            violations.add(locator + " constraint needs an admission or rejection classification");
        }
        if (REFUSAL_WORDING.matcher(unit.text()).find() && !kinds.contains(Classification.REJECTION)) {
            violations.add(locator + " explicit refusal wording is not classified REJECTION");
        }
        if (kinds.contains(Classification.REJECTION) && claims.stream().noneMatch(claim ->
                claim.expected().verdict() == Verdict.REJECTED)) {
            violations.add(locator + " rejection classification has no structured claim with a rejected outcome");
        }
        if (kinds.contains(Classification.RUNTIME) && review.evidence().stream().noneMatch(evidence ->
                evidence.classification() == Classification.RUNTIME)) {
            violations.add(locator + " runtime classification has no evidence");
        }
        if (kinds.contains(Classification.ADMISSION) && review.evidence().stream().noneMatch(evidence ->
                evidence.classification() == Classification.ADMISSION
                        && ((evidence.type() == UnitEvidenceType.CLAIM_PROBE
                                && evidence.expected() != null
                                && evidence.expected().verdict() == Verdict.ACCEPTED)
                                || evidence.type() == UnitEvidenceType.KERNEL_LAW))) {
            violations.add(locator + " admission classification has no kernel admission evidence");
        }
        if (kinds.equals(EnumSet.of(Classification.DESCRIPTIVE))
                && (!claims.isEmpty() || !review.evidence().isEmpty())) {
            violations.add(locator + " descriptive classification has semantic evidence");
        }
        for (UnitEvidence evidence : review.evidence()) {
            if (!kinds.contains(evidence.classification())) {
                violations.add(locator + " has evidence for unclassified " + evidence.classification());
            }
            if (evidence.classification() == Classification.REJECTION) {
                violations.add(locator + " rejection evidence must be a structured claim, not "
                        + evidence.type() + " unit evidence");
            } else if (evidence.classification() == Classification.ADMISSION
                    && evidence.type() != UnitEvidenceType.CLAIM_PROBE
                    && evidence.type() != UnitEvidenceType.KERNEL_LAW) {
                violations.add(locator + " admission evidence must be an owner-bound kernel claim probe or "
                        + "a method-level kernel law, not " + evidence.type());
            } else if (evidence.classification() == Classification.ADMISSION
                    && evidence.type() == UnitEvidenceType.CLAIM_PROBE
                    && (evidence.expected() == null
                            || evidence.expected().verdict() != Verdict.ACCEPTED)) {
                violations.add(locator + " admission claim-probe evidence must assert an accepted outcome");
            } else if (evidence.classification() == Classification.RUNTIME
                    && evidence.type() == UnitEvidenceType.CLAIM_PROBE) {
                violations.add(locator + " runtime evidence must be a runtime test or facet, not a claim probe");
            } else if (evidence.id() == null || evidence.id().isBlank()) {
                violations.add(locator + " has blank " + evidence.type() + " evidence");
            } else if (evidence.type() == UnitEvidenceType.TEST
                    && !testEvidenceResolves(evidence.id())) {
                violations.add(locator + " has unresolved test evidence " + evidence.id());
            } else if (evidence.type() == UnitEvidenceType.KERNEL_LAW
                    && !kernelLawEvidenceResolves(evidence.id(), locator.operator())) {
                violations.add(locator + " has unresolved method-level kernel-law evidence " + evidence.id());
            } else if (evidence.type() == UnitEvidenceType.FACET
                    && !facetEvidenceResolves(evidence.id())) {
                violations.add(locator + " has unresolved facet evidence " + evidence.id());
            } else if (evidence.type() == UnitEvidenceType.FACET
                    && facetEvidenceOwner(evidence.id()) != null
                    && !facetEvidenceOwner(evidence.id()).equals(locator.operator())) {
                violations.add(locator + " facet evidence " + evidence.id()
                        + " does not belong to " + locator.operator());
            } else if (evidence.type() == UnitEvidenceType.CLAIM_PROBE) {
                CatalogKernelClaimEvidenceTest.Descriptor descriptor =
                        CatalogKernelClaimEvidenceTest.descriptor(
                                evidence.id(), evidence.side() == EvidenceSide.CONTROL);
                if (descriptor == null) {
                    violations.add(locator + " has unresolved claim-probe evidence " + evidence.id());
                } else if (!descriptor.operator().equals(locator.operator())) {
                    violations.add(locator + " claim-probe evidence " + evidence.id()
                            + " does not belong to " + locator.operator());
                } else if (evidence.expected() == null
                        || !descriptor.verdict().name().equals(evidence.expected().verdict().name())
                        || !java.util.Objects.equals(descriptor.code(), evidence.expected().code())) {
                    violations.add(locator + " outcome does not match claim-probe evidence " + evidence.id());
                }
            }
        }
    }

    private static boolean testEvidenceResolves(String id) {
        Path match = testEvidencePath(id);
        if (match == null) {
            return false;
        }
        var matcher = TEST_ID.matcher(id);
        matcher.find();
        String method = matcher.group(2);
        if (method == null || method.isBlank()) {
            return true;
        }
        try {
            return declaresMethod(Files.readString(match), method);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read test evidence " + match, e);
        }
    }

    private static boolean kernelLawEvidenceResolves(String id, String operatorId) {
        var matcher = TEST_ID.matcher(id);
        if (!matcher.find() || matcher.group(2) == null || matcher.group(2).isBlank()) {
            return false;
        }
        Path path = testEvidencePath(id);
        if (path == null || !path.toString().replace('\\', '/').contains(
                "/src/test/java/io/github/mbackschat/a12/dm/rulekit/validate/laws/")) {
            return false;
        }
        try {
            String source = Files.readString(path);
            OperatorEntry operator = CATALOG.byId(operatorId).orElse(null);
            return operator != null && operator.keywords() != null && operator.keywords().en() != null
                    && kernelLawMethodSupports(source, matcher.group(2), operator.keywords().en());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read kernel-law evidence " + path, e);
        }
    }

    private static Path testEvidencePath(String id) {
        var matcher = TEST_ID.matcher(id);
        if (!matcher.find()) {
            return null;
        }
        String className = matcher.group(1);
        List<Path> matches = TEST_SOURCES.stream()
                .filter(path -> path.getFileName().toString().equals(className + ".java")
                        || path.getFileName().toString().equals(className + ".kt"))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static boolean declaresMethod(String source, String method) {
        return javaMethodPattern(method).matcher(source).find() || kotlinMethodPattern(method).matcher(source).find();
    }

    private static Pattern javaMethodPattern(String method) {
        String name = Pattern.quote(method);
        String annotation = "(?:@[A-Za-z_$][A-Za-z0-9_$.]*(?:\\([^\\n]*\\))?\\s+)*";
        return Pattern.compile("(?m)^\\s*" + annotation
                + "(?:(?:public|protected|private|static|final|synchronized|abstract|native|strictfp|default)\\s+)*"
                + "(?:<[^>\\n]+>\\s+)?[A-Za-z_$][A-Za-z0-9_$.<>?,\\[\\] ]*\\s+"
                + name + "\\s*\\(");
    }

    private static Pattern kotlinMethodPattern(String method) {
        String name = Pattern.quote(method);
        String annotation = "(?:@[A-Za-z_$][A-Za-z0-9_$.]*(?:\\([^\\n]*\\))?\\s+)*";
        return Pattern.compile("(?m)^\\s*" + annotation
                + "(?:(?:public|protected|private|internal|final|open|abstract|override|suspend|tailrec|operator|"
                + "infix|inline|external)\\s+)*fun\\s+" + name + "\\s*\\(");
    }

    private static String methodBody(String source, String method) {
        var declaration = javaMethodPattern(method).matcher(source);
        if (!declaration.find()) {
            return null;
        }
        int open = source.indexOf('{', declaration.end());
        if (open < 0) {
            return null;
        }
        int depth = 0;
        ScanState state = ScanState.CODE;
        for (int i = open; i < source.length(); i++) {
            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            switch (state) {
                case CODE -> {
                    if (current == '"') state = ScanState.STRING;
                    else if (current == '\'') state = ScanState.CHAR;
                    else if (current == '/' && next == '/') { state = ScanState.LINE_COMMENT; i++; }
                    else if (current == '/' && next == '*') { state = ScanState.BLOCK_COMMENT; i++; }
                    else if (current == '{') depth++;
                    else if (current == '}' && --depth == 0) return source.substring(open, i + 1);
                }
                case STRING -> {
                    if (current == '\\') i++;
                    else if (current == '"') state = ScanState.CODE;
                }
                case CHAR -> {
                    if (current == '\\') i++;
                    else if (current == '\'') state = ScanState.CODE;
                }
                case LINE_COMMENT -> {
                    if (current == '\n') state = ScanState.CODE;
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') { state = ScanState.CODE; i++; }
                }
            }
        }
        return null;
    }

    private static boolean kernelLawMethodSupports(String source, String method, String keyword) {
        String body = methodBody(source, method);
        if (body == null || keyword == null || keyword.isBlank()) {
            return false;
        }
        return keywordOccurs(body, keyword) && reachesKernelOracle(source, method, new HashSet<>());
    }

    private static boolean keywordOccurs(String body, String keyword) {
        return Pattern.compile("(?i)(?<![A-Za-z0-9_$])" + Pattern.quote(keyword)
                + "(?![A-Za-z0-9_$])").matcher(body).find();
    }

    /** Follow only calls made by the exact evidence method into methods declared in the same source file. A
     * sibling declaration cannot discharge the law; a shared local helper may. */
    private static boolean reachesKernelOracle(String source, String method, Set<String> visited) {
        if (!visited.add(method)) {
            return false;
        }
        String body = methodBody(source, method);
        if (body == null) {
            return false;
        }
        if (body.contains("KernelLaws.") || body.contains("RuleValidator")) {
            return true;
        }
        var calls = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(").matcher(body);
        while (calls.find()) {
            String called = calls.group(1);
            if (methodBody(source, called) != null && reachesKernelOracle(source, called, visited)) {
                return true;
            }
        }
        return false;
    }

    private enum ScanState { CODE, STRING, CHAR, LINE_COMMENT, BLOCK_COMMENT }

    private static boolean facetEvidenceResolves(String id) {
        var defaultMatcher = DEFAULT_FACET.matcher(id);
        if (defaultMatcher.matches()) {
            var claim = CATALOG.emptyOperandDefaults().get(defaultMatcher.group(1));
            return claim != null && index(defaultMatcher.group(2)) < claim.probes().size();
        }
        var emptyMatcher = EMPTY_FACET.matcher(id);
        if (emptyMatcher.matches()) {
            OperatorEntry operator = CATALOG.byId(emptyMatcher.group(1)).orElse(null);
            int claimIndex = index(emptyMatcher.group(2));
            int probeIndex = index(emptyMatcher.group(3));
            return operator != null && claimIndex < operator.emptyOperandClaims().size()
                    && probeIndex < operator.emptyOperandClaims().get(claimIndex).probes().size();
        }
        var operatorMatcher = OPERATOR_FACET.matcher(id);
        if (!operatorMatcher.matches()) {
            return false;
        }
        OperatorEntry operator = CATALOG.byId(operatorMatcher.group(1)).orElse(null);
        if (operator == null) {
            return false;
        }
        List<FacetProbe> probes = switch (operatorMatcher.group(2)) {
            case "boundary" -> operator.boundaryClaim() == null
                    ? List.of() : operator.boundaryClaim().probes();
            case "iterationRange" -> operator.iterationRangeClaim() == null
                    ? List.of() : operator.iterationRangeClaim().probes();
            case "aggregateIdentity" -> operator.aggregateIdentityClaim() == null
                    ? List.of() : operator.aggregateIdentityClaim().probes();
            case "polarity" -> operator.polarityClaim() == null
                    ? List.of() : operator.polarityClaim().probes();
            default -> throw new IllegalStateException("unhandled facet " + operatorMatcher.group(2));
        };
        return index(operatorMatcher.group(3)) < probes.size();
    }

    private static String facetEvidenceOwner(String id) {
        if (DEFAULT_FACET.matcher(id).matches()) {
            return null;
        }
        var emptyMatcher = EMPTY_FACET.matcher(id);
        if (emptyMatcher.matches()) {
            return emptyMatcher.group(1);
        }
        var operatorMatcher = OPERATOR_FACET.matcher(id);
        return operatorMatcher.matches() ? operatorMatcher.group(1) : null;
    }

    private static int index(String value) {
        return Integer.parseInt(value);
    }

    private static List<Path> testSources() {
        List<Path> result = new ArrayList<>();
        for (String tree : List.of("src/test", "adapter/src/test", "interpreter/src/commonTest",
                "interpreter/src/jvmTest", "interpreter/src/jsTest", "cli/src/test")) {
            Path directory = CHECKOUT.resolve(tree);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(directory)) {
                walk.filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".kt"))
                        .forEach(result::add);
            } catch (IOException e) {
                throw new IllegalStateException("cannot enumerate test evidence under " + directory, e);
            }
        }
        return List.copyOf(result);
    }


    private static void auditLiteralCodes(
            Locator locator, String text, List<Claim> claims, List<String> violations) {
        var matcher = MVK.matcher(text);
        while (matcher.find()) {
            String code = matcher.group();
            if (claims.stream().noneMatch(claim -> claim.expected() != null
                    && code.equals(claim.expected().code()))) {
                violations.add(locator + " literal " + code + " has no claim");
            }
        }
    }

    private static void auditClaim(Claim claim, List<String> violations) {
        if (claim.id() == null || claim.id().isBlank()) {
            violations.add(claim.operator() + " has a blank claim id");
        }
        if (claim.units().isEmpty()) {
            violations.add(claim.operator() + " claim " + claim.id() + " has no prose locators");
        }
        if (claim.expected() == null) {
            violations.add(claim.operator() + " claim " + claim.id() + " has no expected outcome");
            return;
        }
        if (claim.expected().verdict() != Verdict.ACCEPTED
                && (claim.expected().code() == null || claim.expected().code().isBlank())) {
            violations.add(claim.operator() + " claim " + claim.id() + " has no expected code");
            return;
        }
        if (claim.evidence().isEmpty()) {
            violations.add(claim.operator() + " claim " + claim.id() + " has no evidence");
        }
        claim.evidence().forEach(evidence -> resolveEvidence(claim, evidence, violations));
    }

    private static void resolveEvidence(Claim claim, Evidence evidence, List<String> violations) {
        OperatorEntry operator = CATALOG.byId(claim.operator()).orElse(null);
        if (operator == null) {
            violations.add(claim.operator() + " names an unknown operator");
            return;
        }
        switch (evidence.type()) {
            case INVALID_EXAMPLE -> {
                OperatorEntry.InvalidExample invalid = operator.invalidExample();
                if (invalid == null || claim.kind() != Oracle.CONDITION
                        || claim.expected().verdict() != Verdict.REJECTED
                        || !claim.expected().code().equals(invalid.error())) {
                    violations.add(claim.operator() + " claim " + claim.id()
                            + " does not match the operator's invalidExample");
                }
            }
            case CORRECTIVE -> {
                boolean found = operator.correctives().stream().anyMatch(corrective ->
                        claim.kind() == Oracle.CONDITION && claim.expected().verdict() == Verdict.REJECTED
                                && corrective.code().equals(evidence.code())
                                && sorted(corrective.whenOperands()).equals(sorted(evidence.whenOperands()))
                                && claim.expected().code().equals(corrective.code()));
                if (!found) {
                    violations.add(claim.operator() + " claim " + claim.id()
                            + " has unresolved corrective evidence");
                }
            }
            case CLAIM_PROBE -> {
                CatalogKernelClaimEvidenceTest.Descriptor descriptor =
                        CatalogKernelClaimEvidenceTest.descriptor(
                                evidence.id(), evidence.side() == EvidenceSide.CONTROL);
                if (descriptor == null) {
                    violations.add(claim.operator() + " claim " + claim.id()
                            + " has unresolved claim probe " + evidence.id());
                } else if (!descriptor.operator().equals(claim.operator())) {
                    violations.add(claim.operator() + " claim " + claim.id() + " probe " + evidence.id()
                            + " does not belong to " + claim.operator());
                } else if (!descriptor.oracle().name().equals(claim.kind().name())
                        || !descriptor.verdict().name().equals(claim.expected().verdict().name())
                        || !java.util.Objects.equals(descriptor.code(), claim.expected().code())) {
                    violations.add(claim.operator() + " claim " + claim.id()
                            + " verdict/code do not match claim probe " + evidence.id());
                }
            }
        }
    }

    private static List<String> sorted(List<String> values) {
        return values == null ? List.of() : values.stream().sorted().toList();
    }

    private static Map<String, LiveOperator> uniqueLive(List<LiveOperator> live, List<String> violations) {
        Map<String, LiveOperator> result = new LinkedHashMap<>();
        for (LiveOperator operator : live) {
            if (result.putIfAbsent(operator.operator(), operator) != null) {
                violations.add(operator.operator() + " duplicate live operator");
            }
        }
        return result;
    }

    private static Map<Locator, UnitReview> uniqueReviews(
            List<UnitReview> reviews, List<String> violations) {
        Map<Locator, UnitReview> result = new LinkedHashMap<>();
        for (UnitReview review : reviews) {
            Locator locator = new Locator(review.operator(), review.lane(), review.ordinal());
            if (result.putIfAbsent(locator, review) != null) {
                violations.add(locator + " duplicate unit review");
            }
        }
        return result;
    }

    private static Map<Locator, List<Claim>> claimsByLocator(List<Claim> claims, List<String> violations) {
        Map<Locator, List<Claim>> result = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        for (Claim claim : claims) {
            if (claim.id() == null || !ids.add(claim.id())) {
                violations.add("blank or duplicate claim id " + claim.id());
            }
            for (UnitLocator unit : claim.units()) {
                Locator locator = new Locator(claim.operator(), unit.lane(), unit.ordinal());
                result.computeIfAbsent(locator, ignored -> new ArrayList<>()).add(claim);
            }
        }
        return result;
    }

    private static List<LiveOperator> liveOperators(OperatorCatalog catalog) {
        List<LiveOperator> result = new ArrayList<>();
        for (OperatorEntry operator : catalog.entries()) {
            List<LiveUnit> units = new ArrayList<>();
            units.add(liveUnit(Lane.MEANING, 0, operator.meaning()));
            for (int i = 0; i < operator.constraints().size(); i++) {
                units.add(liveUnit(Lane.CONSTRAINT, i, operator.constraints().get(i)));
            }
            for (int i = 0; i < operator.gotchas().size(); i++) {
                units.add(liveUnit(Lane.GOTCHA, i, operator.gotchas().get(i).text()));
            }
            result.add(liveOperator(operator.id(), units));
        }
        return List.copyOf(result);
    }

    private static ReviewFile loadReview() {
        ObjectMapper mapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        try (InputStream in = CatalogProseReviewGuardTest.class.getClassLoader()
                .getResourceAsStream(REVIEW_RESOURCE)) {
            assertNotNull(in, REVIEW_RESOURCE + " not found");
            return mapper.readValue(in, ReviewFile.class);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load " + REVIEW_RESOURCE, e);
        }
    }

    private static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    enum Lane { MEANING, CONSTRAINT, GOTCHA }
    enum Classification { REJECTION, RUNTIME, ADMISSION, DESCRIPTIVE }
    enum Oracle { CONDITION, COMPUTATION }
    enum Verdict { ACCEPTED, ACCEPTED_WITHOUT_CODE, REJECTED }
    enum EvidenceType { INVALID_EXAMPLE, CORRECTIVE, CLAIM_PROBE }
    enum EvidenceSide { TRIGGER, CONTROL }

    record ReviewFile(int schemaVersion, List<UnitReview> units, List<Claim> claims) {
        ReviewFile {
            units = units == null ? List.of() : List.copyOf(units);
            claims = claims == null ? List.of() : List.copyOf(claims);
        }
    }

    enum UnitEvidenceType { TEST, FACET, CLAIM_PROBE, KERNEL_LAW }
    record UnitEvidence(Classification classification, UnitEvidenceType type, String id, EvidenceSide side,
            Expected expected) { }
    record UnitReview(String operator, Lane lane, int ordinal, String digest,
            List<Classification> classifications, List<UnitEvidence> evidence) {
        UnitReview {
            classifications = classifications == null ? List.of() : List.copyOf(classifications);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }
    record Locator(String operator, Lane lane, int ordinal) { }
    record UnitLocator(Lane lane, int ordinal) { }
    record Claim(String id, String operator, Oracle kind, List<UnitLocator> units,
            Expected expected, List<Evidence> evidence) {
        Claim {
            units = List.copyOf(units);
            evidence = List.copyOf(evidence);
        }
    }
    record Expected(Verdict verdict, String code) { }
    record Evidence(EvidenceType type, String id, String code, List<String> whenOperands, EvidenceSide side) { }
    record LiveUnit(Lane lane, int ordinal, String text) { }
    record LiveOperator(String operator, List<LiveUnit> units) {
        boolean has(Locator locator) {
            return operator.equals(locator.operator()) && units.stream().anyMatch(unit ->
                    unit.lane() == locator.lane() && unit.ordinal() == locator.ordinal());
        }
    }
}
