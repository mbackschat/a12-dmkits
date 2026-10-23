package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import com.mgmtp.a12.kernel.md.document.apiV2.DocumentPointer;
import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer;
import com.mgmtp.a12.kernel.md.document.apiV2.PathPart;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer;
import io.github.mbackschat.a12.dm.interpreter.A12PathPart;
import io.github.mbackschat.a12.dm.interpreter.A12Pointer;
import io.github.mbackschat.a12.dm.interpreter.A12PointerFormat;

/**
 * <b>Every interpreter pointer entry point, measured against ITS OWN kernel entry point.</b>
 *
 * <p>The organising rule is the correction this file exists to hold: <b>A12 does not give its pointer entry
 * points one shared domain</b>, so no factory may be used as the oracle for another. Measured:
 *
 * <table>
 *   <tr><th>kernel entry point</th><th>what it validates</th></tr>
 *   <tr><td>{@code PathPart.of(name, index)}</td><td>a non-empty name and {@code index >= 0} — nothing more;
 *       {@code "a/b"}, {@code "Order$"} and {@code "Order[2]"} are all accepted</td></tr>
 *   <tr><td>{@code DocumentPointer.of(List&lt;PathPart&gt;)}</td><td>the position rule only</td></tr>
 *   <tr><td>{@code DocumentPointer.of(String)}</td><td>the part-string grammar {@code ([\\w_-]+)(\\[(\\d+)?\\])?}
 *       plus the position rule</td></tr>
 *   <tr><td>{@code PartiallyKnownDocumentMultiPointer.of(fullName, reps)}</td><td>splitting and normalization;
 *       no name rule at all</td></tr>
 *   <tr><td>{@code toDocumentPointer()}</td><td>convertibility — and it <em>raises</em> on the root</td></tr>
 * </table>
 *
 * <p>A previous revision put the string grammar behind all five, on the theory that one type has one domain.
 * That produced eight measured over-rejections of values A12 accepts — including on the READ path, where an
 * over-strict check makes a legal kernel value unreadable rather than merely unauthorable. This file is
 * partitioned by entry point so that class of error cannot recur silently: each test names exactly one owner
 * factory, and none of them shares a corpus with another.
 *
 * <p><b>Three deliberate divergences are asserted AS divergences</b>, so departing from A12 stays a recorded
 * decision rather than an accident, and a kernel upgrade that closes one fails this build. Two are stricter than
 * the kernel; the third, {@code -6}, admits what A12's runtime emits although its own assertion refuses it.
 */
class A12PointerKernelDomainDiffTest {

    // ---- entry point 1: PathPart.of(name, index) ------------------------------------------------

    /**
     * The part factory. Its domain is non-emptiness plus {@code index >= 0}, and deliberately NOT the string
     * grammar — {@link #theStringParserHasItsOwnNarrowerDomain} is the contrast that makes that concrete.
     */
    @Test
    void thePartFactoryAcceptsExactlyWhatPathPartAccepts() {
        List<String> divergences = new ArrayList<>();
        for (String name : List.of("Order", "Order$", "Order Item", "Order[2]", "a/b", "Ümlaut", "_x-y", "")) {
            for (int index : List.of(0, 1, 3)) {
                boolean kernel = accepts(() -> PathPart.of(name, index));
                boolean ours = accepts(() -> new A12PathPart(name, index));
                if (kernel != ours) {
                    divergences.add("PathPart('" + name + "', " + index + "): kernel="
                            + verdict(kernel) + " interp=" + verdict(ours));
                }
            }
        }
        for (int index : List.of(-1, -4, -5, -6, -7)) {
            boolean kernel = accepts(() -> PathPart.of("Order", index));
            boolean ours = accepts(() -> new A12PathPart("Order", index));
            if (kernel != ours) {
                divergences.add("PathPart('Order', " + index + "): kernel=" + verdict(kernel)
                        + " interp=" + verdict(ours));
            }
        }
        assertTrue(divergences.isEmpty(), () -> "the part factory diverges from PathPart on "
                + divergences.size() + " case(s):\n  " + String.join("\n  ", divergences));
    }

    // ---- entry point 2: DocumentPointer.of(List<PathPart>) --------------------------------------

    /** The part-list factory: the position rule, over parts their own factory already accepted. */
    @Test
    void thePartListFactoryAcceptsExactlyWhatDocumentPointerOfListAccepts() {
        List<String> divergences = new ArrayList<>();
        for (List<Integer> vector : List.of(
                List.of(1), List.of(0), List.of(3),
                List.of(1, 1), List.of(1, 0), List.of(0, 1), List.of(2, 3), List.of(0, 0))) {
            boolean kernel = accepts(() -> DocumentPointer.of(pathParts(vector)));
            boolean ours = accepts(() -> A12Pointer.Companion.of(a12PathParts(vector)));
            if (kernel != ours) {
                divergences.add(vector + ": kernel=" + verdict(kernel) + " interp=" + verdict(ours));
            }
        }
        // …and a name the STRING parser would refuse must still be constructible here, on both sides.
        assertEquals(
                DocumentPointer.of(List.of(PathPart.of("a/b", 1))).fullName(),
                A12Pointer.Companion.of(List.of(new A12PathPart("a/b", 1))).getFullName(),
                "a part-list factory does not apply the string grammar");
        assertTrue(divergences.isEmpty(), () -> "the part-list factory diverges on "
                + divergences.size() + " vector(s):\n  " + String.join("\n  ", divergences));
    }

    // ---- entry point 3: DocumentPointer.of(String) ----------------------------------------------

    /**
     * The string parser, which is where {@code [\w_-]+} actually lives. Its corpus is separate from the value
     * factories' on purpose: using this as the oracle for {@code PathPart} is precisely the mistake that put a
     * codec precondition on a value type.
     */
    @Test
    void theStringParserHasItsOwnNarrowerDomain() {
        List<String> divergences = new ArrayList<>();
        for (String pointer : List.of(
                "", "/", "Order", "Order/Items[2]/Count", "/Order[1]/Items[2]/Count", "Order[]",
                "Order/Items[0]", "Order[0]/Items[2]", "Order[0]",
                "Plan/Demand[-5]/Headcount", "Plan/Demand[-1]/Headcount", "Plan/Demand[-4]",
                "Order/Items/", "Order/Items[3]x", "Order$/Items", "Order Item", "Order[2][3]")) {
            boolean kernel = accepts(() -> DocumentPointer.of(pointer));
            boolean ours = accepts(() -> A12PointerFormat.INSTANCE.parse(pointer));
            if (kernel != ours) {
                divergences.add("'" + pointer + "': kernel=" + verdict(kernel) + " interp=" + verdict(ours));
            }
        }
        assertTrue(divergences.isEmpty(), () -> "the string parser diverges from DocumentPointer.of(String) on "
                + divergences.size() + " case(s):\n  " + String.join("\n  ", divergences));
    }

    /** The parser also READS the same value, not merely the same accept/reject set. */
    @Test
    void theStringParserReadsTheSameValueTheKernelReads() {
        List<String> divergences = new ArrayList<>();
        for (String pointer : List.of("", "/", "Order", "Order/Items[2]/Count", "/Order[1]/Items[2]/Count",
                "Order[]", "Order/Items[0]")) {
            DocumentPointer kernel = DocumentPointer.of(pointer);
            A12Pointer ours = A12PointerFormat.INSTANCE.parse(pointer);
            if (!kernel.fullName().equals(ours.getFullName())) {
                divergences.add("'" + pointer + "' fullName: kernel='" + kernel.fullName()
                        + "' interp='" + ours.getFullName() + "'");
            }
            if (!kernel.repetitionIndexes().equals(ours.getRepetitionIndexes())) {
                divergences.add("'" + pointer + "' repetitions: kernel=" + kernel.repetitionIndexes()
                        + " interp=" + ours.getRepetitionIndexes());
            }
            // DocumentPointerImpl.toString swaps in a display name for the root; compare pointer strings.
            String kernelCanonical = kernel.size() == 0 ? "" : kernel.toString();
            if (!kernelCanonical.equals(A12PointerFormat.INSTANCE.render(ours))) {
                divergences.add("'" + pointer + "' canonical: kernel='" + kernelCanonical
                        + "' interp='" + A12PointerFormat.INSTANCE.render(ours) + "'");
            }
        }
        assertTrue(divergences.isEmpty(), () -> "the string parser reads differently on "
                + divergences.size() + " case(s):\n  " + String.join("\n  ", divergences));
    }

    // ---- entry point 4: PartiallyKnownDocumentMultiPointer.of(fullName, reps) --------------------

    /** The message factory's INDEX domain, with the arity held equal so only one axis varies. */
    @Test
    void theMessageFactoryAcceptsExactlyTheIndexDomainItsOwnerAccepts() {
        List<String> divergences = new ArrayList<>();
        for (List<Integer> repetitions : List.of(
                List.of(1, 1), List.of(1, 2), List.of(2, 1),
                List.of(0, 2), List.of(1, 0), List.of(0, 0),
                List.of(1, -5), List.of(-5, 1), List.of(-5, -5),
                List.of(1, -1), List.of(-1, 1), List.of(1, -4), List.of(1, -7),
                List.of(1, -2), List.of(1, Integer.MAX_VALUE))) {
            boolean kernel = accepts(() -> PartiallyKnownDocumentMultiPointer.of("/Plan/Demand", repetitions));
            boolean ours = accepts(() -> A12PartiallyKnownMultiPointer.Companion.of("/Plan/Demand", repetitions));
            if (kernel != ours) {
                divergences.add(repetitions + ": kernel=" + verdict(kernel) + " interp=" + verdict(ours));
            }
        }
        assertTrue(divergences.isEmpty(), () -> "the message factory's index domain diverges on "
                + divergences.size() + " vector(s):\n  " + String.join("\n  ", divergences));
    }

    /**
     * The message factory's <b>fullName</b> domain, which validates splitting and normalization and nothing
     * else. Every case here was an over-rejection before this correction.
     */
    @Test
    void theMessageFactoryAcceptsEveryFullNameItsOwnerAccepts() {
        List<String> divergences = new ArrayList<>();
        for (List<Object> testCase : List.of(
                List.of("/Order", List.of(1)),
                List.of("/Order$", List.of(1)),
                List.of("/Order Item", List.of(1)),
                List.of("/Order[2]", List.of(1)),
                List.of("/Order//Items", List.of(1, 1, 1)),
                List.of("Order/Items", List.of(1, 1)),
                List.of("", List.of()),
                List.of("/", List.of()),
                List.of("/Order/", List.of(1)))) {
            String fullName = (String) testCase.get(0);
            @SuppressWarnings("unchecked")
            List<Integer> repetitions = (List<Integer>) testCase.get(1);
            boolean kernel = accepts(() -> PartiallyKnownDocumentMultiPointer.of(fullName, repetitions));
            boolean ours = accepts(() -> A12PartiallyKnownMultiPointer.Companion.of(fullName, repetitions));
            if (kernel != ours) {
                divergences.add("'" + fullName + "' " + repetitions + ": kernel=" + verdict(kernel)
                        + " interp=" + verdict(ours));
                continue;
            }
            if (kernel) {
                assertEquals(
                        PartiallyKnownDocumentMultiPointer.of(fullName, repetitions).fullName(),
                        A12PartiallyKnownMultiPointer.Companion.of(fullName, repetitions).getFullName(),
                        () -> "normalized fullName for '" + fullName + "'");
            }
        }
        assertTrue(divergences.isEmpty(), () -> "the message factory's fullName domain diverges on "
                + divergences.size() + " case(s):\n  " + String.join("\n  ", divergences));
    }

    // ---- entry point 5: toDocumentPointer(), INCLUDING the root ---------------------------------

    /**
     * The conversion corpus, spanning every axis {@code toDocumentPointer()} branches on: a convertible pointer,
     * the terminal {@code RepetitionsV2} zero, a wildcard above the last level, an unresolved level, a one-part
     * pointer, the root, and the fullName shapes the message factory admits but the exact type's PARTS cannot
     * take — the interior-empty {@code /Order//Items} above all, which is the only case that reaches the kernel's
     * own {@code catch}.
     *
     * <p>The names that are legal values but unwritable as a pointer STRING ({@code Order$}, {@code Order Item})
     * belong here too: the conversion builds parts, it does not render text, so they must convert.
     */
    private static final List<List<Object>> CONVERSION_CORPUS = List.of(
            List.of("/Order/Items", List.of(1, 2)),
            List.of("/Order/Items", List.of(1, 0)),
            List.of("/Order/Items", List.of(0, 2)),
            List.of("/Plan/Demand", List.of(1, -5)),
            List.of("/Plan/Demand", List.of(-5, 1)),
            List.of("/Order", List.of(1)),
            List.of("/Order", List.of(0)),
            List.of("", List.<Integer>of()),
            List.of("/Order//Items", List.of(1, 1, 1)),
            List.of("/Order//Items", List.of(1, 0, 1)),
            List.of("//Items", List.of(1, 1)),
            List.of("/Order$/Items", List.of(1, 2)),
            List.of("/Order Item", List.of(3)),
            List.of("/Order[2]", List.of(1)));

    /**
     * The conversion, root included, <b>with the outcome KINDS kept apart and measured against the kernel that
     * runs in PRODUCTION</b> — assertions disabled.
     *
     * <p>Two corrections are baked in here, both of which had produced a wrong claim before.
     *
     * <p><b>Collapsing the outcomes.</b> A single "raised" bucket covered a caller-contract
     * {@code IllegalArgumentException}, a JVM {@code AssertionError} from a bare kernel {@code assert}, and would
     * have covered a {@code null} too — so a side that failed for one reason agreed with a side that failed for a
     * completely different one. Each outcome is now named, and the failure message prints both namings.
     *
     * <p><b>Taking the test JVM for production.</b> This suite runs under {@code -ea}, and the conversion has a
     * case where that changes the answer ({@link #theConversionIsAssertionDependentOnExactlyOneShape}). Holding
     * the interpreter to the {@code -ea} observation would make it raise where A12 answers, so the oracle here is
     * the assertion-free kernel and the {@code -ea} difference is recorded by that test instead of absorbed by
     * this one.
     *
     * <p>The root is the case a previous revision skipped and then silently "repaired": A12 raises there, because
     * {@code toDocumentPointer()} evaluates {@code subList(0, size - 1)} in its {@code if} condition, outside its
     * own {@code try}, so it fails identically in both modes.
     */
    @Test
    void theConversionAgreesOutcomeForOutcomeIncludingOnTheRoot() throws Exception {
        List<String> divergences = new ArrayList<>();
        try (AssertionFreeKernel production = new AssertionFreeKernel()) {
            for (List<Object> testCase : CONVERSION_CORPUS) {
                String fullName = fullNameOf(testCase);
                List<Integer> repetitions = repetitionsOf(testCase);

                String kernel = outcome(() -> production.convert(fullName, repetitions));
                String ours = outcome(() -> ourConversion(fullName, repetitions));
                if (!kernel.equals(ours)) {
                    divergences.add("'" + fullName + "' " + repetitions + ": kernel=" + kernel
                            + " interp=" + ours);
                }
            }
        }
        assertTrue(divergences.isEmpty(), () -> "toDocumentPointer diverges on "
                + divergences.size() + " case(s):\n  " + String.join("\n  ", divergences));
    }

    /**
     * <b>Which conversion cases depend on assertions, measured rather than reasoned.</b>
     *
     * <p>{@code toDocumentPointer()} catches the part-construction {@code IllegalArgumentException} and writes
     * {@code assert false} before returning empty, so a shape that reaches that catch is {@code Optional.empty()}
     * in production and an {@code AssertionError} under {@code -ea}.
     *
     * <p>The pinned list is the measurement, and it is narrower than the obvious prediction — which is the point
     * of pinning it. {@code ("/Order//Items", [1, 0, 1])} looks like the same interior-empty shape, but its
     * interior wildcard fails the position rule <em>first</em>, so the conversion answers empty without ever
     * constructing a part and never reaches the assert. Deriving this list by reading the source got it wrong;
     * running both modes got it right.
     */
    @Test
    void theConversionIsAssertionDependentOnExactlyOneShape() throws Exception {
        assertTrue(A12PointerKernelDomainDiffTest.class.desiredAssertionStatus(),
                "this test compares -ea against -da, so the suite itself must be running with -ea");

        List<String> modeDependent = new ArrayList<>();
        try (AssertionFreeKernel production = new AssertionFreeKernel()) {
            for (List<Object> testCase : CONVERSION_CORPUS) {
                String fullName = fullNameOf(testCase);
                List<Integer> repetitions = repetitionsOf(testCase);

                String enabled = outcome(() -> kernelConversion(fullName, repetitions));
                String disabled = outcome(() -> production.convert(fullName, repetitions));
                if (!enabled.equals(disabled)) {
                    modeDependent.add("'" + fullName + "' " + repetitions + ": -ea=" + enabled
                            + " -da=" + disabled);
                }
            }
        }

        assertEquals(
                List.of("'/Order//Items' [1, 1, 1]: -ea=assertion -da=absent",
                        "'//Items' [1, 1]: -ea=assertion -da=absent"),
                modeDependent,
                "the assertion-dependent cases are exactly those that reach the kernel's own catch — an empty "
                        + "level whose repetitions otherwise satisfy the position rule. Anything else appearing "
                        + "here is a new kernel fact to record, not to absorb");
    }

    private static String fullNameOf(List<Object> testCase) {
        return (String) testCase.get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> repetitionsOf(List<Object> testCase) {
        return (List<Integer>) testCase.get(1);
    }

    private static String kernelConversion(String fullName, List<Integer> repetitions) {
        return PartiallyKnownDocumentMultiPointer.of(fullName, repetitions).toDocumentPointer()
                .map(DocumentPointer::repetitionIndexes).map(Object::toString).orElse(null);
    }

    private static String ourConversion(String fullName, List<Integer> repetitions) {
        A12Pointer exact = A12PartiallyKnownMultiPointer.Companion.of(fullName, repetitions).toExactPointer();
        return exact == null ? null : exact.getRepetitionIndexes().toString();
    }

    // ---- the three deliberate divergences, asserted AS divergences -------------------------------

    /**
     * {@code IIdentifier.PARALLEL_ITERATION} ({@code -6}) is admitted although A12's assertion refuses it, because
     * A12's own runtime puts it on a message pointer: a rule reporting on a keyed group beside a join carries it
     * on its references (KERNEL-FINDINGS kf607, {@code KernelProbeParallelIterationPointerTest}). Under
     * {@code -ea} the assertion then raises from A12's own accessor; in production the value passes, so refusing
     * it made those messages unreadable. Asserted against both assertion modes, so either side moving fails here.
     */
    @Test
    void weAdmitTheParallelIterationIndexA12EmitsDespiteItsOwnAssertion() throws Exception {
        List<Integer> parallel = List.of(1, A12PartiallyKnownMultiPointer.PARALLEL_ITERATION);
        assertTrue(!accepts(() -> PartiallyKnownDocumentMultiPointer.of("/Plan/Demand", parallel)),
                "under -ea A12 refuses -6, the assertion this divergence departs from");
        try (AssertionFreeKernel production = new AssertionFreeKernel()) {
            assertTrue(production.constructs("/Plan/Demand", parallel),
                    "with assertions off, as in production, A12 holds -6 on a message pointer");
        }
        assertTrue(accepts(() -> A12PartiallyKnownMultiPointer.Companion.of("/Plan/Demand", parallel)),
                "and so must we, or a kernel message carrying it cannot be read");
    }

    /**
     * Records which enforcement the kernel is actually giving us for the negative-index rule. The rule is a bare
     * {@code assert}, so it binds only under {@code -ea}; if that ever changed, this says so instead of the
     * divergence claim quietly becoming wrong.
     */
    @Test
    void theKernelOnlyAssertsTheNegativeRule() {
        assertTrue(A12PointerKernelDomainDiffTest.class.desiredAssertionStatus(),
                "this suite must run with -ea, or the kernel's negative-repetition rule is not enforced at all "
                        + "and the parity assertions above measure nothing");
        assertTrue(!accepts(() -> PartiallyKnownDocumentMultiPointer.of("/Plan/Demand", List.of(1, -1))),
                "under -ea the kernel must refuse a negative other than -5");
    }

    /**
     * The arity divergence, asserted so a kernel upgrade that adds the check fails rather than passes.
     *
     * <p><b>And the contrast that makes it a per-entry-point fact rather than a kernel-wide one:</b> A12's
     * <em>exact</em> pointer factory takes the same-looking {@code (fullName, repetitions)} pair and DOES enforce
     * the pairing — {@code PathPartImpl.toPathPartList} refuses "path and repetitions have different length". So
     * arity is not something A12 does or does not check; it is checked at one of these two entry points and not
     * the other, which is this file's whole thesis. Measured on both rather than read off the source.
     */
    @Test
    void weAreStricterThanTheKernelOnArityDeliberately() {
        assertTrue(accepts(() -> PartiallyKnownDocumentMultiPointer.of("/Plan/Demand", List.of(1))),
                "the message factory is expected NOT to compare fullName parts against repetitions");
        assertTrue(!accepts(() -> A12PartiallyKnownMultiPointer.Companion.of("/Plan/Demand", List.of(1))),
                "we enforce the arity invariant toDocumentPointer()'s own comment assumes");

        assertTrue(!accepts(() -> DocumentPointer.of("/Plan/Demand", List.of(1))),
                "the EXACT factory does compare them — same argument shape, different domain");
        assertTrue(!accepts(() -> A12PointerFormat.INSTANCE.format("/Plan/Demand", List.of(1))),
                "and so do we, on the exact side");
    }

    /**
     * <b>The positive control.</b> Every parity test above passes trivially on a corpus that sits inside both
     * domains, so the corpora are asserted to discriminate: the value factories must accept names the string
     * parser refuses, and the string parser must refuse some the value factories accept. Without this, undoing
     * the entry-point split would keep every test green.
     */
    @Test
    void theCorporaDiscriminateTheEntryPoints() {
        // The discriminator is the WRITE direction. `parse` does not "refuse" `Order[2]` or `a/b` — it reads
        // them as DIFFERENT pointers (`Order` repetition 2; a two-part path), which is exactly why the codec
        // may not write such a name. Asking whether `parse` rejects them was this control's own first mistake.
        List<String> unwritable = new ArrayList<>();
        for (String name : List.of("Order$", "Order Item", "Order[2]", "a/b")) {
            boolean asValue = accepts(() -> new A12PathPart(name, 1));
            boolean writable = accepts(() -> A12PointerFormat.INSTANCE.format(name, List.of(1)));
            if (asValue && !writable) {
                unwritable.add(name);
            }
        }
        assertTrue(unwritable.size() >= 4,
                () -> "the corpus must contain names a PathPart accepts and the string codec cannot write, or "
                        + "the entry-point split is untested; found " + unwritable);

        // …and the round trip really does change the pointer for the two that parse "successfully".
        assertEquals(2, A12PointerFormat.INSTANCE.parse("Order[2]").getRepetitionIndexes().get(0),
                "'Order[2]' as a STRING is the pointer 'Order' repetition 2, not a one-part name");
        assertEquals(2, A12PointerFormat.INSTANCE.parse("a/b").getSize(),
                "'a/b' as a STRING is a two-part pointer, not a one-part name");

        assertTrue(!accepts(() -> new A12PathPart("", 1)) && accepts(() -> new A12PathPart("Order", 0)),
                "the part factory must still refuse something and accept something");
    }

    private static List<PathPart> pathParts(List<Integer> repetitions) {
        List<PathPart> parts = new ArrayList<>();
        for (int i = 0; i < repetitions.size(); i++) {
            parts.add(PathPart.of("P" + i, repetitions.get(i)));
        }
        return parts;
    }

    private static List<A12PathPart> a12PathParts(List<Integer> repetitions) {
        List<A12PathPart> parts = new ArrayList<>();
        for (int i = 0; i < repetitions.size(); i++) {
            parts.add(new A12PathPart("P" + i, repetitions.get(i)));
        }
        return parts;
    }

    /**
     * One conversion's outcome, with the failure KINDS kept apart: a converted pointer prints its indices,
     * {@code null}/empty prints {@code absent}, a bare {@code assert} prints {@code assertion}, and anything
     * thrown prints its own exception class. Two sides that both fail now only agree when they fail alike.
     */
    private static String outcome(java.util.concurrent.Callable<String> conversion) {
        String converted;
        try {
            converted = conversion.call();
        } catch (AssertionError assertion) {
            return "assertion";
        } catch (Throwable raised) {
            return "raised:" + raised.getClass().getSimpleName();
        }
        return converted == null ? "absent" : "converted" + converted;
    }

    /**
     * A12's {@code apiV2} pointer classes, reloaded in a child classloader with assertions <b>off</b>, so the
     * production behaviour of a bare kernel {@code assert} can be measured from inside a {@code -ea} suite.
     *
     * <p>Parent-last for exactly that package: delegating to the app loader would hand back the already-loaded,
     * assertion-enabled classes and the measurement would silently be a copy of the {@code -ea} one.
     */
    private static final class AssertionFreeKernel implements AutoCloseable {

        private static final String POINTER_PACKAGE = "com.mgmtp.a12.kernel.md.document.apiV2.";

        private final URLClassLoader loader;
        private final Method of;
        private final Method toDocumentPointer;
        private final Method repetitionIndexes;

        AssertionFreeKernel() throws Exception {
            loader = new URLClassLoader(classpath(), ClassLoader.getPlatformClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    return name.startsWith(POINTER_PACKAGE) ? findLoadedOrDefine(name, resolve)
                            : super.loadClass(name, resolve);
                }

                private Class<?> findLoadedOrDefine(String name, boolean resolve) throws ClassNotFoundException {
                    Class<?> already = findLoadedClass(name);
                    Class<?> loaded = already != null ? already : findClass(name);
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            };
            loader.setDefaultAssertionStatus(false);
            Class<?> pointer = loader.loadClass(POINTER_PACKAGE + "PartiallyKnownDocumentMultiPointer");
            assertEquals(false, pointer.desiredAssertionStatus(),
                    "the reloaded kernel must have assertions OFF, or this measures -ea twice");
            assertTrue(pointer != PartiallyKnownDocumentMultiPointer.class,
                    "the reloaded kernel must be a DIFFERENT class, or the child loader delegated to the parent");
            of = pointer.getMethod("of", String.class, List.class);
            toDocumentPointer = pointer.getMethod("toDocumentPointer");
            repetitionIndexes = loader.loadClass(POINTER_PACKAGE + "DocumentPointer")
                    .getMethod("repetitionIndexes");
        }

        /** Whether the production factory builds the pointer at all, before any conversion. */
        boolean constructs(String fullName, List<Integer> repetitions) throws Exception {
            try {
                of.invoke(null, fullName, repetitions);
                return true;
            } catch (InvocationTargetException refused) {
                return false;
            }
        }

        /** The converted indices, or {@code null} when A12 answers {@code Optional.empty()}. */
        String convert(String fullName, List<Integer> repetitions) throws Exception {
            try {
                Object converted = ((java.util.Optional<?>) toDocumentPointer
                        .invoke(of.invoke(null, fullName, repetitions))).orElse(null);
                return converted == null ? null : repetitionIndexes.invoke(converted).toString();
            } catch (InvocationTargetException wrapped) {
                throw rethrow(wrapped);
            }
        }

        /** Reflection wraps the real outcome; unwrap it so {@link #outcome} classifies the kernel's throw. */
        private static RuntimeException rethrow(InvocationTargetException wrapped) throws Exception {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            throw (Exception) cause;
        }

        private static URL[] classpath() throws Exception {
            List<URL> urls = new ArrayList<>();
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
                urls.add(java.nio.file.Path.of(entry).toUri().toURL());
            }
            return urls.toArray(new URL[0]);
        }

        @Override public void close() throws java.io.IOException {
            loader.close();
        }
    }

    private static String verdict(boolean accepted) {
        return accepted ? "accept" : "reject";
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
