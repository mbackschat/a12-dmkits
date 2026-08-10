package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The {@code operators} verb — the CLI exposing the DSL operator catalog so an agent can explore the
 * vocabulary on demand (CLI-SPEC §1a, the self-explaining requirement; answers "can the LLM browse
 * available operators?"). List (filterable) + one operator's full semantics.
 */
class OperatorsVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void listsTheCatalog() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("operators");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertTrue(j.path("count").asInt() > 0, () -> "non-empty catalog: " + res.out());
        boolean hasFieldFilled = false;
        for (JsonNode op : j.path("operators")) {
            if ("FieldFilled".equals(op.path("id").asText())) {
                hasFieldFilled = true;
                assertTrue(!op.path("meaning").asText().isBlank(), "each entry carries its meaning");
            }
        }
        assertTrue(hasFieldFilled, "lists a known operator (FieldFilled)");
    }

    @Test
    void oneOperatorCarriesFullSemantics() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("operators", "DifferenceInDays");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertEquals("DifferenceInDays", j.path("id").asText());
        assertEquals("FUNCTION", j.path("kind").asText());
        assertEquals("DifferenceInDays", j.path("keyword").path("en").asText());
        assertTrue(!j.path("keyword").path("de").asText().isBlank(), "carries the DE keyword too");
        assertTrue(!j.path("meaning").asText().isBlank(), "carries the meaning");
    }

    @Test
    void servesTheDslAuthoringBridge() throws Exception {
        // G3: an operator's full view names the Java entry point(s) that render it — SemanticIndex names
        // BOTH arms (the presence/coercion handle + the value read), so an agent crosses from operator to call.
        JsonNode si = MAPPER.readTree(CliTestSupport.run("operators", "SemanticIndex").out());
        java.util.Set<String> members = new java.util.HashSet<>();
        for (JsonNode m : si.path("dslEntry")) {
            members.add(m.asText());
        }
        assertTrue(members.contains("semanticIndex") && members.contains("forIndex"),
                () -> "SemanticIndex names both arms: " + si.path("dslEntry"));

        // a constant with no standalone factory serves an explicit empty list + a dslEntryNote reason,
        // never a silent absence.
        JsonNode t = MAPPER.readTree(CliTestSupport.run("operators", "True").out());
        assertTrue(t.path("dslEntry").isArray() && t.path("dslEntry").isEmpty(),
                () -> "True has no factory — explicit empty dslEntry: " + t);
        assertTrue(!t.path("dslEntryNote").asText().isBlank(),
                () -> "True's empty dslEntry carries its reason: " + t);
    }

    @Test
    void servesTheFieldFillPhaseBoundary() throws Exception {
        CliTestSupport.Result result = CliTestSupport.run("operators", "NotExactlyOneFieldFilled");
        assertEquals(0, result.code(), result::err);
        JsonNode operator = MAPPER.readTree(result.out());
        String gotchas = operator.path("gotchas").toString();
        assertTrue(gotchas.contains("validation"), () -> "missing validation contract: " + operator);
        assertTrue(gotchas.contains("computation"), () -> "missing computation contract: " + operator);
        assertTrue(gotchas.contains("restarts"), () -> "missing staged-scan restart: " + operator);
    }

    @Test
    void servesTheFieldValuesNotUniqueAggregateMessageScope() throws Exception {
        CliTestSupport.Result result = CliTestSupport.run("operators", "FieldValuesNotUnique");
        assertEquals(0, result.code(), result::err);
        JsonNode gotchas = MAPPER.readTree(result.out()).path("gotchas");
        JsonNode scope = null;
        for (JsonNode gotcha : gotchas) {
            if (gotcha.path("text").asText().contains("one aggregate verdict")) {
                scope = gotcha;
                break;
            }
        }
        assertTrue(scope != null, () -> "missing aggregate message-scope gotcha: " + gotchas);
        JsonNode aggregateScope = scope;
        assertEquals("SURPRISE", aggregateScope.path("kind").asText());
        assertTrue(aggregateScope.path("text").asText().contains("first row"),
                () -> "missing first-row anchor: " + aggregateScope);
        assertTrue(aggregateScope.path("text").asText().contains("RepetitionNotUnique"),
                () -> "missing per-duplicate-row alternative: " + aggregateScope);
    }

    @Test
    void servesTheCorrectedNumberOfFilledGroupsOperandForms() throws Exception {
        JsonNode operator = MAPPER.readTree(CliTestSupport.run("operators", "NumberOfFilledGroups").out());
        String authoring = operator.path("constraints").toString() + operator.path("gotchas");

        assertTrue(authoring.contains("mixed") && authoring.contains("Items*, ShippingAddress"),
                () -> "the mixed starred/fixed form must be discoverable: " + operator);
        assertTrue(authoring.contains("repeatable") && authoring.contains("MVK_INVALID_WILDCARD"),
                () -> "starring a non-repeatable group must be refused explicitly: " + operator);
    }

    @Test
    void servesConfirmAsAFieldValuesNotUniqueKindRefusal() throws Exception {
        JsonNode operator = MAPPER.readTree(CliTestSupport.run("operators", "FieldValuesNotUnique").out());
        assertTrue(operator.path("constraints").toString().contains("Confirm"),
                () -> "the kind constraint must include Confirm: " + operator);
        assertTrue(operator.path("correctives").toString().contains("Confirm"),
                () -> "the corrective must include Confirm: " + operator);
    }

    @Test
    void servesTheExactRawLengthConstantDomain() throws Exception {
        JsonNode operator = MAPPER.readTree(CliTestSupport.run("operators", "Length").out());
        assertTrue(operator.path("gotchas").toString()
                        .contains("decimal-point-free signed-32-bit integer constant"),
                () -> "the raw Length integer domain must be explicit: " + operator);
    }

    @Test
    void servesPatternSlotCorrectivesForBothOperators() throws Exception {
        for (String id : java.util.List.of("PatternMatched", "PatternViolated")) {
            JsonNode correctives = MAPPER.readTree(CliTestSupport.run("operators", id).out()).path("correctives");
            assertTrue(correctives.toString().contains("MVK_MISSING_CONST_IN_PATTERN_COMP"),
                    () -> id + " must correct a field in the regex slot: " + correctives);
            assertTrue(correctives.toString().contains("MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON"),
                    () -> id + " must correct wrong operand types: " + correctives);
        }
    }

    @Test
    void unknownOperatorFails() {
        CliTestSupport.Result res = CliTestSupport.run("operators", "NoSuchOp");
        assertEquals(2, res.code());
        assertTrue(res.err().contains("no operator"), res::err);
    }

    // --- the machine tier (CATALOG-SPEC §7): the emptyOperand facet, correctives, gotcha kinds ---

    @Test
    void servesTheEmptyOperandFacetAndClassifiedGotchas() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("operators", "Length");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        JsonNode claim = j.path("semantics").path("emptyOperand").get(0);
        assertEquals("0", claim.path("operand").asText());
        assertEquals("AS_ZERO", claim.path("behavior").asText(),
                () -> "Length's empty-as-0 facet must be served: " + res.out());
        assertTrue(!claim.path("basis").asText().isBlank(), "the claim's basis (its lock) travels");
        assertTrue(claim.path("probes").isMissingNode(), "probes are CI detail, not consumer payload");
        // gotchas travel WITH their kind (previously dropped)
        JsonNode gotcha = j.path("gotchas").get(0);
        assertTrue(!gotcha.path("text").asText().isBlank(), () -> "gotcha object with text: " + res.out());
        assertTrue(!gotcha.path("kind").asText().isBlank(), () -> "gotcha kind travels: " + res.out());
    }

    @Test
    void servesPerKindClaimsAndCorrectives() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("operators", "Min");
        assertEquals(0, res.code(), res::err);
        JsonNode perKind = MAPPER.readTree(res.out()).path("semantics").path("emptyOperand").get(0).path("perKind");
        assertEquals("AS_ZERO", perKind.path("number").asText());
        assertEquals("SKIPPED", perKind.path("date").asText());

        CliTestSupport.Result lt = CliTestSupport.run("operators", "LessThan");
        assertEquals(0, lt.code(), lt::err);
        JsonNode correctives = MAPPER.readTree(lt.out()).path("correctives");
        assertEquals(2, correctives.size(), () -> "both operand-conditional fixes served: " + lt.out());
        assertTrue(correctives.get(0).path("whenOperands").isArray());
        assertTrue(!correctives.get(0).path("fix").asText().isBlank());

        CliTestSupport.Result hours = CliTestSupport.run("operators", "HoursFromTime");
        assertEquals(0, hours.code(), hours::err);
        JsonNode hour = MAPPER.readTree(hours.out());
        assertTrue(hour.path("gotchas").get(0).path("seeAlso").toString().contains("MinutesFromTime"),
                () -> "gotcha-local related operators must not be dropped: " + hours.out());
        assertTrue(hour.path("correctives").toString().contains("MVK_WRONG_DATE_FORMAT_FOR_OP"),
                () -> "secondary executable rejections must be served: " + hours.out());
    }

    /**
     * The WI-5b dimensions ride the same machine tier (CATALOG-SPEC §3a–§3d) — this is the eval judge's
     * ground-truth source (it consults {@code operators <id>} for per-operator semantics), so the
     * serving must expose each dimension's {@code value}/{@code perKind} + {@code basis}, probes elided.
     */
    @Test
    void servesTheWi5bDimensions() throws Exception {
        // boundary — the tolerance band is STRICT
        JsonNode tol = sem(CliTestSupport.run("operators", "DiffersWithToleranceRange1")).path("boundary");
        assertEquals("STRICT", tol.path("value").asText(), "boundary served");
        assertTrue(!tol.path("basis").asText().isBlank(), "boundary basis travels");
        assertTrue(tol.path("probes").isMissingNode(), "probes are CI detail, not consumer payload");

        // iterationRange — AllFieldsFilled scans the DECLARED range
        assertEquals("DECLARED",
                sem(CliTestSupport.run("operators", "AllFieldsFilled")).path("iterationRange").path("value").asText());

        // aggregateIdentity — Sum folds to ZERO; Min is per-kind (number ZERO, date NO_VALUE)
        assertEquals("ZERO",
                sem(CliTestSupport.run("operators", "Sum")).path("aggregateIdentity").path("value").asText());
        JsonNode aiMin = sem(CliTestSupport.run("operators", "Min")).path("aggregateIdentity").path("perKind");
        assertEquals("ZERO", aiMin.path("number").asText());
        assertEquals("NO_VALUE", aiMin.path("date").asText());

        // polarity — the date extractor is SYMMETRIC, Length is GROW_ONLY
        assertEquals("SYMMETRIC",
                sem(CliTestSupport.run("operators", "YearFromDate")).path("polarity").path("value").asText());
        assertEquals("GROW_ONLY",
                sem(CliTestSupport.run("operators", "Length")).path("polarity").path("value").asText());
    }

    /** The {@code semantics} node of an {@code operators <id>} response (asserts the call succeeded). */
    private static JsonNode sem(CliTestSupport.Result res) throws Exception {
        assertEquals(0, res.code(), res::err);
        return MAPPER.readTree(res.out()).path("semantics");
    }

    @Test
    void bareListCarriesTheEmptyOperandDefaults() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("operators");
        assertEquals(0, res.code(), res::err);
        JsonNode defaults = MAPPER.readTree(res.out()).path("emptyOperandDefaults");
        assertEquals("AS_ZERO", defaults.path("number").asText());
        assertEquals("NOT_EVALUATED", defaults.path("string").asText());
        assertEquals("AS_FALSE", defaults.path("confirm").asText());
    }

    @Test
    void bareListMapsCatalogIdsToTheirAuthoredKeywords() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("operators");
        assertEquals(0, res.code(), res::err);
        JsonNode validType = java.util.stream.StreamSupport.stream(
                        MAPPER.readTree(res.out()).path("operators").spliterator(), false)
                .filter(operator -> "ValidType".equals(operator.path("id").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ValidType is absent from the operator list"));
        assertEquals("Valid", validType.path("keyword").asText(),
                "the compact list must expose the authored token when it differs from the catalog id");
    }

    @Test
    void filtersByKind() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("operators", "--kind", "PREDICATE");
        assertEquals(0, res.code(), res::err);
        JsonNode ops = MAPPER.readTree(res.out()).path("operators");
        assertTrue(ops.size() > 0, "some predicates exist");
        for (Iterator<JsonNode> it = ops.elements(); it.hasNext();) {
            assertEquals("PREDICATE", it.next().path("kind").asText());
        }
    }

    @Test
    void keywordIsATopicalFilterMatchingMeaningNotJustTheKeyword() throws Exception {
        // eval finding 01b: --keyword is a TOPICAL filter (keyword OR meaning), so `--keyword date` surfaces the
        // date-arithmetic ops whose keyword has no "date" (DifferenceInDays/AddYears) but whose meaning does —
        // not only those with "date" literally in the keyword.
        CliTestSupport.Result res = CliTestSupport.run("operators", "--keyword", "date");
        assertEquals(0, res.code(), res::err);
        boolean hasArithmetic = false;
        for (Iterator<JsonNode> it = MAPPER.readTree(res.out()).path("operators").elements(); it.hasNext();) {
            String id = it.next().path("id").asText();
            hasArithmetic |= "DifferenceInDays".equals(id) || "AddYears".equals(id);
        }
        assertTrue(hasArithmetic, () -> "--keyword date surfaces the date-arithmetic family via meaning: " + res.out());
    }

    @Test
    void aNonIdPositionalFiltersTheCatalogBySubstring() throws Exception {
        // G13: an agent's natural reach — `operators <substring>` — narrows the list instead of failing
        // (an exact id still gives the full single operator; a non-id positional is a substring needle)
        CliTestSupport.Result res = CliTestSupport.run("operators", "Difference");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertTrue(j.has("operators"), () -> "a substring returns the LIST shape, not one full operator: " + res.out());
        assertTrue(j.path("count").asInt() >= 2, () -> "lists every match: " + res.out());
        boolean hasDays = false;
        boolean hasYears = false;
        for (Iterator<JsonNode> it = j.path("operators").elements(); it.hasNext();) {
            String id = it.next().path("id").asText();
            hasDays |= "DifferenceInDays".equals(id);
            hasYears |= "DifferenceInYears".equals(id);
        }
        assertTrue(hasDays && hasYears, () -> "the substring surfaces the matching operators: " + res.out());
    }

    @Test
    void searchingTheConceptWordFindsTheCountingOperators() throws Exception {
        // The report (#120) hit `operators count` returning RoundAccounting (substring "count" in "Accounting")
        // but NOT the real counters, whose names say "NumberOf…" not "count". Discovery is by the natural word,
        // so the counters' MEANING now carries "count" — the positional search matches id/keyword/meaning.
        CliTestSupport.Result res = CliTestSupport.run("operators", "count");
        assertEquals(0, res.code(), res::err);
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Iterator<JsonNode> it = MAPPER.readTree(res.out()).path("operators").elements(); it.hasNext();) {
            ids.add(it.next().path("id").asText());
        }
        for (String counter : new String[]{"NumberOfFilledFields", "NumberOfFilledGroups",
                "NumberOfDifferentValues", "NumberOfValueInFields"}) {
            assertTrue(ids.contains(counter),
                    () -> "`operators count` must surface the counting operator " + counter + ": " + ids);
        }
    }

    @Test
    void aSubstringComposesWithKind() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("operators", "filled", "--kind", "PREDICATE");
        assertEquals(0, res.code(), res::err);
        JsonNode ops = MAPPER.readTree(res.out()).path("operators");
        assertTrue(ops.size() > 0, () -> "filled predicates exist: " + res.out());
        for (Iterator<JsonNode> it = ops.elements(); it.hasNext();) {
            assertEquals("PREDICATE", it.next().path("kind").asText(), "the --kind filter still applies");
        }
    }
}
