package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator

/** DG102's bounded semantic-index pattern and declared-type population. */
internal object IndexKeyPatternPopulation {
    private val host = DocumentModelDraft.named("policy-index-patterns").locale("en_US")
        .typeDef("NamedCode") { it.string { field -> field.pattern(".{2}", "two characters") } }
        .rootGroup("Policy") { policy -> policy
            .stringField("Reference") { }
            .group("Dots") { group -> keyed(group) { it.stringField("K") { field -> field.pattern(".{2}", "two characters") } } }
            .group("Digits") { group -> keyed(group) { it.stringField("K") { field -> field.pattern("\\d{2}", "two digits") } } }
            .group("Choice") { group -> keyed(group) { it.stringField("K") { field -> field.pattern("(AB|CD)", "chosen code") } } }
            .group("Flags") { group -> keyed(group) { it.stringField("K") { field -> field.pattern("(?i:ab)", "case-insensitive code") } } }
            .group("UnicodeFlags") { group -> keyed(group) { it.stringField("K") { field -> field.pattern("(?iu:é)", "Unicode case-insensitive code") } } }
            .group("AsciiFlags") { group -> keyed(group) { it.stringField("K") { field -> field.pattern("(?i:é)", "ASCII case-insensitive code") } } }
            .group("FinalLineAnchor") { group -> keyed(group) { it.stringField("K") { field -> field.pattern("a$\n", "line anchor with final line feed") } } }
            .group("LineAnchor") { group -> keyed(group) { it.stringField("K") { field -> field.pattern("a$", "line anchor") } } }
            .group("Spaces") { group -> keyed(group) { it.stringField("K") { field -> field.pattern("\\h{2}", "two spaces") } } }
            .group("NamedCharacter") { group -> keyed(group) { it.stringField("K") { field -> field.pattern("\\N{LATIN SMALL LETTER A}", "letter a") } } }
            .group("ClassNonSpace") { group -> keyed(group) { it.stringField("K") { field -> field.pattern("[\\S]", "one non-space") } } }
            .group("ClassHex") { group -> keyed(group) { it.stringField("K") { field -> field.pattern("[\\x{A0}]", "nonbreaking space") } } }
            .group("Named") { group -> keyed(group) { it.typedefField("K", "NamedCode") } }
            .group("Custom") { group -> keyed(group) { it.customField("K") { field -> field.type("PolicyCode").minLength(2) } } }
            .group("CustomMax") { group -> keyed(group) { it.customField("K") { field -> field.type("PolicyCode").maxLength(1) } } } }
        .build().toJson()

    private val validator = RuleValidator()
    private val baseRule = BoundModel.ofJson(host).let { model ->
        val reference = model.str("/Policy/Reference")
        model.rule(model.group("/Policy")).name("IndexPatternProbe").errorField(reference)
            .`when`(reference.filled()).code("INDEX_PATTERN_PROBE")
            .message(Dsl.en("Check policy code")).build().draft()
    }

    private fun keyed(
        group: DocumentModelDraft.GroupBuilder,
        key: (DocumentModelDraft.GroupBuilder) -> DocumentModelDraft.GroupBuilder,
    ): DocumentModelDraft.GroupBuilder = key(group.repeatable(5).indexField("K"))
        .numberField("Amount") { it.fractionDigits(2) }

    fun rows(): List<ConditionCase> = listOf(
        ConditionCase("admitted-control", validator.inject(host, baseRule), null),
        row("dot-match", "Dots", "AB", accepted = true),
        row("dot-miss", "Dots", "A", accepted = false),
        row("digit-match", "Digits", "12", accepted = true),
        row("digit-miss", "Digits", "1A", accepted = false),
        row("choice-match", "Choice", "CD", accepted = true),
        row("choice-miss", "Choice", "CE", accepted = false),
        row("flag-match", "Flags", "AB", accepted = true),
        row("flag-miss", "Flags", "AC", accepted = false),
        row("unicode-flag-match", "UnicodeFlags", "É", accepted = true),
        row("ascii-flag-miss", "AsciiFlags", "É", accepted = false),
        row("final-line-anchor-match", "FinalLineAnchor", "a\n", accepted = true),
        row("line-anchor-miss", "LineAnchor", "a\n", accepted = false),
        row("space-match", "Spaces", "  ", accepted = true),
        row("space-miss", "Spaces", "A ", accepted = false),
        row("named-character-match", "NamedCharacter", "a", accepted = true),
        row("named-character-miss", "NamedCharacter", "b", accepted = false),
        row("class-nonspace-match", "ClassNonSpace", "\u00A0", accepted = true),
        row("class-nonspace-miss", "ClassNonSpace", " ", accepted = false),
        row("class-hex-match", "ClassHex", "\u00A0", accepted = true),
        row("class-hex-miss", "ClassHex", "A", accepted = false),
        row("typedef-match", "Named", "AB", accepted = true),
        row("typedef-miss", "Named", "A", accepted = false),
        row("custom-match", "Custom", "AB", accepted = true),
        row("custom-miss", "Custom", "A", accepted = false),
        row("custom-max-match", "CustomMax", "A", accepted = true),
        row("custom-max-miss", "CustomMax", "AB", accepted = false),
    )

    private fun row(id: String, group: String, constant: String, accepted: Boolean): ConditionCase {
        val condition = baseRule.errorCondition() + " And Sum($group/Amount For \"$constant\") > 0"
        return ConditionCase(id, validator.inject(host, baseRule.withErrorCondition(condition)),
            if (accepted) "MVK_PARAMSIZE_INVALIDN" else "MVK_INDEX_VALUE_INVALID")
    }
}
