package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §9 — <b>which key placements a {@code RepetitionNotUnique} {@code @From} scope admits.</b>
 *
 * <p><b>The headline result is that {@code MVK_NOT_IN_GROUP} is barely reachable, and what makes it
 * reachable is INDEX-KEYING rather than anything about containment.</b> On an index-free model every
 * containment violation below draws {@code MVK_NO_WILDCARD}: the two repeatable groups do not iterate
 * together, so A12 refuses the pairing before it ever asks what the scope contains. Give the two groups a
 * shared index field and they iterate JOINTLY, the wildcard gate is satisfied, and the containment class
 * appears. The single previously measured cell lived on a fixture that was index-keyed for other reasons,
 * and nothing recorded that this was load-bearing.
 *
 * <p><i>A rival reading was measured and refuted first</i>: that the class depends on the rule's POSITION,
 * the previous cell having put the rule inside the key's own repeatable group. Reproducing that position on
 * an index-free model still draws {@code MVK_NO_WILDCARD}, so position is not the variable.
 *
 * <p><b>And the scope has a demand of its own that containment does not describe:</b> {@code @From} must name
 * a REPEATABLE group, so naming the rule's own non-repeatable root draws {@code MVK_GROUP_NOT_REPEATABLE}
 * rather than any containment class. That law is already held — KERNEL-FINDINGS §9 measured it on a
 * subscription carrier and {@code RepetitionNotUniqueCondition.from} pre-checks it — so this row CONFIRMS it
 * on a second carrier; it is not a new class.
 *
 * <p>One cell of this law had been measured — a key in a group PARALLEL to the {@code @From} group draws
 * {@code MVK_NOT_IN_GROUP} ({@link IterationLawsTest}) — and the source note beside it reads "each key's
 * parent path must start with the {@code @From} group path". That sentence has three consequences nobody
 * had put to the kernel, and they are the ones a checker turns on: whether a DESCENDANT of the scope is
 * inside it, whether an ANCESTOR of the keys may be named as the scope, and whether a second key outside the
 * scope is enough on its own.
 *
 * <pre>
 * /Depot                       rule group
 *   DepotRef                   above every candidate scope
 *   Crates       repeatable
 *     CrateRef                 directly in the scope
 *     Items      repeatable
 *       Sku                    a DESCENDANT of the scope
 *   Pallets      repeatable    parallel to Crates
 *     PalletRef
 * </pre>
 */
class RepetitionNotUniqueFromScopeLawsTest {

    private static final String DEPOT = DocumentModelDraft.named("depot").locale("en_US")
            .rootGroup("Depot", d -> d
                    .field("DepotRef", FieldKind.STRING)
                    .group("Crates", c -> c.repeatable(20)
                            .field("CrateRef", FieldKind.STRING)
                            .group("Items", i -> i.repeatable(50).field("Sku", FieldKind.STRING)))
                    .group("Pallets", p -> p.repeatable(10).field("PalletRef", FieldKind.STRING)))
            .build().toJson();

    /** The same shape with the two repeatable groups INDEX-KEYED on one name, so they iterate jointly. */
    private static final String INDEXED = DocumentModelDraft.named("depot-indexed").locale("en_US")
            .rootGroup("Depot", d -> d
                    .field("DepotRef", FieldKind.STRING)
                    .group("Crates", c -> c.repeatable(20).indexField("SlotRef")
                            .field("SlotRef", FieldKind.STRING)
                            .field("CrateRef", FieldKind.STRING))
                    .group("Pallets", p -> p.repeatable(10).indexField("SlotRef")
                            .field("SlotRef", FieldKind.STRING)
                            .field("PalletRef", FieldKind.STRING)))
            .build().toJson();

    @Test
    void whatTheFromScopeHasToContain() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the key sits directly in the scope",
                verdict("../Crates/CrateRef", "RepetitionNotUnique(Crates/CrateRef @From Crates)"));
        actual.put("the key sits in a PARALLEL group",
                verdict("../Crates/CrateRef", "RepetitionNotUnique(Crates/CrateRef @From Pallets)"));
        actual.put("the key sits in a DESCENDANT of the scope",
                verdict("../Crates/Items/Sku", "RepetitionNotUnique(Crates/Items/Sku @From Crates)"));
        actual.put("the scope is an ANCESTOR of the keys — the rule's own group",
                verdict("../Crates/CrateRef", "RepetitionNotUnique(Crates/CrateRef @From /Depot)"));
        actual.put("the key sits ABOVE the scope",
                verdict("../DepotRef", "RepetitionNotUnique(DepotRef @From Crates)"));
        actual.put("two keys, one of them outside the scope",
                verdict("../Crates/CrateRef",
                        "RepetitionNotUnique(Crates/CrateRef, Pallets/PalletRef @From Crates)"));
        actual.put("the key outside the scope, on an INDEX-KEYED pair that iterates jointly",
                KernelLaws.verdictModel(INDEXED, "/Depot", "../Crates/CrateRef",
                        "RepetitionNotUnique(Crates/CrateRef @From Pallets)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("the key sits directly in the scope", KernelLaws.ADMITTED);
        expected.put("the key sits in a PARALLEL group", "MVK_NO_WILDCARD");
        expected.put("the key sits in a DESCENDANT of the scope", KernelLaws.ADMITTED);
        // A THIRD class, and one the estate had not measured: the scope must be a REPEATABLE group, and
        // `/Depot` is the root. So naming an ancestor is not merely permitted-or-not — it is refused for a
        // reason that has nothing to do with containment.
        expected.put("the scope is an ANCESTOR of the keys — the rule's own group", "MVK_GROUP_NOT_REPEATABLE");
        expected.put("the key sits ABOVE the scope", "MVK_NO_WILDCARD");
        expected.put("two keys, one of them outside the scope", "MVK_NO_WILDCARD");
        // …and the SAME violation on an INDEX-KEYED pair, which is what makes the containment class
        // reachable: two groups sharing an index field iterate JOINTLY, so the wildcard gate is satisfied
        // and A12 gets as far as asking about containment.
        expected.put("the key outside the scope, on an INDEX-KEYED pair that iterates jointly",
                "MVK_NOT_IN_GROUP");
        assertEquals(expected, actual, "RepetitionNotUnique @From scope containment");
    }

    /**
     * …and the SIBLING class {@code MVK_NOT_IN_SAME_PATH} turns on the same thing, which is a transfer.
     *
     * <p>[MVK-LEDGER] records that class's reachability as fixture-sensitive with the reason unmeasured, in
     * the same words it used for {@code MVK_NOT_IN_GROUP}. The mechanism found for that one — two repeatable
     * groups iterate jointly only when they share an index field, so an index-free pair is refused at the
     * wildcard gate before any path question is asked — PREDICTS the same split here. It is a different
     * class reported by a different check, so predicting is not measuring: both halves are built on the one
     * model shape, index-free and index-keyed.
     */
    @Test
    void theSamePathClassTurnsOnIndexKeyingToo() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two keys in parallel repeatable groups | index-free",
                verdict("../Crates/CrateRef", "RepetitionNotUnique(Crates/CrateRef, Pallets/PalletRef)"));
        actual.put("two keys in parallel repeatable groups | INDEX-KEYED",
                KernelLaws.verdictModel(INDEXED, "/Depot", "../Crates/CrateRef",
                        "RepetitionNotUnique(Crates/CrateRef, Pallets/PalletRef)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two keys in parallel repeatable groups | index-free", "MVK_NO_WILDCARD");
        expected.put("two keys in parallel repeatable groups | INDEX-KEYED", "MVK_NOT_IN_SAME_PATH");
        assertEquals(expected, actual, "the same-path class turns on index keying");
    }

    /** The COMPLETE class list, so a co-firing gate cannot pass for the one under test. */
    private static String verdict(String errorField, String condition) {
        return verdictIn("/Depot", errorField, condition);
    }

    private static String verdictIn(String group, String errorField, String condition) {
        return KernelLaws.verdictModel(DEPOT, group, errorField, condition);
    }
}
