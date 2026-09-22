package io.github.mbackschat.a12.dm.cli.computation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * The computation-spec's optional {@code group} — declaring a computation outside its computed field's parent
 * group, over the CLI. The acceptance matrix of the 2026-08-29 a12-kernel-lean request, whose reproducer this
 * fixture reproduces: a root with two fixed sibling subtrees, a stock level in one and a rules container in
 * the other.
 *
 * <p>The kernel's own verdict on the shape is {@code validate.laws.ComputationDeclaringGroupLawsTest}; what is
 * checked here is the CLI contract on top of it — that the key reaches the model, that the placement is
 * reported back, that omitting it changes nothing, and that a group that does not resolve is refused inside
 * the result envelope rather than as a bare stderr line.
 */
class ComputationDeclaringGroupCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String STOCK_LEVEL = "/Probe/Store/StockLevel";
    private static final String RULES_GROUP = "/Probe/Rules";
    private static final String CROSS_GROUP_COMPUTATION = "/Probe/Rules/StockComp";

    private static final String CROSS_GROUP_SPEC = """
            { "computedField": "%s",
              "group": "%s",
              "name": "StockComp",
              "alternatives": [ { "operation": "-1" } ],
              "messages": [ {"locale":"en_US","text":"Stock level is a constant probe value."} ] }
            """.formatted(STOCK_LEVEL, RULES_GROUP);

    /** The reporter's model: /Probe with fixed child groups /Probe/Store and /Probe/Rules. */
    private static Path probeModel(Path dir, String name) throws Exception {
        String json = DocumentModelDraft.named("Probe_DM").locale("en_US")
                .rootGroup("Probe", p -> p
                        .group("Store", s -> s.numberField("StockLevel", n -> n.fractionDigits(0)))
                        .group("Rules", r -> r.numberField("Marker", n -> n.fractionDigits(0))))
                .build().toJson();
        Path model = dir.resolve(name);
        Files.writeString(model, json);
        return model;
    }

    private static Path write(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    /** Criterion 1 — the cross-group spec previews kernel-confirmed, then persists. */
    @Test
    void aDeclaredGroupPreviewsKernelConfirmedAndThenPersists(@TempDir Path dir) throws Exception {
        Path model = probeModel(dir, "Probe_DM.json");
        Path spec = write(dir, "comp.json", CROSS_GROUP_SPEC);
        String before = Files.readString(model);

        CliTestSupport.Result preview = CliTestSupport.run(
                "-m", model.toString(), "computation", "add", spec.toString(), "--dry-run");
        assertEquals(0, preview.code(), () -> preview.err() + preview.out());
        JsonNode previewEnv = MAPPER.readTree(preview.out());
        assertEquals("preview", previewEnv.path("outcome").asText(), preview::out);
        assertEquals("KERNEL_CONFIRMED", previewEnv.path("verification").asText(), preview::out);
        assertEquals(CROSS_GROUP_COMPUTATION, previewEnv.path("changed").path("computation").asText());
        assertEquals(before, Files.readString(model), "--dry-run writes nothing");

        CliTestSupport.Result applied = CliTestSupport.run(
                "-m", model.toString(), "computation", "add", spec.toString());
        assertEquals(0, applied.code(), () -> applied.err() + applied.out());
        JsonNode appliedEnv = MAPPER.readTree(applied.out());
        assertEquals("applied", appliedEnv.path("outcome").asText(), applied::out);
        assertTrue(appliedEnv.path("written").asBoolean(), applied::out);

        assertEquals("../../Store/StockLevel", storedRelPath(model, CROSS_GROUP_COMPUTATION),
                "the element sits in /Probe/Rules, so the stored reference climbs out and back down");
    }

    /** Criterion 2 — the read reports where the computation LIVES distinctly from where it WRITES. */
    @Test
    void theReadReportsTheDeclaringGroupDistinctlyFromTheCalculatedField(@TempDir Path dir) throws Exception {
        Path model = probeModel(dir, "Probe_DM.json");
        CliTestSupport.run("-m", model.toString(), "computation", "add",
                write(dir, "comp.json", CROSS_GROUP_SPEC).toString());

        CliTestSupport.Result read = CliTestSupport.run(
                "-m", model.toString(), "computation", "read", CROSS_GROUP_COMPUTATION);

        assertEquals(0, read.code(), () -> read.err() + read.out());
        JsonNode data = MAPPER.readTree(read.out()).path("data");
        assertEquals(RULES_GROUP, data.path("group").asText(), read::out);
        assertEquals(STOCK_LEVEL, data.path("calculatedField").asText(), read::out);
    }

    /** The round trip the reporter could not close: re-express the cross-group computation in place. */
    @Test
    void theCrossGroupComputationSurvivesAModifyRoundTrip(@TempDir Path dir) throws Exception {
        Path model = probeModel(dir, "Probe_DM.json");
        CliTestSupport.run("-m", model.toString(), "computation", "add",
                write(dir, "comp.json", CROSS_GROUP_SPEC).toString());
        Path respec = write(dir, "comp2.json", CROSS_GROUP_SPEC.replace("\"-1\"", "\"-2\""));

        CliTestSupport.Result modified = CliTestSupport.run("-m", model.toString(), "computation", "modify",
                CROSS_GROUP_COMPUTATION, "--spec", respec.toString());

        assertEquals(0, modified.code(), () -> modified.err() + modified.out());
        assertEquals("applied", MAPPER.readTree(modified.out()).path("outcome").asText(), modified::out);
        assertEquals("../../Store/StockLevel", storedRelPath(model, CROSS_GROUP_COMPUTATION),
                "a re-expression must not relocate the computation");
    }

    /**
     * Criterion 3 — omitting {@code group} is exactly today's behaviour.
     *
     * <p>The control is within-model: the same spec authored twice, differing in nothing but whether it names
     * the computed field's own parent explicitly. Byte equality of the two persisted models is what shows the
     * default path is unchanged rather than merely equivalent-looking.
     */
    @Test
    void omittingTheGroupIsByteIdenticalToNamingTheComputedFieldsParent(@TempDir Path dir) throws Exception {
        String withoutGroup = """
                { "computedField": "%s",
                  "name": "StockComp",
                  "alternatives": [ { "operation": "-1" } ],
                  "messages": [ {"locale":"en_US","text":"Stock level is a constant probe value."} ] }
                """.formatted(STOCK_LEVEL);
        String withParentGroup = withoutGroup.replace("\"name\"", "\"group\": \"/Probe/Store\",\n  \"name\"");

        Path implicit = probeModel(dir, "implicit.dm.json");
        Path explicit = probeModel(dir, "explicit.dm.json");
        CliTestSupport.run("-m", implicit.toString(), "computation", "add",
                write(dir, "implicit.json", withoutGroup).toString());
        CliTestSupport.run("-m", explicit.toString(), "computation", "add",
                write(dir, "explicit.json", withParentGroup).toString());

        assertEquals("../StockLevel", storedRelPath(implicit, "/Probe/Store/StockComp"),
                "the default placement still stores the plain '../<Field>' reference");
        assertEquals(Files.readString(implicit), Files.readString(explicit),
                "naming the computed field's own parent must change nothing at all");
    }

    /**
     * An operand REFERENCE resolves against the declaring group, not the computed field's parent — the half a
     * constant operation cannot show, and the one an author gets wrong first.
     *
     * <p>Both rows use the same target and the same declaring group and differ only in how the operand is
     * written: the bare short name binds inside {@code /Probe/Rules} (where {@code Marker} lives, so it is
     * accepted), while the same spec asking for the STORE's field by short name has nothing to bind to there.
     * Without this pair, "the base the operand paths render against" is an unexercised claim.
     */
    @Test
    void anOperandReferenceResolvesAgainstTheDeclaringGroup(@TempDir Path dir) throws Exception {
        Path model = probeModel(dir, "Probe_DM.json");
        String spec = """
                { "computedField": "%s",
                  "group": "%s",
                  "name": "StockComp",
                  "alternatives": [ { "operation": "[%s]" } ],
                  "messages": [ {"locale":"en_US","text":"Stock level mirrors the marker."} ] }
                """;

        CliTestSupport.Result inGroup = CliTestSupport.run("-m", model.toString(), "computation", "add",
                write(dir, "in-group.json", spec.formatted(STOCK_LEVEL, RULES_GROUP, "Marker")).toString(),
                "--dry-run");
        assertEquals(0, inGroup.code(),
                () -> "a bare name binds inside the DECLARING group: " + inGroup.err() + inGroup.out());
        assertEquals("KERNEL_CONFIRMED", MAPPER.readTree(inGroup.out()).path("verification").asText());

        CliTestSupport.Result crossGroup = CliTestSupport.run("-m", model.toString(), "computation", "add",
                write(dir, "cross-group.json", spec.formatted(STOCK_LEVEL, RULES_GROUP, "StockLevel")).toString(),
                "--dry-run");
        assertEquals("rejected", MAPPER.readTree(crossGroup.out()).path("outcome").asText(),
                () -> "the computed field's own group is NOT the resolution base: " + crossGroup.out());
    }

    /**
     * A yard whose bins, primary and secondary extents are three repeatable siblings under one fixed
     * intermediate group — so a declaring group can be the target's own parent, a NON-root ancestor, or the
     * root, and a stale {@code ../} spelling can be tried against each.
     */
    private static Path yardModel(Path dir) throws Exception {
        String json = DocumentModelDraft.named("Warehouse_DM").locale("en_US")
                .rootGroup("Warehouse", w -> w
                        .group("Yard", y -> y
                                .group("Bins", b -> b.repeatable(20)
                                        .numberField("SelectedCount", n -> n.fractionDigits(0)))
                                .group("Primary", p -> p.repeatable(20)
                                        .numberField("Candidate", n -> n.fractionDigits(0)))
                                .group("Secondary", s -> s.repeatable(20)
                                        .numberField("Fallback", n -> n.fractionDigits(0))))
                        .group("Office", o -> o.numberField("OfficeMarker", n -> n.fractionDigits(0))))
                .build().toJson();
        Path model = dir.resolve("Warehouse_DM.json");
        Files.writeString(model, json);
        return model;
    }

    /**
     * <b>The operand's base MOVES with the declaring group, and a stale relative spelling misattributes on two
     * different codes.</b> This is the trap the containment widening exposes: an ancestor placement is admitted
     * (KERNEL-FINDINGS §11), so an author who moves a computation upward keeps a working-looking spec whose
     * {@code ../} now counts from somewhere else.
     *
     * <p>Which code you get depends on where the stale walk lands, which is why both ancestors are measured
     * rather than one: from the non-root {@code /Warehouse/Yard} the walk stays inside the tree and resolves to
     * a group that does not exist ({@code MVK_INVALID_ENTITY}); from the root it walks off the top of the model
     * instead and is refused earlier, on arity ({@code MVK_TOO_MANY_PARENT_GROUPS}). a12-kernel-lean's
     * {@code SPEC-2026-08-29-02} reports only the first, because its ancestor was not a root.
     *
     * <p>The re-spelled row is the control: the same placement, the same target, an operand written for the new
     * base — admitted. So the refusals are the SPELLING's and never the placement's.
     */
    @Test
    void aStaleRelativeOperandMisattributesOnTwoCodesWhenTheDeclaringGroupMovesUp(@TempDir Path dir)
            throws Exception {
        Path model = yardModel(dir);
        String spec = """
                { "computedField": "/Warehouse/Yard/Bins/SelectedCount",
                  "group": "%s",
                  "name": "SelectedComp",
                  "alternatives": [ { "operation": "FirstFilledValue(%s)" } ],
                  "messages": [ {"locale":"en_US","text":"The selected count follows the first filled extent."} ] }
                """;
        String stale = "../Primary*/Candidate, ../Secondary*/Fallback";

        record Row(String label, String group, String operand) { }
        List<Row> rows = List.of(
                new Row("the base the operand was written for", "/Warehouse/Yard/Bins", stale),
                new Row("a NON-root ancestor, spelling unchanged", "/Warehouse/Yard", stale),
                new Row("the ROOT ancestor, spelling unchanged", "/Warehouse", stale),
                new Row("the same non-root ancestor, re-spelled", "/Warehouse/Yard",
                        "Primary*/Candidate, Secondary*/Fallback"),
                new Row("the same root ancestor, re-spelled", "/Warehouse",
                        "Yard/Primary*/Candidate, Yard/Secondary*/Fallback"));

        var actual = new LinkedHashMap<String, String>();
        for (Row row : rows) {
            Path file = write(dir, "rebase-" + rows.indexOf(row) + ".json",
                    spec.formatted(row.group(), row.operand()));
            CliTestSupport.Result result = CliTestSupport.run(
                    "-m", model.toString(), "computation", "add", file.toString(), "--dry-run");
            JsonNode envelope = MAPPER.readTree(result.out());
            actual.put(row.label(), "rejected".equals(envelope.path("outcome").asText())
                    ? envelope.path("diagnostics").get(0).path("code").asText()
                    : envelope.path("outcome").asText());
        }

        var expected = new LinkedHashMap<String, String>();
        expected.put("the base the operand was written for", "preview");
        expected.put("a NON-root ancestor, spelling unchanged", "MVK_INVALID_ENTITY");
        expected.put("the ROOT ancestor, spelling unchanged", "MVK_TOO_MANY_PARENT_GROUPS");
        expected.put("the same non-root ancestor, re-spelled", "preview");
        expected.put("the same root ancestor, re-spelled", "preview");
        assertEquals(expected, actual, "the declaring group is the operand's rendering base, not a filing label");
    }

    /**
     * The {@code apply} route refuses a relocating re-expression exactly as the standalone verb does.
     *
     * <p>Both surfaces run the same parser, and {@code modify} is remove-then-add at the draft's declaring
     * group — so a spec naming a different {@code group} would remove the located computation and add another
     * elsewhere. Measured before the gate existed: the op reported {@code applied}/{@code committed} and the
     * element had MOVED. It is refused inside the transaction, which rolls the whole sequence back.
     */
    @Test
    void applyRefusesARelocatingComputationModifyJustAsTheVerbDoes(@TempDir Path dir) throws Exception {
        Path model = probeModel(dir, "Probe_DM.json");
        String besideItsField = CROSS_GROUP_SPEC.replace("\"group\": \"" + RULES_GROUP + "\",\n  ", "");
        CliTestSupport.run("-m", model.toString(), "computation", "add",
                write(dir, "beside.json", besideItsField).toString());
        String before = Files.readString(model);

        Path ops = write(dir, "relocate.json", """
                [ { "target": "computation", "op": "modify",
                    "computationPathInModel": "/Probe/Store/StockComp",
                    "computedField": "%s", "group": "%s", "name": "StockComp",
                    "alternatives": [ { "operation": "-2" } ],
                    "messages": [ {"locale":"en_US","text":"Relocated."} ] } ]
                """.formatted(STOCK_LEVEL, RULES_GROUP));

        CliTestSupport.Result result = CliTestSupport.run("-m", model.toString(), "apply", ops.toString());

        assertEquals("rejected", MAPPER.readTree(result.out()).path("outcome").asText(), result::out);
        assertEquals(before, Files.readString(model), "the sequence rolled back — nothing moved");
    }

    /** Criterion 4 — a group that does not resolve is refused inside the envelope, with a coded diagnostic. */
    @Test
    void aGroupThatDoesNotResolveIsRefusedInsideTheResultEnvelope(@TempDir Path dir) throws Exception {
        Path model = probeModel(dir, "Probe_DM.json");
        String before = Files.readString(model);

        for (String group : new String[] {"/Probe/Nope", "Rules"}) {
            Path spec = write(dir, "bad-" + group.replace('/', '_') + ".json",
                    CROSS_GROUP_SPEC.replace(RULES_GROUP, group));

            CliTestSupport.Result result = CliTestSupport.run(
                    "-m", model.toString(), "computation", "add", spec.toString());

            assertFalse(result.out().isBlank(),
                    () -> "the refusal must reach STDOUT as the envelope, not stderr prose: " + result.err());
            JsonNode envelope = MAPPER.readTree(result.out());
            assertEquals("rejected", envelope.path("outcome").asText(), result::out);
            assertEquals("RK_NO_SUCH_GROUP", envelope.path("diagnostics").get(0).path("code").asText(),
                    () -> "group '" + group + "' does not resolve: " + result.out());
            assertEquals(group, envelope.path("diagnostics").get(0).path("where").path("path").asText(),
                    result::out);
            assertFalse(envelope.path("written").asBoolean(), result::out);
        }
        assertEquals(before, Files.readString(model), "a refused add writes nothing");
    }

    /**
     * The declaring group is reported for an OPAQUE read too — the claim the payload's own comment makes, and
     * the one a Converted-only test cannot see.
     *
     * <p>The Opaque trigger is a non-{@code EXACT} rounding mode ({@code RoundUp}): rulekit models exact
     * computations only, so the converter refuses it and the read degrades tolerantly. That branch produces no
     * typed {@code Computation} at all, which is exactly why {@code group} is derived structurally from the
     * full name rather than from the converted object.
     *
     * <p>The tolerance band is load-bearing, not decoration: the kernel refuses a non-exact rounding mode on an
     * EXACT comparison ({@code MVK_ERROR_WRONG_ROUNDING_MODE}), so without it the fixture is an unloadable
     * model rather than an Opaque computation — measured, on the first attempt at this test.
     */
    @Test
    void theDeclaringGroupIsReportedForAnOpaqueReadToo(@TempDir Path dir) throws Exception {
        Path model = probeModel(dir, "Probe_DM.json");
        String withTolerance = CROSS_GROUP_SPEC.replace("{ \"operation\": \"-1\" }",
                "{ \"operation\": \"-1\", \"toleranceRangeOp\": \"DiffersWithToleranceRange1\" }");
        CliTestSupport.run("-m", model.toString(), "computation", "add",
                write(dir, "comp.json", withTolerance).toString());
        makeRoundingNonExact(model, CROSS_GROUP_COMPUTATION);

        CliTestSupport.Result read = CliTestSupport.run(
                "-m", model.toString(), "computation", "read", CROSS_GROUP_COMPUTATION);

        assertEquals(0, read.code(), () -> read.err() + read.out());
        JsonNode data = MAPPER.readTree(read.out()).path("data");
        assertFalse(data.path("converted").asBoolean(),
                () -> "the fixture must actually be Opaque, or this asserts nothing: " + read.out());
        assertEquals(RULES_GROUP, data.path("group").asText(), read::out);
        assertTrue(data.path("calculatedField").isMissingNode(),
                () -> "an Opaque read carries no typed target — `group` is the only placement it can report: "
                        + read.out());
    }

    /** Rewrite one persisted computation's rounding mode to {@code RoundUp}, which rulekit does not model. */
    private static void makeRoundingNonExact(Path model, String computationFullName) throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(model));
        JsonNode element = findElement(root.path("content").path("modelRoot").path("rootGroups"),
                computationFullName);
        ((com.fasterxml.jackson.databind.node.ObjectNode) element.path("Computation"))
                .put("roundingMode", "RoundUp");
        Files.writeString(model, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    /**
     * A {@code computedField} carrying an EMPTY segment is refused — the guard the {@code group} key would
     * otherwise have removed.
     *
     * <p>Before {@code group} existed, a trailing separator was caught by accident: the declaring group was
     * derived as {@code computedField} minus its last segment, which for {@code "/Probe/Store/StockLevel/"}
     * is the FIELD path {@code /Probe/Store/StockLevel}, and {@code RK_NO_SUCH_GROUP} refused it. Supplying
     * {@code group} skips that derivation entirely, and the kernel then ACCEPTS the spec (measured: it splits
     * absolute paths with a default {@code split} that drops the trailing empty) — so dmtool would persist a
     * {@code computedFieldRelPath} ending in an empty segment, under a computation named {@code Comp} taken
     * from the empty last segment. A12's own {@code ElementPathUtils.splitAbsPath} states that such a path is
     * not a legal absolute path, so refusing it matches A12's shape rather than inventing one.
     */
    @Test
    void aComputedFieldWithAnEmptySegmentIsRefusedEvenWhenAGroupIsSupplied(@TempDir Path dir) throws Exception {
        Path model = probeModel(dir, "Probe_DM.json");
        for (String malformed : new String[] {STOCK_LEVEL + "/", "//Probe/Store/StockLevel", "/Probe//StockLevel"}) {
            Path spec = write(dir, "bad-" + malformed.hashCode() + ".json",
                    CROSS_GROUP_SPEC.replace("\"" + STOCK_LEVEL + "\"", "\"" + malformed + "\""));

            CliTestSupport.Result result = CliTestSupport.run(
                    "-m", model.toString(), "computation", "add", spec.toString(), "--dry-run");

            JsonNode envelope = MAPPER.readTree(result.out());
            assertEquals("rejected", envelope.path("outcome").asText(),
                    () -> "computedField '" + malformed + "' must be refused: " + result.out());
            assertEquals("RK_INVALID_VALUE", envelope.path("diagnostics").get(0).path("code").asText(),
                    result::out);
        }
    }

    /**
     * The two input gates are layered, and which one answers is worth pinning because they overlap.
     *
     * <p>The SCHEMA gate runs first and already covers every required key and declared enum, so a missing
     * {@code messages} and a bogus {@code toleranceRangeOp} are both refused there, keyed by {@code where.arg}.
     * What reaches the PARSER is only what the schema cannot express — a path's FORM. Both report
     * {@code RK_INVALID_VALUE}, which is the point: an agent gets one code and one arg for "this key's value is
     * unusable", wherever the refusal was decided.
     *
     * <p>Consequence worth knowing: the parser's {@code RK_INCOMPLETE_RULE} arm is unreachable from these verbs
     * (the schema pre-empts it) and reachable only through {@code apply}, which rewraps it as
     * {@code RK_APPLY_OP_FAILED} carrying the parser's summary. So the code exists and is emitted; it is not a
     * code an agent sees from `computation add`.
     */
    @Test
    void theSchemaGateAnswersFirstAndTheParserOnlySeesWhatItCannotExpress(@TempDir Path dir) throws Exception {
        Path model = probeModel(dir, "Probe_DM.json");

        record Case(String name, String spec, String arg) { }
        List<Case> cases = List.of(
                new Case("missing-messages", CROSS_GROUP_SPEC.replaceAll(",\\s*\"messages\"[^}]*}\\s*]", ""),
                        "messages"),
                // The schema gate names the full NESTED key path, which is why this row exists: an agent
                // repairing a tier needs to know which slot, not just which top-level key.
                new Case("bad-band", CROSS_GROUP_SPEC.replace("\"operation\": \"-1\"",
                        "\"operation\": \"-1\", \"toleranceRangeOp\": \"NotABand\""),
                        "alternatives[]/toleranceRangeOp"),
                new Case("relative-computed-field",
                        CROSS_GROUP_SPEC.replace(STOCK_LEVEL, "../../Store/StockLevel"), "computedField"));

        for (Case c : cases) {
            CliTestSupport.Result result = CliTestSupport.run("-m", model.toString(), "computation", "add",
                    write(dir, c.name() + ".json", c.spec()).toString(), "--dry-run");

            JsonNode diagnostic = MAPPER.readTree(result.out()).path("diagnostics").get(0);
            assertEquals("RK_INVALID_VALUE", diagnostic.path("code").asText(),
                    () -> c.name() + ": " + result.out());
            assertEquals(c.arg(), diagnostic.path("where").path("arg").asText(),
                    () -> c.name() + ": the refusal must name the key, whichever gate decided it: " + result.out());
        }
    }

    /**
     * An explicit {@code "group": null} is refused by the schema as a type violation rather than defaulting.
     *
     * <p>Pinned because the library builder's convention is the opposite — {@code Builder.group(null)} means
     * "leave it unset" — and the two are deliberately different: a Java caller passing {@code null} is usually
     * composing, while a JSON author writing {@code null} into an optional key is usually confused, and the
     * self-describing surface answers with the key's declared type instead of guessing.
     */
    @Test
    void anExplicitNullGroupIsRefusedByTheSchemaRatherThanDefaulted(@TempDir Path dir) throws Exception {
        Path model = probeModel(dir, "Probe_DM.json");
        Path spec = write(dir, "null-group.json", CROSS_GROUP_SPEC.replace(
                "\"group\": \"" + RULES_GROUP + "\"", "\"group\": null"));

        CliTestSupport.Result result = CliTestSupport.run(
                "-m", model.toString(), "computation", "add", spec.toString(), "--dry-run");

        JsonNode diagnostic = MAPPER.readTree(result.out()).path("diagnostics").get(0);
        assertEquals("RK_INVALID_VALUE", diagnostic.path("code").asText(), result::out);
        assertEquals("group", diagnostic.path("where").path("arg").asText(), result::out);
    }

    /** The spec's own parse refusals ride the envelope too — the shape the reporter found as stderr prose. */
    @Test
    void aMalformedComputedFieldIsRefusedInsideTheResultEnvelopeToo(@TempDir Path dir) throws Exception {
        Path model = probeModel(dir, "Probe_DM.json");
        Path spec = write(dir, "relative.json", CROSS_GROUP_SPEC.replace(STOCK_LEVEL, "../../Store/StockLevel"));

        CliTestSupport.Result result = CliTestSupport.run(
                "-m", model.toString(), "computation", "add", spec.toString(), "--dry-run");

        assertFalse(result.out().isBlank(),
                () -> "a spec-parse refusal must be the envelope, not stderr prose: " + result.err());
        JsonNode envelope = MAPPER.readTree(result.out());
        assertEquals("rejected", envelope.path("outcome").asText(), result::out);
        JsonNode diagnostic = envelope.path("diagnostics").get(0);
        assertEquals("RK_INVALID_VALUE", diagnostic.path("code").asText(), result::out);
        assertEquals("computedField", diagnostic.path("where").path("arg").asText(), result::out);
    }

    /** The {@code computedFieldRelPath} actually stored on the computation element in the model file. */
    private static String storedRelPath(Path model, String computationFullName) throws Exception {
        JsonNode root = MAPPER.readTree(Files.readString(model));
        JsonNode element = findElement(root.path("content").path("modelRoot").path("rootGroups"),
                computationFullName);
        assertTrue(element != null && !element.isMissingNode(),
                () -> "no computation element at " + computationFullName);
        return element.path("Computation").path("computedFieldRelPath").asText();
    }

    private static JsonNode findElement(JsonNode groups, String id) {
        for (JsonNode node : groups) {
            if (id.equals(node.path("id").asText())) {
                return node;
            }
            JsonNode nested = findElement(node.path("Group").path("elements"), id);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
}
