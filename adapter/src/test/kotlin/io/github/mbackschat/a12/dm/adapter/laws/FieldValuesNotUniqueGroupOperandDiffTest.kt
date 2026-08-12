package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — `FieldValuesNotUnique` over a
 * **GROUP scope** rather than a field list.
 *
 * **Why this exists, and why it is measured before anything is built.** [KF173] records that the kernel *admits*
 * a group operand here: arity treats the group slot as already-many (a group expanding to a single field is still
 * admitted) while the category gate reads the expansion. rulekit cannot author the shape — `fieldValuesNotUnique`
 * takes a `FieldOperand`, which permits `FieldRef`/`FieldListRef` but not `GroupListRef` — so
 * [DEV-API-GAPS DG33] proposes widening it. That widening is only safe if the interpreter *agrees* with the
 * kernel on the shape: admitting an authoring form the evaluator answers **wrongly** is worse than the current
 * erasure, because a wrong answer is silent where a missing factory is not.
 *
 * The prior is concrete rather than hypothetical. [GroupStarValueAggregateDiffTest] exists because the
 * interpreter once read cells *at* the group path for `Sum(Lines*)` — no field is declared there — and computed
 * the empty-selection `0` against the kernel's `617`. Its note says the aggregate siblings and a validation-side
 * rule ride the same `starOperandVals` chokepoint, so whether this operator shares the fixed path is exactly the
 * open question, and it is settled here by measurement rather than by reading the chokepoint.
 *
 * Conditions are **string literals** here, which is the one legitimate reason to hand-write one in this estate:
 * the shape cannot be reached through the typed surface that DG33 exists to widen. Each is paired with the
 * equivalent explicit field list, so a disagreement cannot be read as a defect in the fixture.
 */
class FieldValuesNotUniqueGroupOperandDiffTest {

    private companion object {
        const val REFERENCE = "/Shipment/Reference"
        const val ORIGIN = "/Shipment/Origin"
        const val CITY = "/Shipment/Origin/City"
        const val DEPOT = "/Shipment/Origin/Depot"
        const val LEGS = "/Shipment/Legs"
        const val FIRST = "/Shipment/Legs/FirstScan"
        const val SECOND = "/Shipment/Legs/SecondScan"
        const val CODE = "DUP"
    }

    /**
     * A parcel-shipment model with two **homogeneous** groups, which the measurement requires: `Origin` expands
     * to two Strings and `Legs` to two same-scale Numbers. A mixed group cannot carry this test — its expansion
     * offends the category gate, so a disagreement would not distinguish "the group operand is mishandled" from
     * "this group's contents are refused".
     */
    private val base = DocumentModelDraft.named("shipment").locale("en_US")
        .rootGroup("Shipment") { g ->
            g.stringField("Reference") { }
            g.group("Origin") { o ->
                o.stringField("City") { }
                o.stringField("Depot") { }
            }
            g.group("Legs") { l ->
                l.repeatable(10)
                    .numberField("FirstScan") { it.fractionDigits(0, 0) }
                    .numberField("SecondScan") { it.fractionDigits(0, 0) }
            }
        }.build().toJson()

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both {
            kDoc.group(path, *reps); iDoc.group(path, *reps); return this
        }

        fun field(path: String, v: String, vararg reps: Int): Both {
            kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this
        }
    }

    /** Tri-check whether [condition] fires over the instance [build] creates. */
    private fun agreeFires(condition: String, expected: Boolean, msg: String, build: (Both) -> Unit) {
        val kModel = RuntimeLaws.withRule(base, "/Shipment", "R", "../Reference", CODE, condition)
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(build)
        val groovy = RuntimeLaws.messages(kModel, dynamic.kDoc).any { it.code() == CODE }

        // The static arm needs its own freshly built instance; the engines consume a document destructively.
        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(build).kDoc
        val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
            .messagesModern(staticDoc, null, java.util.Locale.US).any { it.code() == CODE }

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc).any { it.code == CODE }

        assertEquals(expected, groovy, "$msg — the kernel groovy-dynamic verdict")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "$msg — kernel vs INTERPRETER (the DG33 gate)")
    }

    private fun guarded(list: String) = "FieldFilled(/Shipment/Reference) And $list"

    // ── a NON-repeatable group: its two Strings are the compared values ────────────────────────────────

    @Test
    fun aNonRepeatableGroupScopeFiresOnADuplicateAmongItsExpandedFields() =
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Origin)"), expected = true,
            msg = "Origin expands to City+Depot, both 'Hamburg' → a duplicate") { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .field(CITY, "Hamburg", 1, 1, 1).field(DEPOT, "Hamburg", 1, 1, 1)
        }

    @Test
    fun theSameGroupScopeDoesNotFireWhenItsExpandedFieldsDiffer() =
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Origin)"), expected = false,
            msg = "City 'Hamburg' vs Depot 'Bremen' → distinct") { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .field(CITY, "Hamburg", 1, 1, 1).field(DEPOT, "Bremen", 1, 1, 1)
        }

    /** The paired control: the same two cells named as an explicit FIELD list must reach the same verdict. */
    @Test
    fun theExplicitFieldListReachesTheSameVerdictAsTheGroupScope() {
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Origin/City, /Shipment/Origin/Depot)"),
            expected = true, msg = "explicit list, both 'Hamburg' → a duplicate") { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .field(CITY, "Hamburg", 1, 1, 1).field(DEPOT, "Hamburg", 1, 1, 1)
        }
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Origin/City, /Shipment/Origin/Depot)"),
            expected = false, msg = "explicit list, distinct → no duplicate") { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .field(CITY, "Hamburg", 1, 1, 1).field(DEPOT, "Bremen", 1, 1, 1)
        }
    }

    /** An empty group expands to empty cells, and empties are skipped rather than duplicating each other. */
    @Test
    fun anEmptyGroupScopeDoesNotFire() =
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Origin)"), expected = false,
            msg = "both expanded cells empty → two empties are not a duplicate") { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
        }

    // ── a STARRED repeatable group: the expansion crosses rows as well as fields ───────────────────────

    @Test
    fun aStarredGroupScopeFiresOnADuplicateAcrossItsRowsAndFields() =
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Legs*)"), expected = true,
            msg = "Legs* expands to FirstScan+SecondScan over both rows; 7 repeats → a duplicate") { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .group(LEGS, 1, 1).field(FIRST, "7", 1, 1, 1).field(SECOND, "3", 1, 1, 1)
                .group(LEGS, 1, 2).field(FIRST, "5", 1, 2, 1).field(SECOND, "7", 1, 2, 1)
        }

    @Test
    fun theSameStarredGroupScopeDoesNotFireWhenEveryExpandedCellIsDistinct() =
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Legs*)"), expected = false,
            msg = "four distinct scan values across two rows → no duplicate") { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .group(LEGS, 1, 1).field(FIRST, "7", 1, 1, 1).field(SECOND, "3", 1, 1, 1)
                .group(LEGS, 1, 2).field(FIRST, "5", 1, 2, 1).field(SECOND, "9", 1, 2, 1)
        }

    /**
     * THE DISCRIMINATOR for the expansion's extent. The duplicate sits in **one row across two fields**, so an
     * implementation that expanded per row and compared only within a field (or only across rows of one field)
     * misses it while the kernel does not.
     */
    @Test
    fun aDuplicateWithinOneRowAcrossTwoFieldsIsStillFound() =
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Legs*)"), expected = true,
            msg = "row 1 holds 4 and 4 in its two scan fields → a duplicate inside one row") { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .group(LEGS, 1, 1).field(FIRST, "4", 1, 1, 1).field(SECOND, "4", 1, 1, 1)
                .group(LEGS, 1, 2).field(FIRST, "5", 1, 2, 1).field(SECOND, "9", 1, 2, 1)
        }
}
