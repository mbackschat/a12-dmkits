package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When `+` refuses an Enumeration for its display labels (a12-kernel-lean SPEC-2026-10-08-04). [KF396] measured that
 * a labelled Enumeration beside a text draws `MVK_NO_STRING_OR_NUMBER_FOR_ADD` where a labelless one concatenates.
 * These rows separate the two things that rule depends on, each on an Enumeration of the same tokens:
 * - the LABELS: only an effective remapping refuses, a label differing from its stored value in some locale
 *   (`IEnumerationType.hasDisplayValues`). Labels that repeat their values are no labels here.
 * - the OPERAND SHAPE: only an immediate field read is asked (`CheckOperationImpl.checkWithoutDisplayValues` returns
 *   for anything but a `SINGLE_OP` field value), so a braced Enumeration concatenates and a category access is exempt.
 *   A braced failing addition still fails inside the brace, and the brace keeps the Enumeration's string-likeness,
 *   so it does not become a Number partner.
 *
 * Own domain (an e-commerce order's sales channel).
 */
class CheckerEnumerationAdditionAgreementTest {

    private val order = DocumentModelDraft.named("order-channel-addition").locale("en_US").locale("de_DE")
        .rootGroup("Order") { o -> o
            .stringField("Reference") { }
            .stringField("Notes") { }
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
    fun onlyAnImmediateEffectivelyRemappedEnumerationIsRefused() {
        val predicted = linkedMapOf(
            "a labelless Enumeration" to ("[Plain] + [Notes] == \"x\"" to ADMITTED),
            "an identity-labelled Enumeration, left" to ("[Identity] + [Notes] == \"x\"" to ADMITTED),
            "an identity-labelled Enumeration, right" to ("[Notes] + [Identity] == \"x\"" to ADMITTED),
            "a remapped Enumeration, left" to ("[Remapped] + [Notes] == \"x\"" to MIXED_ADD),
            "a remapped Enumeration, right" to ("[Notes] + [Remapped] == \"x\"" to MIXED_ADD),
            "one remapped label in one locale" to ("[OneRemapped] + [Notes] == \"x\"" to MIXED_ADD),
            "a braced remapped Enumeration, left" to ("{[Remapped]} + [Notes] == \"x\"" to ADMITTED),
            "a braced remapped Enumeration, right" to ("[Notes] + {[Remapped]} == \"x\"" to ADMITTED),
            "a twice-braced remapped Enumeration" to ("{{[Remapped]}} + [Notes] == \"x\"" to ADMITTED),
            "a brace around the failing addition" to ("{[Remapped] + [Notes]} == \"x\"" to MIXED_ADD),
            "a braced one beside a direct one" to ("{[Remapped]} + [Remapped] == \"x\"" to MIXED_ADD),
            "a direct one beside a braced one" to ("[Remapped] + {[Remapped]} == \"x\"" to MIXED_ADD),
            "a category access" to ("[Remapped -> Kind] + [Notes] == \"x\"" to ADMITTED),
            "a braced remapped Enumeration beside a Number" to ("{[Remapped]} + [Quantity] == \"x\"" to MIXED_ADD),
            "two braced Enumerations alone" to ("{[Remapped]} + {[Identity]} == \"x\"" to ADMITTED),
            "two braced Enumerations before a text" to ("{[Remapped]} + {[Identity]} + [Notes] == \"x\"" to ADMITTED),
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
            if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.toSet()
        }
        val ours = check(ModelSource(validator.inject(order, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }

    private companion object {
        val ADMITTED = emptySet<String>()
        val MIXED_ADD = setOf("MVK_NO_STRING_OR_NUMBER_FOR_ADD")

        /** The rows where the checker answers otherwise, with what it answers. */
        val OURS: Map<String, Set<String>> = mapOf()
    }
}
