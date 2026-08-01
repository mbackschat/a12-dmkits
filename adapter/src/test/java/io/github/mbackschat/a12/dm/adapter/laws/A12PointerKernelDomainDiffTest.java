package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.mgmtp.a12.kernel.md.document.apiV2.DocumentPointer;
import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointerFormat;
import io.github.mbackschat.a12.dm.interpreter.A12PointerFormat;

/**
 * <b>The accept/reject sets of both interpreter pointer types, measured against the real kernel factories.</b>
 *
 * This exists because of CLAUDE.md's standing rule that a client-side refusal needs a measured kernel basis:
 * delegation can only under-report and is safe, but a refusal we invent can be plainly wrong and is invisible,
 * since the kernel is never asked. Reading the source is not the oracle for an accept/reject claim — the kernel
 * is — and this file is that oracle running.
 *
 * <p>It is also the guard against the defect it was written after. A single hybrid pointer type admitted the
 * union of two A12 domains: {@code -5} on an <em>exact</em> pointer, which {@code DocumentPointerImpl}'s own part
 * pattern ({@code \d+}, unsigned) cannot even read, while still enforcing the exact type's last-part-only
 * {@code 0}. Nothing in the JVM or JS suites could see that, because neither side had ever asked A12 what it
 * accepts. Now every case below is decided twice — once by us, once by the kernel — and any divergence fails.
 *
 * <p><b>Two deliberate divergences are asserted as divergences</b>, so being stricter than the kernel stays a
 * recorded decision rather than an accident (both on the partially-known type, both explained in
 * {@code A12PartiallyKnownMultiPointer}'s KDoc):
 *
 * <ol>
 *   <li><b>arity</b> — {@code PartiallyKnownDocumentMultiPointerImpl}'s constructor never compares the name count
 *       against the repetition count, while its own {@code toDocumentPointer()} documents the omission away ("we
 *       do not need to check the length of both lists, as we only construct valid instances"). We enforce the
 *       invariant that comment claims.</li>
 *   <li><b>a negative other than {@code -5}</b> — the kernel expresses that rule as a bare Java {@code assert},
 *       enforced under {@code -ea} and silently absent in production. {@link #theKernelOnlyAssertsTheNegativeRule}
 *       measures which of the two we are seeing, so the divergence claim cannot rot.</li>
 * </ol>
 */
class A12PointerKernelDomainDiffTest {

    /** One pointer string, decided by both sides. */
    private record Case(String pointer, String why) {
    }

    private static Case c(String pointer, String why) {
        return new Case(pointer, why);
    }

    /**
     * Every case that discriminates one of the two domains, plus the shapes both accept.
     *
     * <p>The corpus is authored, and its recall limit is exactly that: it covers the sentinels, the position
     * rule, both root spellings, the bracket forms and the name grammar. The exhaustive vector sweep lives in
     * {@code A12PointerSymmetryTest}, which cannot run here because it would need a kernel call per vector.
     */
    private static List<Case> cases() {
        return List.of(
            c("", "A12's root pointer: parseFullName(\"\") returns an empty part list"),
            c("/", "the one trailing slash parseFullName excludes from its refusal by name"),
            c("Order", "one part, implicit repetition 1"),
            c("Order/Items[2]/Count", "the canonical form, [1] omitted"),
            c("/Order[1]/Items[2]/Count", "the interpreter's house form, which A12 also reads"),
            c("Order[]", "an empty bracket, which A12's pattern makes group 3 null and defaults to 1"),
            c("Order/Items[0]", "a trailing 0 addresses RepetitionsV2 on an exact pointer"),
            c("Order[0]/Items[2]", "a wildcard above the last part: a multipointer, never an exact one"),
            c("Order[0]", "a bare wildcard at the only (and therefore last) part"),
            c("Plan/Demand[-5]/Headcount", "A12's UNKNOWN, which the message channel carries"),
            c("Plan/Demand[-5]", "UNKNOWN on the last part"),
            c("Plan/Demand[-1]/Headcount", "IIdentifier.ITERATION: a template placeholder, not a pointer index"),
            c("Plan/Demand[-4]", "IIdentifier.SEMANTIC_INDEX: likewise unresolved, not an index"),
            c("Order/Items/", "a trailing slash"),
            c("Order/Items[3]x", "a part outside the grammar"),
            c("Order$/Items", "a name outside [\\w_-]+ ($ is not in \\w)"),
            c("Order Item", "a space is not in the name grammar"),
            c("Order[2][3]", "an already-indexed name growing a second bracket"));
    }

    /**
     * The exact type: our {@link A12PointerFormat#parse} accepts a string exactly when
     * {@link DocumentPointer#of(String)} does.
     *
     * <p>Note which kernel entry point this is. {@code DocumentPointer.of(String)} parses, so it exercises the
     * unsigned part pattern <em>and</em> the constructor's position rule — the same two layers our shared syntax
     * plus domain check form. Comparing against {@code of(fullName, repetitions)} instead would skip the pattern
     * and could not decide the {@code -5} cases at all.
     */
    @Test
    void theExactTypeAcceptsExactlyWhatDocumentPointerAccepts() {
        List<String> divergences = new ArrayList<>();
        for (Case testCase : cases()) {
            boolean kernel = accepts(() -> DocumentPointer.of(testCase.pointer()));
            boolean ours = accepts(() -> A12PointerFormat.INSTANCE.parse(testCase.pointer()));
            if (kernel != ours) {
                divergences.add("'" + testCase.pointer() + "' (" + testCase.why() + "): kernel="
                        + (kernel ? "accept" : "reject") + " interp=" + (ours ? "accept" : "reject"));
            }
        }
        assertTrue(divergences.isEmpty(), () -> "the exact pointer domain diverges from DocumentPointer on "
                + divergences.size() + " case(s):\n  " + String.join("\n  ", divergences));
    }

    /**
     * The exact type also agrees on the <b>value</b>, not merely on acceptance: same {@code fullName}, same
     * {@code repetitionIndexes}, same canonical rendering.
     *
     * <p>Acceptance parity alone would pass while we read {@code Order[2]} as repetition 1, so this is the half
     * that pins the reading. The canonical string is compared against {@code PathPartImpl.toString}'s own output
     * via {@code DocumentPointerImpl.toString}, which is A12 rendering itself rather than us predicting it.
     */
    @Test
    void theExactTypeReadsTheSameValueTheKernelReads() {
        List<String> divergences = new ArrayList<>();
        for (Case testCase : cases()) {
            DocumentPointer kernel;
            try {
                kernel = DocumentPointer.of(testCase.pointer());
            } catch (RuntimeException rejected) {
                continue;
            }
            var ours = A12PointerFormat.INSTANCE.parse(testCase.pointer());
            if (!kernel.fullName().equals(ours.getFullName())) {
                divergences.add("'" + testCase.pointer() + "' fullName: kernel='" + kernel.fullName()
                        + "' interp='" + ours.getFullName() + "'");
            }
            if (!kernel.repetitionIndexes().equals(ours.getRepetitionIndexes())) {
                divergences.add("'" + testCase.pointer() + "' repetitions: kernel=" + kernel.repetitionIndexes()
                        + " interp=" + ours.getRepetitionIndexes());
            }
            // DocumentPointerImpl.toString swaps in a display name for the root; compare the pointer strings.
            String kernelCanonical = kernel.size() == 0 ? "" : kernel.toString();
            if (!kernelCanonical.equals(A12PointerFormat.INSTANCE.render(ours))) {
                divergences.add("'" + testCase.pointer() + "' canonical: kernel='" + kernelCanonical
                        + "' interp='" + A12PointerFormat.INSTANCE.render(ours) + "'");
            }
        }
        assertTrue(divergences.isEmpty(), () -> "the exact pointer reads differently from DocumentPointer on "
                + divergences.size() + " case(s):\n  " + String.join("\n  ", divergences));
    }

    /**
     * The partially-known type against {@link PartiallyKnownDocumentMultiPointer}, on the axis the kernel
     * actually checks: the repetition domain.
     *
     * <p>The kernel has no string parser for this type, so the comparison is over
     * {@code of(fullName, repetitions)} with the arity held equal — the one axis where we are deliberately
     * stricter, measured separately by {@link #weAreStricterThanTheKernelOnArityDeliberately}.
     */
    @Test
    void thePartiallyKnownTypeAcceptsExactlyWhatItsOwnerAccepts() {
        List<List<Integer>> vectors = List.of(
                List.of(1, 1), List.of(1, 2), List.of(2, 1),
                List.of(0, 2), List.of(1, 0), List.of(0, 0),
                List.of(1, -5), List.of(-5, 1), List.of(-5, -5),
                List.of(1, -1), List.of(-1, 1), List.of(1, -4), List.of(1, -6), List.of(1, -7),
                List.of(1, -2), List.of(1, Integer.MAX_VALUE));

        List<String> divergences = new ArrayList<>();
        for (List<Integer> repetitions : vectors) {
            boolean kernel = accepts(() -> PartiallyKnownDocumentMultiPointer.of("/Plan/Demand", repetitions));
            boolean ours = accepts(
                    () -> A12PartiallyKnownMultiPointerFormat.INSTANCE.of("/Plan/Demand", repetitions));
            if (kernel != ours) {
                divergences.add(repetitions + ": kernel=" + (kernel ? "accept" : "reject")
                        + " interp=" + (ours ? "accept" : "reject"));
            }
        }
        assertTrue(divergences.isEmpty(),
                () -> "the partially-known domain diverges from PartiallyKnownDocumentMultiPointer on "
                        + divergences.size() + " vector(s):\n  " + String.join("\n  ", divergences));
    }

    /** The owner shape, read back from the kernel value rather than predicted: {@code fullName} and the indexes. */
    @Test
    void thePartiallyKnownTypeCarriesTheSameOwnerShape() {
        for (List<Integer> repetitions : List.of(List.of(1, 1), List.of(1, -5), List.of(0, 2), List.of(1, 0))) {
            var kernel = PartiallyKnownDocumentMultiPointer.of("/Plan/Demand", repetitions);
            var ours = A12PartiallyKnownMultiPointerFormat.INSTANCE.of("/Plan/Demand", repetitions);
            assertEquals(kernel.fullName(), ours.getFullName(), () -> "fullName for " + repetitions);
            assertEquals(kernel.repetitionIndexes(), ours.getRepetitionIndexes(),
                    () -> "repetitionIndexes for " + repetitions);
            assertEquals(kernel.toDocumentPointer().isPresent(), ours.toExactPointer() != null,
                    () -> "convertibility for " + repetitions);
            kernel.toDocumentPointer().ifPresent(exact -> assertEquals(
                    exact.repetitionIndexes(),
                    ours.toExactPointer().getRepetitionIndexes(),
                    () -> "converted exact pointer for " + repetitions));
        }
        // The root: its owner joins with a "/" prefix, so an empty part list renders as "/", not "".
        assertEquals(
                PartiallyKnownDocumentMultiPointer.of("", List.of()).fullName(),
                A12PartiallyKnownMultiPointerFormat.INSTANCE.of("", List.of()).getFullName());
    }

    /**
     * <b>Records which enforcement the kernel is actually giving us for the negative rule.</b> The rule is a bare
     * {@code assert}, so it binds only under {@code -ea}; Gradle's test JVMs enable assertions by default, and if
     * that ever changed this test would say so instead of the divergence claim quietly becoming wrong.
     */
    @Test
    void theKernelOnlyAssertsTheNegativeRule() {
        boolean assertionsEnabled = A12PointerKernelDomainDiffTest.class.desiredAssertionStatus();
        assertTrue(assertionsEnabled,
                "this suite must run with -ea, or the kernel's negative-repetition rule is not enforced at all "
                        + "and the parity assertion above measures nothing");
        assertTrue(
                !accepts(() -> PartiallyKnownDocumentMultiPointer.of("/Plan/Demand", List.of(1, -1))),
                "under -ea the kernel must refuse a negative other than -5");
    }

    /**
     * The arity divergence, asserted <b>as</b> a divergence: the kernel accepts a mismatched pair and we do not.
     *
     * <p>Written as an assertion rather than a comment so it cannot rot. If a kernel upgrade adds the check, this
     * fails and the deliberate-strictness note in {@code A12PartiallyKnownMultiPointer} stops being true.
     */
    @Test
    void weAreStricterThanTheKernelOnArityDeliberately() {
        assertTrue(accepts(() -> PartiallyKnownDocumentMultiPointer.of("/Plan/Demand", List.of(1))),
                "the kernel's constructor is expected NOT to compare fullName parts against repetitions");
        assertTrue(!accepts(() -> A12PartiallyKnownMultiPointerFormat.INSTANCE.of("/Plan/Demand", List.of(1))),
                "we enforce the arity invariant toDocumentPointer()'s own comment assumes");
    }

    /**
     * <b>The positive control.</b> Every parity assertion above passes trivially on a corpus that happens to sit
     * inside both domains, and it would then keep passing while the split was undone — so the corpus itself is
     * asserted to discriminate: some strings only the message channel reads, some neither type reads, some both.
     *
     * <p>This is the check the pre-fix code had no equivalent of. It is also what makes the parity tests' green
     * first run meaningful rather than reassuring: the {@code -5} rows are exactly the class the old hybrid type
     * accepted on the exact side, which the JVM red run measured before the fix.
     */
    @Test
    void theCorpusDiscriminatesTheTwoDomains() {
        List<String> messageChannelOnly = new ArrayList<>();
        List<String> neither = new ArrayList<>();
        List<String> both = new ArrayList<>();
        for (Case testCase : cases()) {
            boolean exact = accepts(() -> A12PointerFormat.INSTANCE.parse(testCase.pointer()));
            boolean message = accepts(
                    () -> A12PartiallyKnownMultiPointerFormat.INSTANCE.parse(testCase.pointer()));
            if (message && !exact) {
                messageChannelOnly.add(testCase.pointer());
            } else if (!message && !exact) {
                neither.add(testCase.pointer());
            } else if (message) {
                both.add(testCase.pointer());
            } else {
                throw new AssertionError("'" + testCase.pointer() + "' is an exact pointer the message channel "
                        + "refuses, which is impossible: the partially-known domain is a superset");
            }
        }
        assertTrue(messageChannelOnly.size() >= 3,
                () -> "the corpus must contain strings only the message channel reads, or the split is untested; "
                        + "found " + messageChannelOnly);
        assertTrue(neither.size() >= 4,
                () -> "the corpus must contain strings neither type reads, or the grammar is untested; found "
                        + neither);
        assertTrue(both.size() >= 5,
                () -> "the corpus must contain strings both types read, or parity is asserted over rejections "
                        + "alone; found " + both);
    }

    private static boolean accepts(Runnable construction) {
        try {
            construction.run();
            return true;
        } catch (RuntimeException | AssertionError rejected) {
            return false;
        }
    }
}
