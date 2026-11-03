package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * KERNEL-FINDINGS §13 — the rule error-text checker runs per <b>DECLARED</b> locale, so a message for a locale
 * the model does not declare is stored without ever being examined ([KF197]).
 *
 * <p><b>Why the pair is the law and neither row alone is.</b> The accepted row on its own says only "this model
 * is valid", which every well-formed model is; the rejected row on its own says only "this parameter is
 * invalid", which [KF196]'s neighbours already establish. What carries the claim is that the two rows differ in
 * <em>exactly one</em> variable — the message's locale — while the text, the condition, the model, and the rule
 * are byte-identical. That is a within-model control: the same {@code $Nonexistent$} draws
 * {@code INVALID_ENTITY} in {@code en_US} and no diagnostic at all in {@code fr_FR}.
 *
 * <p><b>Kernel 31.1.1 adds a second reader, and it is not the checker.</b> The model BUILD now reaches the
 * undeclared text: code generation looks its parameters up among the declared texts' and fails the build on a
 * miss ([KF659], {@link #anUndeclaredLocalesParametersMustBeSpelledAsADeclaredTextSpellsThem}). The rows through
 * {@link RuleValidator#check} below stay admitted because that route checks without generating code; the
 * whole-model rows of {@link #whichEntriesTheErrorTextCheckerExamines} build, and three of its four undeclared
 * defects now fail there. "Unexamined" remains the error-text checker's law.
 *
 * <p>{@link #anUndeclaredLocaleIsUnexaminedRatherThanLeniently} is the row that makes "unexamined" a
 * measurement rather than an inference: a well-formed text in the same undeclared locale is also accepted, so
 * the two undeclared rows are indistinguishable and the checker cannot be said to have looked and approved.
 *
 * <p><b>This is the measured basis for a client-side refusal</b> — {@code rule check --message} refuses an
 * undeclared locale ({@code RK_UNDECLARED_MESSAGE_LOCALE}) rather than reporting {@code valid:true} about a
 * text nothing examined. That is deliberately stricter than the kernel, which accepts the model; the reason is
 * recorded here and in [KF197] rather than left to be rediscovered as a bug.
 *
 * <p><b>Where that refusal lives, stated so it is not read as broader than it is.</b> It is in the CLI
 * ({@code RuleCheckCommand}), NOT in {@link RuleValidator#check}: the library route delegates to the kernel
 * and therefore returns {@code ok()} for an undeclared-locale text, exactly as {@link
 * #theSameTextInAnUndeclaredLocaleIsNotCheckedAtAll} measures. A library consumer that wants the stricter
 * answer asks for it — {@link RuleValidator#undeclaredMessageLocales} is that question, and it is the same
 * call the CLI makes. Deliberate: the validator's job is to report the kernel's verdict, and a refusal the
 * kernel does not make belongs at the surface that chose to be stricter.
 */
class ErrorTextLocaleScopeLawsTest {

    private static final String GROUP = "/Order";
    private static final String INVALID_ENTITY = "INVALID_ENTITY";

    /** What a declared locale WITHOUT an error text draws — measured, and what the merge must never produce. */
    private static final List<String> MISSING_TEXT_CODES =
            List.of("MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING", "RULE_HAS_NO_ERRORTEXT");

    /** Declares {@code en_US} + {@code de_DE}; {@code fr_FR} is the undeclared one every row below varies to. */
    private static final String ORDER = DocumentModelDraft.named("order").locale("en_US").locale("de_DE")
            .rootGroup("Order", o -> o
                    .stringField("Reference", s -> { })
                    .numberField("Amount", n -> n.fractionDigits(2)))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(ORDER);

    /** One condition for every row, authored typed, so only the message's locale varies across the table. */
    private static final String CONDITION =
            KernelLaws.render(Dsl.fieldNotFilled(M.field(GROUP + "/Reference")));

    /** A precondition that reads {@code Reference}, so a computation's declared text may name it. */
    private static final String READS_REFERENCE = KernelLaws.render(Dsl.fieldFilled(M.field(GROUP + "/Reference")));

    /** The one text under test — invalid in any locale the checker actually reads. */
    private static final String NAMES_NOTHING = "See $Nonexistent$";
    private static final String WELL_FORMED = "The reference is required.";

    @Test
    void aDeclaredLocaleHasItsErrorTextChecked() {
        ValidationResult r = check(new LocalizedText("en_US", NAMES_NOTHING));

        assertFalse(r.ok(), () -> "a declared locale's text must be checked: " + r.diagnostics());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().filter(INVALID_ENTITY::equals).isPresent()),
                () -> "expected " + INVALID_ENTITY + ", got: " + r.diagnostics());
    }

    /** The discriminator: same text, same model, same rule — only the locale differs, and nothing is reported. */
    @Test
    void theSameTextInAnUndeclaredLocaleIsNotCheckedAtAll() {
        ValidationResult r = check(new LocalizedText("fr_FR", NAMES_NOTHING));

        assertTrue(r.ok(), () -> "an undeclared locale's text is never examined, so nothing can be reported: "
                + r.diagnostics());
    }

    /**
     * …and the row that rules out "examined and approved": a well-formed text in that same undeclared locale is
     * accepted too, so the two undeclared rows carry no information and the checker did not run on either.
     */
    @Test
    void anUndeclaredLocaleIsUnexaminedRatherThanLeniently() {
        assertEquals(check(new LocalizedText("fr_FR", NAMES_NOTHING)).ok(),
                check(new LocalizedText("fr_FR", WELL_FORMED)).ok(),
                "a legal and an illegal text in an undeclared locale must be indistinguishable");
    }

    /** The vacuity control: this fixture CAN report a clean declared-locale text. */
    @Test
    void aWellFormedDeclaredLocaleTextIsAccepted() {
        ValidationResult r = check(new LocalizedText("en_US", WELL_FORMED));

        assertTrue(r.ok(), () -> "expected acceptance, got: " + r.diagnostics());
    }

    /**
     * The unsupplied declared locale keeps the validator's placeholder, so it is not reported missing.
     *
     * <p><b>Keyed on the CODE, because the obvious assertion is vacuous.</b> This row first asserted that no
     * diagnostic's summary contained {@code "de_DE"} — and it passes under a replace-not-merge implementation,
     * because the kernel names the language in prose ({@code "…for the language Deutsch"}) and never repeats
     * the locale tag. Measured: an unsupplied declared locale draws
     * {@code MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING} beside {@code RULE_HAS_NO_ERRORTEXT}, so those codes are
     * the observable, and a mutant that drops the placeholder instead of merging it now fails here.
     */
    @Test
    void anUnsuppliedDeclaredLocaleIsNotReportedMissing() {
        ValidationResult r = check(new LocalizedText("en_US", WELL_FORMED));

        assertTrue(r.diagnostics().stream().map(d -> d.code().orElse("")).noneMatch(MISSING_TEXT_CODES::contains),
                () -> "de_DE keeps its placeholder rather than being reported missing: " + r.diagnostics());
    }

    /** What the CLI's refusal is keyed on — the locales the model declares, read off the same header. */
    @Test
    void theValidatorNamesTheUndeclaredLocalesACallerSupplied() {
        DraftRule draft = draft(List.of(new LocalizedText("fr_FR", WELL_FORMED),
                new LocalizedText("en_US", WELL_FORMED)));

        assertEquals(List.of("fr_FR"), new RuleValidator().undeclaredMessageLocales(ORDER, draft));
        assertEquals(List.of("en_US", "de_DE"), new RuleValidator().declaredLocales(ORDER));
    }

    /**
     * Every error-text class the portable checker names, from an undeclared locale and from a declared locale's
     * REPLACED entry, each beside its declared-locale twin that draws it.
     *
     * <p>Written as raw DM-JSON rather than through {@link #check}, which merges messages by locale and would
     * collapse the duplicate before A12 saw it. The undeclared rows generalize {@link
     * #theSameTextInAnUndeclaredLocaleIsNotCheckedAtAll} from one class to four; the duplicate rows ask which of
     * two entries for one locale A12 examines.
     */
    @Test
    void whichEntriesTheErrorTextCheckerExamines() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> defects = new LinkedHashMap<>();
        defects.put("a parameter naming nothing", NAMES_NOTHING);
        defects.put("a control character", "a\tb");
        defects.put("an odd number of dollars", "a $ b");
        defects.put("the base year, in a model without one", "since $BaseYear$");
        Map<String, String> classes = Map.of(
                "a parameter naming nothing", INVALID_ENTITY,
                "a control character", "INVALID_CHARACTERS",
                "an odd number of dollars", "WRONG_NUMBER_OF_DOLLARS",
                "the base year, in a model without one", "NO_BASE_YEAR");
        // The checker never examines them, but code generation looks every parameter up among the declared texts'
        // (KF659): a control character is not a parameter, so it alone still builds.
        Map<String, String> undeclared = Map.of(
                "a parameter naming nothing", KernelLaws.NOT_BUILDABLE,
                "a control character", KernelLaws.ADMITTED,
                "an odd number of dollars", KernelLaws.NOT_BUILDABLE,
                "the base year, in a model without one", KernelLaws.NOT_BUILDABLE);
        defects.forEach((defect, text) -> {
            actual.put("en_US: " + defect + " — the twin", verdict(en(text), de(WELL_FORMED)));
            expected.put("en_US: " + defect + " — the twin", classes.get(defect));
            actual.put("fr_FR: " + defect, verdict(en(WELL_FORMED), de(WELL_FORMED),
                    new LocalizedText("fr_FR", text)));
            expected.put("fr_FR: " + defect, undeclared.get(defect));
        });
        actual.put("en_US twice, the EARLIER naming nothing",
                verdict(en(NAMES_NOTHING), en(WELL_FORMED), de(WELL_FORMED)));
        actual.put("en_US twice, the LATER naming nothing",
                verdict(en(WELL_FORMED), en(NAMES_NOTHING), de(WELL_FORMED)));
        expected.put("en_US twice, the EARLIER naming nothing", KernelLaws.ADMITTED);
        expected.put("en_US twice, the LATER naming nothing", INVALID_ENTITY);
        assertEquals(expected, actual, "which authored entries A12's error-text checker examines");
    }

    /**
     * What an undeclared locale's text must satisfy for the model to BUILD on kernel 31.1.1 ([KF659]): each
     * non-empty part between two {@code $} is spelled as some declared locale's text spells a parameter.
     *
     * <p>A12's error-text checker still never reads the text — {@link #theSameTextInAnUndeclaredLocaleIsNotCheckedAtAll}
     * stays green through the validator, which does not generate code. Code generation does: {@code FehlertextBuilder}
     * looks every message entry's parameters up among those parsed from the DECLARED locales and throws on a miss
     * (source-read at {@code eec9134}). So the rows separate spelling from meaning: a valid field the declared texts
     * do not name fails, the same field written as an absolute path fails, and the identical spelling builds. A
     * defective declared text keeps its own class and the build failure never comes, because code generation runs
     * only on a model without errors.
     */
    @Test
    void anUndeclaredLocalesParametersMustBeSpelledAsADeclaredTextSpellsThem() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a valid field no declared text names", verdict(en(WELL_FORMED), de(WELL_FORMED),
                new LocalizedText("fr_FR", "Voir $Reference$")));
        actual.put("the same spelling in the declared texts", verdict(en("See $Reference$"), de("Siehe $Reference$"),
                new LocalizedText("fr_FR", "Voir $Reference$")));
        actual.put("the same field spelled another way", verdict(en("See $Reference$"), de("Siehe $Reference$"),
                new LocalizedText("fr_FR", "Voir $/Order/Reference$")));
        actual.put("an empty pair", verdict(en(WELL_FORMED), de(WELL_FORMED), new LocalizedText("fr_FR", "Voir $$ ici")));
        actual.put("a defective declared text beside it", verdict(en(NAMES_NOTHING), de(NAMES_NOTHING),
                new LocalizedText("fr_FR", "Voir $Nonexistent$")));
        actual.put("a computation's message, a name no declared text names",
                computationVerdict(new LocalizedText("fr_FR", "Voir $Reference$"), WELL_FORMED));
        actual.put("a computation's message, the same spelling",
                computationVerdict(new LocalizedText("fr_FR", "Voir $Reference$"), "See $Reference$"));
        actual.put("a field type's pattern message", KernelLaws.verdictOf(DocumentModelDraft.named("order")
                .locale("en_US").locale("de_DE").rootGroup("Order", o -> o.stringField("Reference", s -> s
                        .pattern("[A-Z]+", "capitals").patternMessage("de_DE", "Grossbuchstaben")
                        .patternMessage("fr_FR", "Voir $Nonexistent$"))).build().toJson()));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a valid field no declared text names", KernelLaws.NOT_BUILDABLE);
        expected.put("the same spelling in the declared texts", KernelLaws.ADMITTED);
        expected.put("the same field spelled another way", KernelLaws.NOT_BUILDABLE);
        expected.put("an empty pair", KernelLaws.ADMITTED);
        expected.put("a defective declared text beside it", INVALID_ENTITY + "," + INVALID_ENTITY);
        expected.put("a computation's message, a name no declared text names", KernelLaws.NOT_BUILDABLE);
        expected.put("a computation's message, the same spelling", KernelLaws.ADMITTED);
        expected.put("a field type's pattern message", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "which undeclared-locale parameters A12's code generation can build");
    }

    /**
     * A computation into {@code Amount} whose precondition reads {@code Reference}, so a declared text may name it,
     * with two declared messages {@code declared} and one undeclared entry.
     */
    private static String computationVerdict(LocalizedText undeclared, String declared) {
        DraftComputation draft = DraftComputation.builder()
                .groupPath(GROUP).name("AmountCalc").computedFieldRelPath("../Amount")
                .alternatives(List.of(new DraftComputation.Alternative(READS_REFERENCE, "1", null)))
                .messages(List.of()).build();
        return KernelLaws.verdictOf(ModelWriter.addComputation(ORDER, draft,
                List.of(en(declared), de(declared.replace("See", "Siehe")), undeclared)));
    }

    private static String verdict(LocalizedText... messages) {
        String model = ModelWriter.addRule(ORDER, GROUP, "ProbeRule", "../Reference", "PROBE", CONDITION, "ERROR",
                List.of(messages));
        assertEquals(messages.length, model.split("\"locale\"", -1).length - 1,
                "every authored entry must reach the DM-JSON, duplicates included");
        return KernelLaws.verdictOf(model);
    }

    private static ValidationResult check(LocalizedText message) {
        return new RuleValidator().check(ORDER, draft(List.of(message)));
    }

    private static DraftRule draft(List<LocalizedText> messages) {
        return DraftRule.builder()
                .groupPath(GROUP).name("ProbeRule").errorEntityRelPath("../Reference")
                .errorCondition(CONDITION)
                .errorCode("PROBE").severity("ERROR")
                .messages(messages)
                .build();
    }
}
