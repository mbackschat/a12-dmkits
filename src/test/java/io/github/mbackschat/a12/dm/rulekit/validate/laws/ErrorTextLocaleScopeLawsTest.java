package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

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
    private static final String CONDITION = new DslRenderer()
            .render(Dsl.fieldNotFilled(M.field(GROUP + "/Reference")));

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
