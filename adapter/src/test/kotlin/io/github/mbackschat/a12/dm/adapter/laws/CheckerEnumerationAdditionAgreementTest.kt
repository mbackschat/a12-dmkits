package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When `+` refuses an Enumeration (a12-kernel-lean SPEC-2026-10-08-04). Kernel 30.8.1 refused an immediate read
 * whose labels remap its values beside a text ([KF396]); kernel 31.1.1 deleted that gate ([KF655]), so every row
 * that separated the LABELS and the OPERAND SHAPE it depended on is now admitted, and they stay as the lock that no
 * remnant of it survives. A braced Enumeration still keeps its string-likeness, so beside a Number it is refused.
 *
 * Own domain (an e-commerce order's sales channel).
 */
class CheckerEnumerationAdditionAgreementTest {

    private val order = DocumentModelDraft.named("order-channel-addition").locale("en_US").locale("de_DE")
        .rootGroup("Order") { o -> o
            .stringField("Reference") { }
            .stringField("Notes") { }
            .stringField("Key") { }
            .group("Lines") { g -> g.repeatable(5).indexField("Sku")
                .stringField("Sku") { }
                .enumField("Channel") { e -> e
                    .value("WEB") { l -> l.label("en_US", "Web shop").label("de_DE", "Webshop") }
                    .value("STORE") { l -> l.label("en_US", "In store").label("de_DE", "Filiale") } } }
            .numberField("Quantity") { n -> n.fractionDigits(0) }
            .enumField("Plain") { e -> e.value("WEB").value("STORE") }
            .enumField("Identity") { e -> e
                .value("WEB") { l -> l.label("en_US", "WEB").label("de_DE", "WEB") }
                .value("STORE") { l -> l.label("en_US", "STORE").label("de_DE", "STORE") } }
            .enumField("Remapped") { e -> e
                .value("WEB") { l -> l.label("en_US", "Web shop").label("de_DE", "Webshop") }
                .value("STORE") { l -> l.label("en_US", "In store").label("de_DE", "Filiale") }
                .category("Kind", "ONLINE", "OFFLINE") }
            // One value and one locale remap; every other label repeats its stored value.
            .enumField("OneRemapped") { e -> e
                .value("WEB") { l -> l.label("en_US", "WEB").label("de_DE", "WEB") }
                .value("STORE") { l -> l.label("en_US", "STORE").label("de_DE", "Filiale") } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun anEnumerationJoinsATextWhateverItsLabelsAndShape() {
        val predicted = linkedMapOf(
            "a labelless Enumeration" to ("[Plain] + [Notes] == \"x\"" to ADMITTED),
            "an identity-labelled Enumeration, left" to ("[Identity] + [Notes] == \"x\"" to ADMITTED),
            "an identity-labelled Enumeration, right" to ("[Notes] + [Identity] == \"x\"" to ADMITTED),
            "a remapped Enumeration, left" to ("[Remapped] + [Notes] == \"x\"" to ADMITTED),
            "a remapped Enumeration, right" to ("[Notes] + [Remapped] == \"x\"" to ADMITTED),
            "one remapped label in one locale" to ("[OneRemapped] + [Notes] == \"x\"" to ADMITTED),
            "a braced remapped Enumeration, left" to ("{[Remapped]} + [Notes] == \"x\"" to ADMITTED),
            "a braced remapped Enumeration, right" to ("[Notes] + {[Remapped]} == \"x\"" to ADMITTED),
            "a twice-braced remapped Enumeration" to ("{{[Remapped]}} + [Notes] == \"x\"" to ADMITTED),
            "a brace around the addition" to ("{[Remapped] + [Notes]} == \"x\"" to ADMITTED),
            "a braced one beside a direct one" to ("{[Remapped]} + [Remapped] == \"x\"" to ADMITTED),
            "a direct one beside a braced one" to ("[Remapped] + {[Remapped]} == \"x\"" to ADMITTED),
            "a category access" to ("[Remapped -> Kind] + [Notes] == \"x\"" to ADMITTED),
            "a braced remapped Enumeration beside a Number" to ("{[Remapped]} + [Quantity] == \"x\"" to MIXED_ADD),
            "two braced Enumerations alone" to ("{[Remapped]} + {[Identity]} == \"x\"" to ADMITTED),
            "two braced Enumerations before a text" to ("{[Remapped]} + {[Identity]} + [Notes] == \"x\"" to ADMITTED),
            "a key-selected remapped cell" to ("[Lines/Channel For Key] + [Notes] == \"x\"" to ADMITTED),
            "a braced key-selected remapped cell" to ("{[Lines/Channel For Key]} + [Notes] == \"x\"" to ADMITTED),
        )
        val wrong = predicted.mapNotNull { (row, probe) ->
            val (condition, a12) = probe
            val observed = compare(condition)
            val expected = a12 to (OURS[row] ?: a12)
            if (observed == expected) null else "$row: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    private fun compare(probe: String): Pair<Set<String>, Set<String>> {
        val draft = DraftRule("/Order", "ProbeRule", "../Reference", "FieldFilled(Reference) And $probe", "PROBE",
            "ERROR")
        val a12 = validator.check(order, draft).let { result ->
            if (result.ok()) emptySet() else result.diagnostics().map { it.codeOrSummary() }.toSet()
        }
        val ours = rejectingCodes(validator.inject(order, draft)).toSet()
        return a12 to ours
    }

    private companion object {
        val ADMITTED = emptySet<String>()
        val MIXED_ADD = setOf("MVK_NO_STRING_OR_NUMBER_FOR_ADD")

        /** The rows where the checker answers otherwise, with what it answers. */
        val OURS: Map<String, Set<String>> = mapOf()
    }
}
