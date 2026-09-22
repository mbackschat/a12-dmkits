package io.github.mbackschat.a12.dm.rulekit.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.opentest4j.AssertionFailedError;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * Kernel evidence for reviewed catalog claims that do not fit a primary invalid example or catalog facet.
 * Operator attribution is authored review metadata, not inferred causation. Each trigger carries a control
 * that distinguishes the measured boundary from its nearest admitted or differently-rejected sibling.
 */
class CatalogKernelClaimEvidenceTest {

    private static final String CLAIMS_RESOURCE = "catalog/operator-claim-probes.json";
    private static final String FIXES_RESOURCE = "catalog/operator-fix-probes.json";
    private static final RuleValidator VALIDATOR = new RuleValidator();
    private static final OperatorCatalog CATALOG = OperatorCatalog.load();

    private static ClaimFile claimFile;
    private static String orderJson;
    private static String rawClaimJson;
    private static String priceListJson;
    private static String logisticsJson;
    private static String projectJson;
    private static String subscriptionJson;
    private static String twoRootJson;
    private static String baseYearOrderJson;
    private static String warehouseJson;
    private static String repeatableRootJson;
    private static String dateFragmentsJson;
    private static String germanValueListJson;
    private static String extensibleEnumJson;
    private static String dateRangeFormatsJson;
    private static String fieldValueAsNumberJson;
    private static String enumComparabilityJson;
    private static String basketJson;
    private static String shippingJson;

    @BeforeAll
    static void loadFixtures() throws Exception {
        claimFile = loadClaims();
        orderJson = readResource("models/order.dm.json");
        rawClaimJson = DocumentModelDraft.named("claim").locale("en_US")
                .rootGroup("Claim", root -> root
                        .stringField("IncidentNote", field -> field.lineBreaks().noValueValidation()))
                .build().toJson();
        priceListJson = DocumentModelDraft.named("price-list").locale("en_US")
                .rootGroup("PriceList", root -> root.group("Tiers", tiers -> tiers.repeatable(50)
                        .stringField("Key", field -> { })
                        .stringField("RawBlob", field -> field.lineBreaks().noValueValidation())))
                .build().toJson();
        logisticsJson = ModelDrafts.logistics().toJson();
        basketJson = ModelDrafts.basket().toJson();
        shippingJson = ModelDrafts.shipping().toJson();
        projectJson = readResource("models/project.dm.json");
        subscriptionJson = readResource("models/subscription.dm.json");
        twoRootJson = DocumentModelDraft.named("claimform").locale("en_US")
                .rootGroup("Policy", policy -> policy
                        .field("PolicyNo", FieldKind.STRING)
                        .group("Coverage", coverage -> coverage.field("Amount", FieldKind.NUMBER)))
                .rootGroup("Claim", claim -> claim.field("ClaimNo", FieldKind.STRING))
                .build().toJson();
        baseYearOrderJson = ModelDrafts.order().toJson();
        warehouseJson = DocumentModelDraft.named("warehouse").locale("en_US")
                .rootGroup("Warehouse", warehouse -> warehouse
                        .group("Aisles", aisles -> aisles.repeatable(20)
                                .stringField("Code", field -> { })
                                .group("Bins", bins -> bins.repeatable(20)
                                        .numberField("Quantity", field -> { }))))
                .build().toJson();
        repeatableRootJson = ModelDrafts.repeatableRoot().toJson();
        dateFragmentsJson = ModelDrafts.dateFragments().toJson();
        String german = DocumentModelDraft.named("german-value-list").locale("de_DE")
                .rootGroup("Doc", doc -> doc
                        .stringField("Choice", field -> { })
                        .stringField("Other", field -> { }))
                .build().toJson();
        EditSession germanSession = EditSession.read(german);
        germanSession.config().conditionLanguage("de_DE");
        germanValueListJson = germanSession.toJson();
        extensibleEnumJson = DocumentModelDraft.named("extensible-enum").locale("en_US")
                .rootGroup("Doc", doc -> doc
                        .stringField("Choice", field -> field.suggestions("en_US", "A", "B"))
                        .enumField("EnumChoice", field -> field.value("A").value("B"))
                        .numberField("Count", field -> { }))
                .build().toJson();
        dateRangeFormatsJson = DocumentModelDraft.named("date-range-formats").locale("en_US")
                .rootGroup("Order", order -> order
                        .stringField("SelectedSku", field -> { })
                        .dateField("OrderedOn", field -> field.format("yyyy-MM-dd"))
                        .dateField("DueBy", field -> field.format("dd.MM.yyyy"))
                        .dateField("DateMonth", field -> field.format("MM"))
                        .field("Approved", FieldKind.BOOLEAN)
                        .field("OtherApproved", FieldKind.BOOLEAN)
                        .field("Confirmed", FieldKind.CONFIRM)
                        .field("OtherConfirmed", FieldKind.CONFIRM)
                        .dateFragmentField("OtherFragmentMonth", field -> field.fragmentFormat("MM"))
                        .dateFragmentField("FragmentMonth", field -> field.fragmentFormat("MM"))
                        .dateFragmentField("FragmentYear", field -> field.fragmentFormat("yyyy"))
                        .customField("CustomCode", field -> field.type("CatalogProbeCode"))
                        .customField("OtherCustomCode", field -> field.type("CatalogProbeCode"))
                        .stringField("StringCode", field -> { })
                        .enumField("EnumCode", field -> field.value("A").value("B"))
                        .numberField("Count", field -> { })
                        .dateRangeField("Coverage", field -> field.format("yyyy-MM-dd").separator("/"))
                        .dateRangeField("MonthCoverage", field -> field.format("MM").separator("/"))
                        .group("Items", items -> items.repeatable(20).indexField("Sku")
                                .stringField("Sku", field -> { })
                                .dateField("PromiseDate", field -> field.format("yyyy-MM-dd"))))
                .build().toJson();
        fieldValueAsNumberJson = DocumentModelDraft.named("numeric-tokens").locale("en_US")
                .rootGroup("Tokens", tokens -> tokens
                        .enumField("TrailingPoint", field -> field.value("1."))
                        .enumField("Decimal", field -> field.value("1.5")))
                .build().toJson();
        enumComparabilityJson = ModelDrafts.enumComparability().toJson();
    }

    @TestFactory
    Stream<DynamicTest> everyClaimAndItsDiscriminatingControlRunAgainstTheKernel() {
        return claimFile.claims().stream().map(claim -> dynamicTest(claim.id(), () -> {
            Outcome trigger = execute(claim, claim.trigger());
            Outcome control = execute(claim, claim.control());
            assertOutcome(claim.id() + " trigger", claim.trigger(), trigger);
            assertOutcome(claim.id() + " control", claim.control(), control);
            assertDiscriminating(claim.id(), trigger, control);
        }));
    }

    @Test
    void evidenceDescriptorsAreClosedUniqueAndNameALiveReviewedOperator() {
        assertEquals(1, claimFile.schemaVersion(), "unknown claim-probe schema");
        Set<String> ids = new HashSet<>();
        for (Claim claim : claimFile.claims()) {
            assertTrue(ids.add(claim.id()), () -> "duplicate claim evidence id " + claim.id());
            assertTrue(CATALOG.byId(claim.operator()).isPresent(),
                    () -> claim.id() + " names unknown operator " + claim.operator());
            assertNotNull(claim.control(), () -> claim.id() + " needs a discriminating control");
        }
    }

    @Test
    void theDiscriminationCheckRejectsASameOutcomeMutant() {
        Claim live = claimFile.claims().stream()
                .filter(claim -> claim.id().equals("NoGroupFilled.repeatable-negative"))
                .findFirst().orElseThrow();
        assertNotEquals(expected(live.trigger()), expected(live.control()),
                "trigger and control verdicts must differ before either is executed");
        Outcome outcome = execute(live, live.trigger());
        assertThrows(AssertionFailedError.class,
                () -> assertDiscriminating("same-outcome mutant", outcome, outcome));
    }

    @TestFactory
    Stream<DynamicTest> everyPublishedFixHasAKernelAcceptedRepairWitness() {
        return loadFixes().fixes().stream().map(fix -> dynamicTest(fix.locator().toString(), () ->
                assertAcceptedFix(fix)));
    }

    @Test
    void theFixInventoryIsDerivedContentBoundOrderedAndItsAcceptanceGateDiscriminates() {
        FixFile file = loadFixes();
        List<LiveFix> live = liveFixes();
        List<String> violations = auditFixes(live, file);
        assertTrue(violations.isEmpty(), () -> "catalog fix evidence violations:\n  "
                + String.join("\n  ", violations));

        LiveFix first = live.getFirst();
        assertContains(auditFixes(live.subList(1, live.size()), file), "stale fix probe");
        assertContains(auditFixes(live, new FixFile(1, file.fixes().subList(1, file.fixes().size()))),
                "missing fix probe");
        FixProbe changed = new FixProbe(file.fixes().getFirst().locator(), "sha256:changed",
                file.fixes().getFirst().fixture(), file.fixes().getFirst().group(),
                file.fixes().getFirst().field(), file.fixes().getFirst().dsl());
        List<FixProbe> changedFile = new ArrayList<>(file.fixes());
        changedFile.set(0, changed);
        assertContains(auditFixes(live, new FixFile(1, changedFile)), "digest mismatch");

        OperatorEntry.InvalidExample invalid = CATALOG.byId(first.locator().operator()).orElseThrow().invalidExample();
        FixProbe liveProbe = file.fixes().getFirst();
        FixProbe mutant = new FixProbe(liveProbe.locator(), liveProbe.digest(), liveProbe.fixture(),
                liveProbe.group(), liveProbe.field(), invalid.dsl());
        assertThrows(AssertionFailedError.class, () -> assertAcceptedFix(mutant),
                "the acceptance guard must reject the real pre-repair DSL");
    }

    @Test
    void theKernelRejectsTheHistoricalDayRepairWitness() {
        FixProbe live = loadFixes().fixes().stream()
                .filter(fix -> fix.locator().equals(new FixLocator("DayFromDate", FixSource.CORRECTIVE, 0)))
                .findFirst().orElseThrow();
        FixProbe preFix = new FixProbe(live.locator(), live.digest(), Fixture.ORDER,
                "/Order", "../ApproxDate",
                "FieldFilled(ApproxDate) And DayFromDate(ValueAsDate(ApproxDate, FirstDay)) > 1");
        assertThrows(AssertionFailedError.class, () -> assertAcceptedFix(preFix),
                "the exact nested ValueAsDate repair advertised before CG2 must remain a negative control");
    }

    static Descriptor descriptor(String id, boolean control) {
        Claim claim = loadClaims().claims().stream().filter(candidate -> candidate.id().equals(id))
                .findFirst().orElse(null);
        if (claim == null) {
            return null;
        }
        return descriptor(claim, control ? claim.control() : claim.trigger());
    }

    static Set<String> claimProbeIds() {
        return loadClaims().claims().stream().map(Claim::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Descriptor descriptor(Claim claim, Probe probe) {
        return new Descriptor(claim.operator(), claim.kind(), probe.verdict(), probe.code());
    }

    private static Outcome execute(Claim claim, Probe probe) {
        Fixture fixture = probe.fixture() == null ? claim.fixture() : probe.fixture();
        ValidationResult result;
        if (claim.kind() == Oracle.CONDITION) {
            result = VALIDATOR.check(modelFor(fixture), new DraftRule(
                    probe.group(), "CatalogClaimProbe", probe.field(), probe.dsl(), "CATALOG_CLAIM", "ERROR"));
        } else if (claim.kind() == Oracle.COMPUTATION) {
            DraftComputation computation = new DraftComputation(probe.group(), "CatalogClaimProbe",
                    probe.field(), null, List.of(new DraftComputation.Alternative(probe.precondition(), probe.dsl())));
            result = VALIDATOR.check(modelFor(fixture), computation);
        } else {
            throw new IllegalStateException("unknown oracle " + claim.kind());
        }
        List<String> codes = result.diagnostics().stream().flatMap(diagnostic -> diagnostic.code().stream()).toList();
        return new Outcome(result.ok(), codes);
    }

    private static void assertAcceptedFix(FixProbe fix) {
        ValidationResult result = VALIDATOR.check(modelFor(fix.fixture()), new DraftRule(
                fix.group(), "CatalogFixProbe", fix.field(), fix.dsl(), "CATALOG_FIX", "ERROR"));
        assertTrue(result.ok(), () -> fix.locator() + " advertised repair was rejected: "
                + result.diagnostics());
    }

    private static List<LiveFix> liveFixes() {
        List<LiveFix> result = new ArrayList<>();
        for (OperatorEntry operator : CATALOG.entries()) {
            if (operator.invalidExample() != null && operator.invalidExample().fix() != null
                    && !operator.invalidExample().fix().isBlank()) {
                result.add(new LiveFix(new FixLocator(operator.id(), FixSource.INVALID_EXAMPLE, 0),
                        operator.invalidExample().fix()));
            }
            for (int i = 0; i < operator.correctives().size(); i++) {
                result.add(new LiveFix(new FixLocator(operator.id(), FixSource.CORRECTIVE, i),
                        operator.correctives().get(i).fix()));
            }
        }
        return List.copyOf(result);
    }

    private static List<String> auditFixes(List<LiveFix> live, FixFile file) {
        List<String> violations = new ArrayList<>();
        if (file.schemaVersion() != 1) {
            violations.add("unsupported fix schemaVersion " + file.schemaVersion());
        }
        Map<FixLocator, LiveFix> liveByLocator = new LinkedHashMap<>();
        for (LiveFix fix : live) {
            if (liveByLocator.putIfAbsent(fix.locator(), fix) != null) {
                violations.add(fix.locator() + " duplicate live fix locator");
            }
        }
        Map<FixLocator, FixProbe> probes = new LinkedHashMap<>();
        for (FixProbe probe : file.fixes()) {
            if (probes.putIfAbsent(probe.locator(), probe) != null) {
                violations.add(probe.locator() + " duplicate fix probe");
            }
        }
        live.forEach(fix -> {
            FixProbe probe = probes.get(fix.locator());
            if (probe == null) {
                violations.add(fix.locator() + " missing fix probe");
            } else if (!digest(fix.text()).equals(probe.digest())) {
                violations.add(fix.locator() + " digest mismatch");
            }
        });
        probes.keySet().stream().filter(locator -> !liveByLocator.containsKey(locator))
                .forEach(locator -> violations.add(locator + " stale fix probe"));
        if (!live.stream().map(LiveFix::locator).toList()
                .equals(file.fixes().stream().map(FixProbe::locator).toList())) {
            violations.add("fix probes must follow canonical live order");
        }
        return violations;
    }

    private static void assertContains(List<String> violations, String fragment) {
        assertTrue(violations.stream().anyMatch(value -> value.contains(fragment)),
                () -> "expected violation containing '" + fragment + "', got " + violations);
    }

    private static void assertOutcome(String at, Probe expected, Outcome actual) {
        switch (expected.verdict()) {
            case ACCEPTED -> assertTrue(actual.accepted(), () -> at + " unexpectedly produced " + actual.codes());
            case ACCEPTED_WITHOUT_CODE -> {
                assertTrue(actual.accepted(), () -> at + " unexpectedly produced " + actual.codes());
                assertFalse(actual.codes().contains(expected.code()),
                        () -> at + " unexpectedly produced absent code " + expected.code());
            }
            case REJECTED -> {
                assertFalse(actual.accepted(), () -> at + " was unexpectedly accepted");
                assertEquals(List.of(expected.code()), actual.codes(),
                        () -> at + " reached competing gates");
            }
        }
    }

    private static void assertDiscriminating(String id, Outcome trigger, Outcome control) {
        assertNotEquals(trigger, control, () -> id + " control does not discriminate the measured outcome");
    }

    private static Expected expected(Probe probe) {
        return new Expected(probe.verdict(), probe.code());
    }

    private static ClaimFile loadClaims() {
        ObjectMapper mapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        try (InputStream in = CatalogKernelClaimEvidenceTest.class.getClassLoader()
                .getResourceAsStream(CLAIMS_RESOURCE)) {
            assertNotNull(in, CLAIMS_RESOURCE + " not found");
            return mapper.readValue(in, ClaimFile.class);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load " + CLAIMS_RESOURCE, e);
        }
    }

    private static FixFile loadFixes() {
        ObjectMapper mapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        try (InputStream in = CatalogKernelClaimEvidenceTest.class.getClassLoader()
                .getResourceAsStream(FIXES_RESOURCE)) {
            assertNotNull(in, FIXES_RESOURCE + " not found");
            return mapper.readValue(in, FixFile.class);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load " + FIXES_RESOURCE, e);
        }
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = CatalogKernelClaimEvidenceTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, path + " fixture not found");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String modelFor(Fixture fixture) {
        return switch (fixture) {
            case ORDER -> orderJson;
            case RAW_CLAIM -> rawClaimJson;
            case PRICE_LIST -> priceListJson;
            case LOGISTICS -> logisticsJson;
            case PROJECT -> projectJson;
            case BASKET -> basketJson;
            case SHIPPING -> shippingJson;
            case SUBSCRIPTION -> subscriptionJson;
            case TWO_ROOT -> twoRootJson;
            case BASE_YEAR_ORDER -> baseYearOrderJson;
            case WAREHOUSE -> warehouseJson;
            case REPEATABLE_ROOT -> repeatableRootJson;
            case DATE_FRAGMENTS -> dateFragmentsJson;
            case GERMAN_VALUE_LIST -> germanValueListJson;
            case EXTENSIBLE_ENUM -> extensibleEnumJson;
            case DATE_RANGE_FORMATS -> dateRangeFormatsJson;
            case FIELD_VALUE_AS_NUMBER -> fieldValueAsNumberJson;
            case ENUM_COMPARABILITY -> enumComparabilityJson;
        };
    }

    private static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    enum Oracle {
        CONDITION,
        COMPUTATION
    }

    enum Fixture {
        ORDER,
        RAW_CLAIM,
        PRICE_LIST,
        LOGISTICS,
        PROJECT,
        SUBSCRIPTION,
        TWO_ROOT,
        BASE_YEAR_ORDER,
        WAREHOUSE,
        REPEATABLE_ROOT,
        DATE_FRAGMENTS,
        GERMAN_VALUE_LIST,
        EXTENSIBLE_ENUM,
        DATE_RANGE_FORMATS,
        FIELD_VALUE_AS_NUMBER,
        ENUM_COMPARABILITY,
        BASKET,
        SHIPPING
    }

    enum Verdict {
        ACCEPTED,
        ACCEPTED_WITHOUT_CODE,
        REJECTED
    }

    record Descriptor(String operator, Oracle oracle, Verdict verdict, String code) {
    }

    record ClaimFile(int schemaVersion, List<Claim> claims) {
        ClaimFile {
            claims = List.copyOf(claims);
        }
    }

    record Claim(String id, String operator, Oracle kind, Fixture fixture, Probe trigger, Probe control) {
    }

    enum FixSource { INVALID_EXAMPLE, CORRECTIVE }

    record FixLocator(String operator, FixSource source, int ordinal) { }

    record FixFile(int schemaVersion, List<FixProbe> fixes) {
        FixFile { fixes = List.copyOf(fixes); }
    }

    record FixProbe(FixLocator locator, String digest, Fixture fixture, String group, String field, String dsl) { }

    record LiveFix(FixLocator locator, String text) { }

    record Probe(String group, String field, String dsl, Verdict verdict, String code, Fixture fixture,
            String precondition) {
    }

    private record Outcome(boolean accepted, List<String> codes) {
    }

    private record Expected(Verdict verdict, String code) {
    }
}
