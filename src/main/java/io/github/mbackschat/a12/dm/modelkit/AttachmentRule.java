package io.github.mbackschat.a12.dm.modelkit;

/**
 * The four rules an A12 <b>attachment</b> group must carry (FEATURE-GAPS). The kernel's
 * {@code AttachmentGroupRule} validates an attachment group by matching each rule's {@code errorCondition}
 * <em>string</em> (in the model's condition language) against a fixed set — so the generator emits exactly
 * these, verbatim. This enum is the closed catalog: each constant carries its rule name, error code, the
 * field its error points at, the en/de condition strings, and a default en/de message. Authors only ever
 * reference the constants — to override a rule's message via
 * {@code AttachmentBuilder.message(AttachmentRule, locale, text)}.
 *
 * <p>The condition/message data is package-private: it feeds the generator, it is not a public contract.
 * If the kernel ever adds a fifth mandated rule, adding a constant here makes every exhaustive use a
 * compile error until handled.
 */
public enum AttachmentRule {

    /** Exactly one of an attachment reference or inline content must be filled. */
    EXACTLY_ONE_SOURCE("AttachmentExactlyOneSource", "ATTACHMENT_EXACTLY_ONE_SOURCE", "content",
            "GroupFilled(RuleGroup) and NotExactlyOneFieldFilled(attachment_id, content)",
            "KontextAngegeben(RegelKontext) und NichtGenauEinFeldAngegeben(attachment_id, content)",
            "Provide exactly one of an attachment reference or inline content.",
            "Genau eine Quelle angeben: eine Anhang-Referenz oder Inline-Inhalt."),

    /** When inline content is present, its size is required. */
    SIZE_WITH_CONTENT("AttachmentSizeRequired", "ATTACHMENT_SIZE_REQUIRED", "size",
            "FieldFilled(content) and FieldNotFilled(size)",
            "FeldAngegeben(content) und FeldNichtAngegeben(size)",
            "When inline content is provided, its size is required.",
            "Bei Inline-Inhalt ist die Größe erforderlich."),

    /** An internal filename is always required. */
    INTERNAL_FILENAME("AttachmentInternalFilenameRequired", "ATTACHMENT_INTERNAL_FILENAME_REQUIRED", "internal_filename",
            "GroupFilled(RuleGroup) and FieldNotFilled(internal_filename)",
            "KontextAngegeben(RegelKontext) und FeldNichtAngegeben(internal_filename)",
            "An internal filename is required.",
            "Ein interner Dateiname ist erforderlich."),

    /** A MIME type is always required. */
    MIME_TYPE("AttachmentMimeTypeRequired", "ATTACHMENT_MIME_TYPE_REQUIRED", "mime_type",
            "GroupFilled(RuleGroup) and FieldNotFilled(mime_type)",
            "KontextAngegeben(RegelKontext) und FeldNichtAngegeben(mime_type)",
            "A MIME type is required.",
            "Ein MIME-Typ ist erforderlich.");

    final String ruleName;
    final String code;
    final String errorField;
    private final String conditionEn;
    private final String conditionDe;
    private final String messageEn;
    private final String messageDe;

    AttachmentRule(String ruleName, String code, String errorField, String conditionEn, String conditionDe,
            String messageEn, String messageDe) {
        this.ruleName = ruleName;
        this.code = code;
        this.errorField = errorField;
        this.conditionEn = conditionEn;
        this.conditionDe = conditionDe;
        this.messageEn = messageEn;
        this.messageDe = messageDe;
    }

    /** The kernel-mandated {@code errorCondition} string for this rule, in the model's condition language. */
    String condition(boolean german) {
        return german ? conditionDe : conditionEn;
    }

    /** The default error message for this rule, in the given language. */
    String defaultMessage(boolean german) {
        return german ? messageDe : messageEn;
    }
}
