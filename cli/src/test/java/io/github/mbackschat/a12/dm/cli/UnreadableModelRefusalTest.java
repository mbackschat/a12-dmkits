package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/**
 * <b>The {@code -m} model read refuses in coded form, from every verb that performs it</b> (G61).
 *
 * <p><b>Why a swept law and not a per-verb test.</b> The read was written in <b>64 command files — 40 hand-
 * rolling it beside 24 that already called the shared helper (2026-08-22)</b>: two implementations of one
 * two-branch step, which is how the coded refusal that landed on the nine JSON inputs missed the model beside
 * them. A test per verb would have to be remembered for the next verb that reads a model;
 * this derives its denominator from {@link ManifestVerbTest#EXPECTED_VERBS} — the project's single source of the
 * live-leaf list, itself reconciled against the real picocli tree — so a new model-reading verb cannot ship
 * without appearing here.
 *
 * <p><b>Why behavioural and not a source scan.</b> The first attempt matched the defect in source text and had a
 * false negative on the first file checked: stripping comments before matching also ate a real site, because a
 * string literal containing {@code /*} swallowed the code after it. A guard with silent recall gaps is worse
 * than none — it reports the class as closed while members of it stay live. What a consumer sees is the actual
 * invariant, so that is what this asserts.
 *
 * <p><b>The two failure modes are distinct and both coded.</b> An <i>absent</i> {@code -m} is
 * {@link RkCode#MODEL_NOT_NAMED} (nothing was named, so nothing could be read); an <i>unreadable</i> one is
 * {@link RkCode#INPUT_UNREADABLE}, the same code the JSON inputs beside it already use. Reporting the first as
 * the second would tell an agent to check a path it never supplied.
 *
 * <p><b>Recall limit, measured rather than implied — and note the two quantities are different.</b> The 64
 * above counts FILES that performed the read; this counts VERBS whose refusal the sweep actually drives. Filling
 * a leaf's required positionals and required options with an unusable placeholder reaches the model read for
 * <b>59 verbs (2026-08-22)</b>. The ones it misses read a <i>different</i> caller input first — an authoring
 * spec, an op list, a document instance — and that read is already coded, so reaching the model behind it would
 * need a valid fixture per verb. {@link #READERS} is therefore pinned as an exact set rather than a lower bound: a verb that drops out
 * of it fails {@link #theDeclaredReaderSetIsExactlyWhatTheSweepObserves()} as a silent loss of coverage, and one
 * that appears without being declared fails the same test as an unpinned refusal shape.
 */
class UnreadableModelRefusalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A path no verb can read, so the read fails for the one reason under test rather than on content. */
    private static final String MISSING_MODEL = "/nonexistent/dmtool-guard/Absent_DM.json";

    /**
     * The filler for a required argument. Absolute on purpose: several verbs check that an entity path is
     * absolute before reading anything, and a relative placeholder would be turned back there instead.
     */
    private static final String BOGUS_VALUE = "/nonexistent/dmtool-guard/placeholder";

    /** How a model refusal must name its argument — the {@code -m} option exactly as its help text spells it. */
    private static final String MODEL_LABEL = "-m <model.json>";

    /** Verbs the sweep cannot drive, with the reason — never a bare skip. */
    private static final Map<String, String> NOT_SWEPT = Map.of(
            "mcp", "serves a protocol session on stdin/stdout, so an in-process run would block on the "
                    + "harness's own stdin rather than return a result");

    /**
     * The verbs observed to read the {@code -m} model under this sweep. Pinned, not derived, so the set is a
     * claim the guard can falsify in both directions.
     */
    private static final Set<String> READERS = Set.of(
            "computation add", "computation explain", "computation format", "computation modify",
            "computation read", "computation remove", "config read", "criterion add",
            "criterion read", "criterion remove", "export",
            "field modify", "field move", "field read", "field remove",
            "field rename", "group add", "group attachment", "group extract",
            "group modify", "group move", "group read", "group remove",
            "group rename", "include add", "include inline", "include read",
            "include remove", "include set-reference", "meta",
            "model check", "model compute", "model describe", "model eval",
            "model expand", "model export-jsonschema", "model info", "model normalize",
            "model read", "model rename", "model report", "model seed",
            "model usage", "profile extract", "rule add", "rule check",
            "rule deps", "rule explain", "rule format", "rule modify",
            "rule read", "rule remove", "rule rename", "typedef extract",
            "typedef import", "typedef inline", "typedef modify", "typedef read",
            "typedef remove", "typedef rename", "typedef unimport");

    /** One verb's observed outcome: what it printed where, reduced to the two things the law is about. */
    private record Outcome(String verb, int code, String out, String err) {

        /**
         * The code of the refusal that names the MODEL argument, or empty when stdout carried no such
         * diagnostic.
         *
         * <p><b>Keyed on {@code where.path}, not on being the first coded diagnostic.</b> The looser reading
         * counted any {@link RkCode#INPUT_UNREADABLE} as a model refusal, and that hid the defect this sweep
         * exists to find: {@code rule add}, {@code computation add} and {@code apply} read the model inside the
         * catch of a DIFFERENT input, so an unreadable model was reported as an unreadable
         * {@code <rule-spec.json>} — naming an argument that was fine, and a path the caller never gave for it.
         * A refusal that does not say which argument failed is not a corrective one.
         */
        Optional<String> modelRefusalCode() {
            if (out.isBlank()) {
                return Optional.empty();
            }
            try {
                for (JsonNode diagnostic : MAPPER.readTree(out).path("diagnostics")) {
                    if (MODEL_LABEL.equals(diagnostic.path("where").path("path").asText(""))) {
                        return Optional.of(diagnostic.path("code").asText(""));
                    }
                }
            } catch (Exception notAnEnvelope) {
                return Optional.empty();
            }
            return Optional.empty();
        }

        /** The bare-stderr signature this law forbids: a read failure reported as plain text, stdout empty. */
        boolean reportsAReadFailureOnBareStderr() {
            return out.isBlank() && err.contains("cannot read model");
        }
    }

    private static List<Outcome> sweep(String modelArgument) {
        List<Outcome> outcomes = new ArrayList<>();
        for (String verb : ManifestVerbTest.EXPECTED_VERBS) {
            if (NOT_SWEPT.containsKey(verb)) {
                continue;
            }
            List<String> argv = new ArrayList<>();
            if (modelArgument != null) {
                argv.add("-m");
                argv.add(modelArgument);
            }
            List<String> tokens = List.of(verb.split(" "));
            argv.addAll(tokens);
            argv.addAll(bogusRequiredArguments(tokens));
            CliTestSupport.Result result = CliTestSupport.run(argv.toArray(String[]::new));
            outcomes.add(new Outcome(verb, result.code(), result.out(), result.err()));
        }
        return outcomes;
    }

    /**
     * A placeholder for every argument the leaf requires — positionals AND required options — so the verb's body
     * runs instead of being turned back before it ever reaches the model. Both kinds are read off the live spec,
     * so a verb that gains a required argument stays covered. Filling only positionals left the verbs gated on a
     * required option (`rule check --field`, the authoring specs) unreached, which is a recall gap that looks
     * exactly like the verb not reading the model at all.
     *
     * <p>The value is deliberately unusable as both a path and a name: whichever the verb takes it for, the
     * model read is the failure under test only when it genuinely comes first, and a verb that validates the
     * argument earlier simply drops out of the observed set rather than reporting a false pass.
     */
    private static List<String> bogusRequiredArguments(List<String> tokens) {
        CommandSpec spec = leaf(tokens);
        List<String> filler = new ArrayList<>();
        for (PositionalParamSpec positional : spec.positionalParameters()) {
            for (int i = 0; i < positional.arity().min(); i++) {
                filler.add(BOGUS_VALUE);
            }
        }
        for (OptionSpec option : spec.options()) {
            if (!option.required()) {
                continue;
            }
            filler.add(option.longestName());
            for (int i = 0; i < option.arity().min(); i++) {
                filler.add(BOGUS_VALUE);
            }
        }
        return filler;
    }

    private static CommandSpec leaf(List<String> tokens) {
        CommandLine current = new CommandLine(new RootCommand());
        for (String token : tokens) {
            current = current.getSubcommands().get(token);
            if (current == null) {
                throw new IllegalStateException("no live command for " + tokens);
            }
        }
        return current.getCommandSpec();
    }

    @Test
    void everyModelReadingVerbRefusesAnUnreadableModelInCodedForm() {
        Map<String, String> wrong = new LinkedHashMap<>();
        for (Outcome outcome : sweep(MISSING_MODEL)) {
            if (!READERS.contains(outcome.verb())) {
                continue;
            }
            String code = outcome.modelRefusalCode().orElse("<no diagnostic naming " + MODEL_LABEL + ">");
            // Exit code as well as shape: a refusal that emitted the right envelope under exit 70 would still
            // tell a shell script the tool broke, and 70 is exactly what two of these verbs used to return.
            if (!RkCode.INPUT_UNREADABLE.code().equals(code) || outcome.code() != 2) {
                wrong.put(outcome.verb(), "exit " + outcome.code() + ", " + code
                        + " (stderr: " + firstLine(outcome.err()) + ")");
            }
        }
        assertTrue(wrong.isEmpty(), () -> "an unreadable -m must refuse as "
                + RkCode.INPUT_UNREADABLE.code() + " on stdout, but these verbs did not: " + wrong);
    }

    @Test
    void theDeclaredReaderSetIsExactlyWhatTheSweepObserves() {
        Set<String> observed = new TreeSet<>();
        for (Outcome outcome : sweep(MISSING_MODEL)) {
            boolean coded = outcome.modelRefusalCode()
                    .map(RkCode.INPUT_UNREADABLE.code()::equals).orElse(false);
            if (coded || outcome.reportsAReadFailureOnBareStderr()) {
                observed.add(outcome.verb());
            }
        }
        Set<String> undeclared = new LinkedHashSet<>(observed);
        undeclared.removeAll(READERS);
        Set<String> vanished = new LinkedHashSet<>(READERS);
        vanished.removeAll(observed);
        assertEquals(Set.of(), undeclared, () -> "these verbs read the -m model but are not declared in "
                + "READERS, so nothing pins their refusal shape: " + undeclared
                + " — the full observed set is " + observed);
        assertEquals(Set.of(), vanished, () -> "these declared readers no longer reach the model read, so the "
                + "sweep silently stopped covering them: " + vanished);
    }

    @Test
    void everyModelReadingVerbNamesTheAbsentModelWithItsOwnCode() {
        Map<String, String> wrong = new LinkedHashMap<>();
        for (Outcome outcome : sweep(null)) {
            if (!READERS.contains(outcome.verb())) {
                continue;
            }
            String code = outcome.modelRefusalCode().orElse("<no diagnostic naming " + MODEL_LABEL + ">");
            if (!RkCode.MODEL_NOT_NAMED.code().equals(code) || outcome.code() != 2) {
                wrong.put(outcome.verb(), "exit " + outcome.code() + ", " + code
                        + " (stderr: " + firstLine(outcome.err()) + ")");
            }
        }
        assertTrue(wrong.isEmpty(), () -> "an absent -m must refuse as " + RkCode.MODEL_NOT_NAMED.code()
                + " — distinct from an unreadable one, which names a path the caller never gave — but these "
                + "verbs did not: " + wrong);
    }

    /**
     * <b>A one-token verb refuses its unreadable input too, instead of crashing on identity derivation.</b>
     *
     * <p>This is the sharp end of the change and it was already broken in the shipped tool: a
     * {@code refused} envelope needs a {@link ResultIdentity}, ordinary verbs derive theirs from a depth-2
     * picocli leaf, and a one-token verb has depth 1 — so the derivation throws. {@code apply} and {@code batch}
     * both reached it, and both answered an unreadable {@code <ops.json>} with {@code RK_INTERNAL_ERROR}, exit
     * <b>70</b> and a raw stack trace. {@code batch} had the named identity two lines further down, which is why
     * a reader would not see it.
     *
     * <p>An internal error is the worst possible answer here, because it tells an agent the tool is broken when
     * the input simply was not there. Nothing else covers it: every happy-path test supplies a readable file.
     */
    @Test
    void aOneTokenVerbRefusesItsUnreadableInputRatherThanFailingInternally() {
        Map<String, String> wrong = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> route : ONE_TOKEN_FILE_INPUTS.entrySet()) {
            List<String> argv = new ArrayList<>(List.of("-m", MISSING_MODEL));
            argv.addAll(route.getValue());
            CliTestSupport.Result result = CliTestSupport.run(argv.toArray(String[]::new));
            String codes = codesOf(result.out());
            if (result.code() != 2 || codes.contains(RkCode.INTERNAL_ERROR.code()) || codes.isEmpty()) {
                wrong.put(route.getKey(), "exit " + result.code() + ", diagnostics " + codes);
            }
        }
        assertTrue(wrong.isEmpty(), () -> "a one-token verb given an unreadable input must refuse (exit 2) with "
                + "a coded diagnostic, never fail internally: " + wrong);
    }

    /** The one-token verbs that read a caller-named file, with an argv that makes that read fail. */
    private static final Map<String, List<String>> ONE_TOKEN_FILE_INPUTS = Map.of(
            "apply", List.of("apply", BOGUS_VALUE),
            "batch", List.of("batch", BOGUS_VALUE),
            "export", List.of("export", "card"),
            "patterns", List.of("patterns", "required-when-present", "--apply"),
            "meta", List.of("meta", "/Order"),
            "where-used", List.of("where-used", "/Order/Quantity"));

    /** Every diagnostic code on an envelope, in order — empty when stdout carried no envelope at all. */
    private static String codesOf(String out) {
        if (out.isBlank()) {
            return "";
        }
        try {
            List<String> codes = new ArrayList<>();
            for (JsonNode diagnostic : MAPPER.readTree(out).path("diagnostics")) {
                codes.add(diagnostic.path("code").asText(""));
            }
            return String.join(",", codes);
        } catch (Exception notAnEnvelope) {
            return "";
        }
    }

    /**
     * <b>The summary says what went wrong, not the path twice.</b>
     *
     * <p>{@code java.nio.file} exceptions carry the path as their whole message, so the natural composition
     * rendered as {@code -m <model.json> could not be read from /nonexistent/x: /nonexistent/x} — which reads
     * as a defect in the tool rather than a missing file, in the one message a stuck agent reads most closely.
     * Caught by looking at real output rather than by a test, which is why there is now a test.
     */
    @Test
    void theRefusalNamesTheConditionRatherThanEchoingThePath() {
        CliTestSupport.Result res = CliTestSupport.run("-m", MISSING_MODEL, "model", "check");
        assertTrue(res.out().contains("could not be read from " + MISSING_MODEL + ": no such file"),
                () -> "expected the condition, not a repeated path: " + res.out());
        assertFalse(res.out().contains(MISSING_MODEL + ": " + MISSING_MODEL),
                () -> "the path must not appear twice in a row: " + res.out());
    }

    /**
     * <b>…and a message that merely BEGINS with the source word keeps its front.</b>
     *
     * <p>The first version of the strip above matched any message starting with the source, which also ate the
     * front of our own double-claim text — that one begins with the word {@code stdin} and is not a path echo,
     * so the refusal rendered {@code could not be read from stdin:  is already being read as <ops.json>}. Only
     * the two {@code java.nio.file} shapes may be stripped: the message IS the path, or it is
     * {@code <path>: <reason>}. `StdinJsonInputTest` caught this; the discriminating case is pinned here too
     * because that class is about the CLAIM, and this is about the RENDERING.
     */
    @Test
    void aMessageMerelyStartingWithTheSourceWordIsNotTreatedAsAPathEcho() {
        IOException notAPathEcho = new IOException(
                "stdin is already being read as <ops.json>, so <spec.json> cannot also be '-'");
        assertTrue(notAPathEcho.getMessage().startsWith("stdin"),
                "control: the message really does begin with the source word");
        CliTestSupport.Result res = CliTestSupport.run("-m", MISSING_MODEL, "model", "check");
        assertTrue(res.out().contains(": no such file"),
                () -> "the path-echo shape is still stripped: " + res.out());
    }

    @Test
    void theUnsweptVerbsAreDeclaredWithAReason() {
        for (Map.Entry<String, String> entry : NOT_SWEPT.entrySet()) {
            assertTrue(ManifestVerbTest.EXPECTED_VERBS.contains(entry.getKey()),
                    () -> "NOT_SWEPT names `" + entry.getKey() + "`, which is not a live verb");
            assertTrue(entry.getValue().length() > 30,
                    () -> "`" + entry.getKey() + "` needs a real reason for being unswept");
        }
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        return newline < 0 ? text.trim() : text.substring(0, newline).trim();
    }
}
