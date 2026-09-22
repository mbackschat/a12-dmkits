package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * KERNEL-FINDINGS §9 — THREE further admission routes for a negative under iteration, none of them modelled by
 * the level rule.
 *
 * <p>The level rule says an {@code And} needs one child that both REFERENCES the queried level and is admitted
 * at it, and the advisory reads "admitted" as a positive PRESENCE predicate. An independent differential found
 * six shapes the advisory warns on that the kernel admits, and offered two mechanisms. Measured, one holds,
 * one is refuted, and a third route turns up that neither party proposed:
 *
 * <ul>
 * <li><b>Refuted — a declared INDEX FIELD.</b> It makes no difference at all: a bare negative is refused with
 *     the index declared and without it, same code, even when the error field IS the index.</li>
 * <li><b>Confirmed — a value COMPARISON at the level licenses.</b> One conjunct apart, the bare negative is
 *     refused and the comparison admits it. The rule's own word is <em>references</em>, not <em>is
 *     present</em>, and the advisory's guard vocabulary was presence predicates only.</li>
 * <li><b>Confirmed — an UNSTARRED cross-group reference licenses</b>, where the starred form does not. That
 *     narrows the level rule: "a guard on another repeatable level does not admit" holds for a STARRED
 *     reference and is false for an unstarred one. Root-versus-nested is NOT an axis — both admit.</li>
 * <li><b>New — an index declaration AND a maximum of TWO, together.</b> A bare negative naming a repeatable
 *     group is admitted only when the group declares an index field and its maximum repeatability is two;
 *     three repetitions refuse with an index, and two refuse without one, at root and nested alike. Neither
 *     conjunct alone: reading the count as the rule cost agreeing refusals on groups declaring no index, and
 *     reading the index as the rule would cost them on groups repeating more than twice. No mechanism is
 *     offered for the boundary, only its location.
 *
 *     <p><b>Corrected 2026-09-07, same day, twice.</b> The differential first offered the index alone and it
 *     was refuted — at ten repetitions both arms refuse, which is where that was measured. A sweep then read
 *     the count alone as the rule, with the index declared in every cell it took. Each set held the other's
 *     variable constant, and the grid below is the first to move both.</li>
 * </ul>
 *
 * <p>Each pair differs in exactly ONE builder call or ONE conjunct. Conditions carry no appended error-field
 * guard: that conjunct is itself a positive presence guard at the negative's level and admits every row
 * ([KF436](#kf436)).
 */
class IterationGuardAdmissionRoutesLawsTest {

    private static final String NEG = "MVK_NEG_CONDITION_IN_ITERATION";

    /** Two REPEATABLE ROOT groups. {@code indexed} is the only difference between the two models. */
    private static String twoRoots(boolean indexed) {
        return DocumentModelDraft.named(indexed ? "roots-indexed" : "roots-plain").locale("en_US")
                .rootGroup("P1", p -> {
                    p.repeatable(10).field("I", FieldKind.STRING).field("S", FieldKind.STRING);
                    if (indexed) {
                        p.indexField("I");
                    }
                })
                .rootGroup("P2", p -> {
                    p.repeatable(10).field("I", FieldKind.STRING).field("S", FieldKind.STRING);
                    if (indexed) {
                        p.indexField("I");
                    }
                })
                .build().toJson();
    }

    private static final String INDEXED = twoRoots(true);
    private static final String PLAIN = twoRoots(false);

    /** Two repeatable siblings NESTED under one root, with index fields — the root-vs-nested control. */
    private static final String NESTED_SIBLINGS = DocumentModelDraft.named("nested-siblings-indexed")
            .locale("en_US")
            .rootGroup("O", o -> o
                    .group("P1", p -> p.repeatable(10).indexField("I")
                            .field("I", FieldKind.STRING).field("S", FieldKind.STRING))
                    .group("P2", p -> p.repeatable(10).indexField("I")
                            .field("I", FieldKind.STRING).field("S", FieldKind.STRING)))
            .build().toJson();

    /** A single repeatable ROOT group, for the reported bare-negative rows. */
    private static final String LONE_ROOT = DocumentModelDraft.named("lone-root").locale("en_US")
            .rootGroup("Q", q -> q.repeatable(2).indexField("I")
                    .field("I", FieldKind.STRING)
                    .group("g", g -> g.field("f", FieldKind.STRING)))
            .build().toJson();

    /** The lone-root model plus one more root, whose repeatability is the variable. */
    private static String loneRootPlus(boolean secondRootRepeats, int ownRepeats) {
        return DocumentModelDraft.named("lone-plus-" + secondRootRepeats + "-" + ownRepeats).locale("en_US")
                .rootGroup("Q", q -> q.repeatable(ownRepeats).indexField("I")
                        .field("I", FieldKind.STRING)
                        .group("g", g -> g.field("f", FieldKind.STRING)))
                .rootGroup("Z", z -> {
                    z.field("zf", FieldKind.STRING);
                    if (secondRootRepeats) {
                        z.repeatable(5);
                    }
                })
                .build().toJson();
    }

    /** A nested repeatable group with NO index field, so H1 cannot explain anything here. */
    private static final String NESTED = DocumentModelDraft.named("nested-no-index").locale("en_US")
            .rootGroup("A", a -> a
                    .group("b", b -> b.repeatable(2)
                            .field("flag", FieldKind.BOOLEAN)
                            .group("sh", s -> s.field("note", FieldKind.STRING))))
            .build().toJson();

    /**
     * One repeatable group carrying the negative, over the axes that decide it: its POSITION, its
     * repeatability COUNT, whether it declares an INDEX FIELD, and what it CONTAINS.
     *
     * <p>The contents axis is here because it was the last thing every cell of this grid held constant — two
     * flat fields and no nested subgroup — while the lone-root row that admits carries a nested one, so
     * "admitted iff index and a maximum of two" and "admitted iff index and a maximum of two, on a group with
     * no subgroup" were indistinguishable. Varying it changes nothing, which is what makes the count/index
     * conjunct a claim about the group's repeatability rather than about its shape.
     */
    private static String repeatCountModel(boolean atRoot, int repeats, boolean indexed, boolean withSubgroup) {
        var b = DocumentModelDraft.named("rc-" + atRoot + "-" + repeats + "-" + indexed + "-" + withSubgroup)
                .locale("en_US");
        java.util.function.Consumer<DocumentModelDraft.GroupBuilder> body = q -> {
            q.repeatable(repeats).field("I", FieldKind.STRING).field("S", FieldKind.STRING);
            if (withSubgroup) {
                q.group("g", g -> g.field("f", FieldKind.STRING));
            }
            if (indexed) {
                q.indexField("I");
            }
        };
        if (atRoot) {
            return b.rootGroup("Q", body).build().toJson();
        }
        return b.rootGroup("O", o -> o.group("Q", body)).build().toJson();
    }

    private static String verdict(String json, String ruleGroup, String errorField, String condition) {
        var result = KernelLaws.checkModel(json, ruleGroup, errorField, condition);
        if (result.ok()) {
            return KernelLaws.ADMITTED;
        }
        return result.diagnostics().stream()
                .map(d -> d.code().orElse("NO_CODE"))
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining("+"));
    }

    /** One table, so a kernel shift shows every cell rather than aborting at the first. */
    @Test
    void anIndexDeclarationAndAValueReferenceAreBothAdmissionRoutes() {
        var actual = new LinkedHashMap<String, String>();

        // H1, the decisive pair: identical model, identical rule, identical bare negative. One
        // `.indexField("I")` apart.
        actual.put("H1 bare negative, group declares an index",
                verdict(INDEXED, "/P1", "../S", "GroupNotFilled(/P1)"));
        actual.put("H1 bare negative, NO index declared",
                verdict(PLAIN, "/P1", "../S", "GroupNotFilled(/P1)"));

        // The reported row 1: the same negative with an unstarred presence over the sibling ROOT. Reachable
        // without a star precisely because the sibling declares an index — which is why an earlier attempt at
        // this shape, on a model without one, drew MVK_NO_WILDCARD from the star it had to use instead.
        actual.put("H1 with an unstarred sibling-root guard",
                verdict(INDEXED, "/P1", "../S", "GroupNotFilled(/P1) And GroupFilled(/P2)"));

        // The reported row 2: the error field IS the index field, bare negative.
        actual.put("H1 bare negative, error field IS the index",
                verdict(INDEXED, "/P1", "../I", "GroupNotFilled(/P1)"));

        // H2, the decisive pair: same model, same rule, same error field, same negative. One value
        // comparison apart, on a field at the negative's own level.
        actual.put("H2 bare negative, no index anywhere",
                verdict(NESTED, "/A/b", "../sh/note", "GroupNotFilled(sh)"));
        actual.put("H2 the same, plus a value comparison at that level",
                verdict(NESTED, "/A/b", "../sh/note",
                        KernelLaws.render(BoundModel.ofJson(NESTED).bool("/A/b/flag").isTrue())
                                + " And GroupNotFilled(sh)"));

        // WHAT ADMITS the sibling-root row, since H1 is refuted. Two candidates: the reference is UNSTARRED
        // (a list-level read that iterates at no level), or the groups are ROOTS rather than nested. One cell
        // each.
        actual.put("H3 starred sibling-root guard instead of unstarred",
                verdict(INDEXED, "/P1", "../S", "GroupNotFilled(/P1) And AtLeastOneGroupFilled(/P2*)"));
        actual.put("H3 the unstarred guard, siblings NESTED under a root",
                verdict(NESTED_SIBLINGS, "/O/P1", "../S", "GroupNotFilled(/O/P1) And GroupFilled(/O/P2)"));
        // The reported row 4: an unstarred FIELD read into the sibling instead of a group presence.
        actual.put("H3 an unstarred FIELD read into the sibling",
                verdict(INDEXED, "/P1", "../S", "FieldFilled(/P2/S) And GroupNotFilled(/P1)"));

        // The reported bare-negative rows, on a LONE repeatable root, and in both error-field spellings —
        // their note writes these two with a bare name where the others carry a path.
        actual.put("row2 lone root, error field ../I",
                verdict(LONE_ROOT, "/Q", "../I", "GroupNotFilled(/Q)"));
        actual.put("row3 lone root, nested non-repeating group, ../g/f",
                verdict(LONE_ROOT, "/Q/g", "../f", "FieldNotFilled(f)"));

        // WHY rows 2-3 are admitted where the same bare shape is refused on the two-root model. Three
        // candidates, one cell each: a second root exists at all, that second root REPEATS, or the group's
        // own repeatability count.
        actual.put("row2 plus a NON-repeatable second root",
                verdict(loneRootPlus(false, 2), "/Q", "../I", "GroupNotFilled(/Q)"));
        actual.put("row2 plus a REPEATABLE second root",
                verdict(loneRootPlus(true, 2), "/Q", "../I", "GroupNotFilled(/Q)"));
        actual.put("row2 lone root but repeatable 10",
                verdict(loneRootPlus(false, 10), "/Q", "../I", "GroupNotFilled(/Q)"));

        // THE SHAPE THE ADVISORY'S OWN LOCK PINS AS A WARNING: an unstarred FIELD read into a sibling
        // repeatable group, guarding a field negative. If the kernel admits it, that lock pins a false
        // positive — the starred spelling is the one the level law measured.
        actual.put("unstarred sibling FIELD guard, field negative",
                verdict(NESTED_SIBLINGS, "/O/P1", "../S",
                        "FieldFilled(/O/P2/S) And FieldNotFilled(S)"));
        actual.put("the same guard STARRED",
                verdict(NESTED_SIBLINGS, "/O/P1", "../S",
                        "AtLeastOneFieldFilled(/O/P2*/S) And FieldNotFilled(S)"));

        // THE GRID that separates the count from the index declaration, over both positions. An earlier
        // sweep varied the count with the index declared in every cell, and read the count as the whole rule;
        // the H1 rows varied the index only at a count of ten, where both arms refuse. Neither set could see
        // the interaction.
        for (boolean atRoot : new boolean[] {true, false}) {
            for (int n : new int[] {2, 3, 10}) {
                for (boolean indexed : new boolean[] {true, false}) {
                    for (boolean sub : new boolean[] {false, true}) {
                        String key = "grid " + (atRoot ? "root" : "nested") + " R(" + n + ")"
                                + (indexed ? " idx" : " no-idx") + (sub ? " +sub" : "");
                        String group = atRoot ? "/Q" : "/O/Q";
                        actual.put(key, verdict(repeatCountModel(atRoot, n, indexed, sub), group, "../S",
                                "GroupNotFilled(" + group + ")"));
                    }
                }
            }
        }

        // The axis the grid holds constant even after the contents widening: in every cell above the RULE
        // sits on the same group the negative names and the same group carrying the index and the count, so
        // "the negative's group" and "the rule's group" are indistinguishable. Separated here by moving the
        // rule to the nested non-repeating child while the negative keeps naming the repeatable ancestor,
        // on the refusing side as well as the admitting one — which is where the two accounts would diverge.
        for (int n : new int[] {2, 3}) {
            String json = repeatCountModel(true, n, true, true);
            actual.put("locus R(" + n + ") rule on the repeatable group itself",
                    verdict(json, "/Q", "../S", "GroupNotFilled(/Q)"));
            actual.put("locus R(" + n + ") rule on its non-repeating child",
                    verdict(json, "/Q/g", "../f", "GroupNotFilled(/Q)"));
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("H1 bare negative, group declares an index", NEG);
        expected.put("H1 bare negative, NO index declared", NEG);
        expected.put("H1 with an unstarred sibling-root guard", KernelLaws.ADMITTED);
        expected.put("H1 bare negative, error field IS the index", NEG);
        expected.put("H2 bare negative, no index anywhere", NEG);
        expected.put("H2 the same, plus a value comparison at that level", KernelLaws.ADMITTED);
        expected.put("H3 starred sibling-root guard instead of unstarred", NEG);
        expected.put("H3 the unstarred guard, siblings NESTED under a root", KernelLaws.ADMITTED);
        expected.put("H3 an unstarred FIELD read into the sibling", KernelLaws.ADMITTED);
        expected.put("row2 lone root, error field ../I", KernelLaws.ADMITTED);
        expected.put("row3 lone root, nested non-repeating group, ../g/f", KernelLaws.ADMITTED);
        expected.put("row2 plus a NON-repeatable second root", KernelLaws.ADMITTED);
        expected.put("row2 plus a REPEATABLE second root", KernelLaws.ADMITTED);
        expected.put("row2 lone root but repeatable 10", NEG);
        expected.put("unstarred sibling FIELD guard, field negative", KernelLaws.ADMITTED);
        expected.put("the same guard STARRED", NEG);
        for (boolean atRoot : new boolean[] {true, false}) {
            for (int n : new int[] {2, 3, 10}) {
                for (boolean indexed : new boolean[] {true, false}) {
                    for (boolean sub : new boolean[] {false, true}) {
                        // Admitted iff BOTH: the group declares an index AND its maximum is two. What the
                        // group contains is not an axis — every pair of `+sub` cells agrees with its twin.
                        expected.put("grid " + (atRoot ? "root" : "nested") + " R(" + n + ")"
                                + (indexed ? " idx" : " no-idx") + (sub ? " +sub" : ""),
                                n == 2 && indexed ? KernelLaws.ADMITTED : NEG);
                    }
                }
            }
        }

        // Inert: moving the rule off the group the negative names changes nothing in either direction, so
        // the conjunction is a claim about the group the NEGATIVE names, and the rule's own locus is not an
        // axis of it.
        expected.put("locus R(2) rule on the repeatable group itself", KernelLaws.ADMITTED);
        expected.put("locus R(2) rule on its non-repeating child", KernelLaws.ADMITTED);
        expected.put("locus R(3) rule on the repeatable group itself", NEG);
        expected.put("locus R(3) rule on its non-repeating child", NEG);

        assertEquals(expected, actual);
    }
}
