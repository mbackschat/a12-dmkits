package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import java.util.Locale
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

        // the nesting fixture
        const val CARRIER_NAME = "/Shipment/Carrier/Name"
        const val HANDOFFS = "/Shipment/Carrier/Handoffs"
        const val SITE = "/Shipment/Carrier/Handoffs/Site"

        // two repeatable levels below the operand
        const val DEPOT_NAME = "/Shipment/Depot/Name"
        const val HANDOVERS = "/Shipment/Depot/Handovers"
        const val SCANS = "/Shipment/Depot/Handovers/Scans"
        const val SCAN_CODE = "/Shipment/Depot/Handovers/Scans/Code"

        // a repeatable outer group with a nested repeatable subgroup, for the STARRED arm
        const val CONSIGNMENTS = "/Shipment/Consignments"
        const val CONSIGNMENT_REF = "/Shipment/Consignments/Ref"
        const val MARKS = "/Shipment/Consignments/Marks"
        const val MARK = "/Shipment/Consignments/Marks/Mark"

        /**
         * The `referenced` set a group operand contributes, measured 2026-08-14. The expansion names each
         * declared field with a **wildcard** coordinate at every repeatable level (`Consignments[0]`,
         * `Handoffs[0]`) and a concrete one at every non-repeatable level (`Carrier[1]`), while the message's
         * own anchor is concrete throughout — the two are different addresses of the same firing. The guard's
         * `/Shipment[1]/Reference` rides along because `FieldFilled` references it.
         */
        const val CONSIGNMENT_REFS =
            "[/Shipment[1]/Consignments[0]/Marks[0]/Mark, /Shipment[1]/Consignments[0]/Ref, " +
                "/Shipment[1]/Reference]"
        const val CARRIER_REFS =
            "[/Shipment[1]/Carrier[1]/Handoffs[0]/Site, /Shipment[1]/Carrier[1]/Name, /Shipment[1]/Reference]"
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

    /**
     * A second fixture for the **nesting** question: `Carrier` holds ONE direct String and a nested *repeatable*
     * `Handoffs` carrying one String. Exactly one direct field is the point — a single cell cannot duplicate
     * itself, so any firing here can only mean the expansion crossed into the nested rows. Homogeneous
     * (all Strings) so the category gate cannot be the thing that answers.
     */
    private val nested = DocumentModelDraft.named("carrier").locale("en_US")
        .rootGroup("Shipment") { g ->
            g.stringField("Reference") { }
            g.group("Carrier") { c ->
                c.stringField("Name") { }
                c.group("Handoffs") { h ->
                    h.repeatable(10).stringField("Site") { }
                }
            }
        }.build().toJson()

    /**
     * **TWO levels of repeatable subgroup** below the operand: `Depot` (non-repeatable) → `Handovers` (repeatable)
     * → `Scans` (repeatable). A separate question from [nested] rather than a corollary: one level iterating fully
     * does not establish that a second does, and the binding rule is not derivable by reading — three successive
     * readings of `Document.groupScopePrefix` predicted a pin here and every one was falsified (IF287).
     */
    private val deepNested = DocumentModelDraft.named("depot").locale("en_US")
        .rootGroup("Shipment") { g ->
            g.stringField("Reference") { }
            g.group("Depot") { d ->
                d.stringField("Name") { }
                d.group("Handovers") { h ->
                    h.repeatable(10).group("Scans") { s -> s.repeatable(10).stringField("Code") { } }
                }
            }
        }.build().toJson()

    /**
     * A **repeatable** outer group carrying a nested repeatable subgroup, for the STARRED arm — [nested] uses a
     * non-repeatable outer group, so it exercises the converted star rather than an authored one.
     */
    private val starredNested = DocumentModelDraft.named("consignment").locale("en_US")
        .rootGroup("Shipment") { g ->
            g.stringField("Reference") { }
            g.group("Consignments") { c ->
                c.repeatable(10).stringField("Ref") { }
                c.group("Marks") { m -> m.repeatable(10).stringField("Mark") { } }
            }
        }.build().toJson()

    /**
     * [deepNested] plus a direct `Marker` field on the **middle** repeatable level, which is what lets a rule
     * ANCHOR ITERATION there: iteration is reference-driven, so a guard naming `Handovers/Marker` makes
     * `Handovers` the iterating group while the operand still names its ancestor `/Shipment/Depot`. Without such a
     * field every condition over this shape binds `Once`, and the declared rule group drives nothing — which is
     * why the existing deeper-rule-group row does not reach this question despite looking like it does.
     */
    private val deepNestedMarked = DocumentModelDraft.named("markeddepot").locale("en_US")
        .rootGroup("Shipment") { g ->
            g.stringField("Reference") { }
            g.group("Depot") { d ->
                d.stringField("Name") { }
                d.group("Handovers") { h ->
                    h.repeatable(10).stringField("Marker") { }
                    h.group("Scans") { s -> s.repeatable(10).stringField("Code") { } }
                }
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
    private fun agreeFires(
        condition: String,
        expected: Boolean,
        msg: String,
        model: String = base,
        ruleGroup: String = "/Shipment",
        errorPath: String = "../Reference",
        build: (Both) -> Unit,
    ) {
        val kModel = RuntimeLaws.withRule(model, ruleGroup, "R", errorPath, CODE, condition)
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(build)
        val groovy = RuntimeLaws.messages(kModel, dynamic.kDoc).any { it.code() == CODE }

        // The static arm needs its own freshly built instance; the engines consume a document destructively.
        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(build).kDoc
        val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
            .messagesModern(staticDoc, null, Locale.US).any { it.code() == CODE }

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

    // ── a group holding a NESTED REPEATABLE subgroup: how far does the expansion reach? ─────────────────

    /**
     * The nesting extent, which [§7.3 of the 2026-08-13 handover] listed as unmeasured. `Carrier` declares one
     * direct String, so this cell cannot duplicate itself: firing is possible only if the expansion descends into
     * the nested repeatable `Handoffs` rows. A **non-starred** operand naming a group that *contains* a repeatable
     * one is the interesting case precisely because the star is not written anywhere.
     */
    @Test
    fun aGroupScopeDescendsIntoANestedRepeatableSubgroup() =
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Carrier)"), expected = true,
            msg = "Carrier's one direct field cannot duplicate itself, so firing proves the nested rows are in " +
                "the set — with no star written anywhere",
            model = nested) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .field(CARRIER_NAME, "DHL", 1, 1, 1)
                .group(HANDOFFS, 1, 1, 1).field(SITE, "DHL", 1, 1, 1, 1)
        }

    /**
     * The same shape with the duplicate lying **entirely inside** the nested subgroup, across two of its rows —
     * so the compared set is not merely "the direct fields plus one nested cell": the nested rows are compared
     * against each other too.
     */
    @Test
    fun aDuplicateInsideTheNestedRepeatableSubgroupIsAlsoFound() =
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Carrier)"), expected = true,
            msg = "two Handoffs rows both 'UPS' — inside the nested group, still one compared set",
            model = nested) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .field(CARRIER_NAME, "DHL", 1, 1, 1)
                .group(HANDOFFS, 1, 1, 1).field(SITE, "UPS", 1, 1, 1, 1)
                .group(HANDOFFS, 1, 1, 2).field(SITE, "UPS", 1, 1, 2, 1)
        }

    /**
     * **The mechanism discriminator**, kept because it is what identified the defect these three rows locked.
     * The duplicate pairs the direct field with the nested subgroup's **second** row. Before the fix the
     * interpreter missed it, which falsifies "expands every nested row but compares them only against the direct
     * fields" (that reading finds this one) and leaves "reads the nested repetition at a single row-1 coordinate"
     * as the only survivor — the plain field ref the expansion used to build.
     */
    @Test
    fun aDuplicateBetweenTheDirectFieldAndTheNestedSubgroupsSecondRowIsFound() =
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Carrier)"), expected = true,
            msg = "Name 'MSC' repeats in Handoffs row 2, not row 1",
            model = nested) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .field(CARRIER_NAME, "MSC", 1, 1, 1)
                .group(HANDOFFS, 1, 1, 1).field(SITE, "UPS", 1, 1, 1, 1)
                .group(HANDOFFS, 1, 1, 2).field(SITE, "MSC", 1, 1, 2, 1)
        }

    // ── TWO repeatable levels below the operand ─────────────────────────────────────────────────────────

    /**
     * **The second level must iterate too.** The duplicate pairs the direct field with `Handovers[2]/Scans[2]`, so
     * it is reachable only if BOTH repeatable levels advance past their first row. An implementation that binds the
     * segments above the innermost star — the natural single-level reading — pins `Handovers` at the ambient row and
     * misses this while still passing every one-level row.
     */
    @Test
    fun aGroupScopeIteratesEveryLevelOfTwoNestedRepeatableSubgroups() =
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Depot)"), expected = true,
            msg = "Name repeats in Handovers[2]/Scans[2] — two levels down, neither at row 1",
            model = deepNested) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .field(DEPOT_NAME, "ZZ9", 1, 1, 1)
                .group(HANDOVERS, 1, 1, 1).group(SCANS, 1, 1, 1, 1).field(SCAN_CODE, "AA1", 1, 1, 1, 1, 1)
                .group(HANDOVERS, 1, 1, 2).group(SCANS, 1, 1, 2, 1).field(SCAN_CODE, "BB2", 1, 1, 2, 1, 1)
                .group(SCANS, 1, 1, 2, 2).field(SCAN_CODE, "ZZ9", 1, 1, 2, 2, 1)
        }

    /** The same fixture with the duplicate spanning two different `Handovers` rows' `Scans`, and no direct field. */
    @Test
    fun aDuplicateAcrossTwoBranchesOfTheDeepestRepetitionIsFound() =
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Depot)"), expected = true,
            msg = "Handovers[1]/Scans[1] and Handovers[2]/Scans[2] both 'CC3'",
            model = deepNested) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .field(DEPOT_NAME, "ZZ9", 1, 1, 1)
                .group(HANDOVERS, 1, 1, 1).group(SCANS, 1, 1, 1, 1).field(SCAN_CODE, "CC3", 1, 1, 1, 1, 1)
                .group(HANDOVERS, 1, 1, 2).group(SCANS, 1, 1, 2, 1).field(SCAN_CODE, "BB2", 1, 1, 2, 1, 1)
                .group(SCANS, 1, 1, 2, 2).field(SCAN_CODE, "CC3", 1, 1, 2, 2, 1)
        }

    /** The negative control on the deep fixture: every cell distinct across both levels must stay silent. */
    @Test
    fun theDeepGroupScopeStaysSilentWhenEveryCellAcrossBothLevelsIsDistinct() =
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Depot)"), expected = false,
            msg = "four distinct codes across two Handovers rows plus a distinct Name",
            model = deepNested) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .field(DEPOT_NAME, "ZZ9", 1, 1, 1)
                .group(HANDOVERS, 1, 1, 1).group(SCANS, 1, 1, 1, 1).field(SCAN_CODE, "AA1", 1, 1, 1, 1, 1)
                .group(HANDOVERS, 1, 1, 2).group(SCANS, 1, 1, 2, 1).field(SCAN_CODE, "BB2", 1, 1, 2, 1, 1)
                .group(SCANS, 1, 1, 2, 2).field(SCAN_CODE, "CC3", 1, 1, 2, 2, 1)
        }

    // ── the operand read from a DEEPER iterating rule group, where the binding cap can bite ──────────────

    /**
     * **The placement every other row here leaves untested, and probably the commonest one.** All the cases above
     * author their rule on the root group, so the shared-ancestry binding is one level and nothing below it is ever
     * pinned. A rule authored on a *repeatable* group — ordinary per-row validation — makes that ancestry as deep as
     * the rule group, and the operand names an ANCESTOR of it (`/Shipment/Depot` from a rule on
     * `/Shipment/Depot/Handovers`).
     *
     * The duplicate spans **two different `Handovers` rows**, with a distinct `Name`, which is what makes this
     * two-way: if each rule instance sees only its own row's `Scans` the pair is never in one compared set and
     * nothing fires; if the operand's scope spans the rows regardless of where the rule sits, every instance sees
     * both and it fires. `theSameDeeperRuleStillFiresWithinOneRow` is the control proving the rule evaluates at all.
     *
     * **Measured: it fires** — the compared set is bounded by the OPERAND, not by the rule's own row. All three arms
     * agree. The prediction written here first was the opposite, from reading the binding code; see IF287 on why a
     * source reading of that function is not an oracle.
     */
    @Test
    fun aGroupOperandReadFromADeeperRuleGroupStillSpansTheWholeOperand() =
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Depot)"), expected = true,
            msg = "the duplicate spans Handovers[1] and Handovers[2]; the rule iterates Handovers",
            model = deepNested, ruleGroup = HANDOVERS, errorPath = "../../../Reference") { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .field(DEPOT_NAME, "N0", 1, 1, 1)
                .group(HANDOVERS, 1, 1, 1).group(SCANS, 1, 1, 1, 1).field(SCAN_CODE, "XX1", 1, 1, 1, 1, 1)
                .group(HANDOVERS, 1, 1, 2).group(SCANS, 1, 1, 2, 1).field(SCAN_CODE, "XX1", 1, 1, 2, 1, 1)
        }

    /** The control: with the duplicate INSIDE one `Handovers` row, a per-row scope and a spanning one both fire. */
    @Test
    fun theSameDeeperRuleStillFiresWithinOneRow() =
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Depot)"), expected = true,
            msg = "Name repeats in Handovers[2]'s own Scans, so scope cannot be the reason it fires",
            model = deepNested, ruleGroup = HANDOVERS, errorPath = "../../../Reference") { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .field(DEPOT_NAME, "N0", 1, 1, 1)
                .group(HANDOVERS, 1, 1, 1).group(SCANS, 1, 1, 1, 1).field(SCAN_CODE, "XX1", 1, 1, 1, 1, 1)
                .group(HANDOVERS, 1, 1, 2).group(SCANS, 1, 1, 2, 1).field(SCAN_CODE, "N0", 1, 1, 2, 1, 1)
        }

    // ── an AUTHORED star on a repeatable group that itself holds a repeatable subgroup ───────────────────

    /**
     * The starred arm's nesting row. [nested] converts a non-starred operand into a star, so it says nothing about
     * an **authored** star whose group carries a repeatable subgroup: there the operand's own `bindDepth` rides
     * each expanded field, which is a different value from the converted arm's.
     */
    @Test
    fun aStarredGroupScopeReachesItsNestedRepeatableSubgroupsLaterRows() =
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Consignments*)"), expected = true,
            msg = "Consignments[1]/Ref repeats in that row's Marks[2]",
            model = starredNested) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .group(CONSIGNMENTS, 1, 1).field(CONSIGNMENT_REF, "K7", 1, 1, 1)
                .group(MARKS, 1, 1, 1).field(MARK, "M1", 1, 1, 1, 1)
                .group(MARKS, 1, 1, 2).field(MARK, "K7", 1, 1, 2, 1)
        }

    /** The duplicate spanning two OUTER rows' nested subgroups — both levels of the authored star must iterate. */
    @Test
    fun aStarredGroupScopeFindsADuplicateAcrossTwoOuterRowsNestedSubgroups() =
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Consignments*)"), expected = true,
            msg = "Consignments[1]/Marks[2] and Consignments[2]/Marks[1] both 'M9'",
            model = starredNested) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .group(CONSIGNMENTS, 1, 1).field(CONSIGNMENT_REF, "K7", 1, 1, 1)
                .group(MARKS, 1, 1, 1).field(MARK, "M1", 1, 1, 1, 1)
                .group(MARKS, 1, 1, 2).field(MARK, "M9", 1, 1, 2, 1)
                .group(CONSIGNMENTS, 1, 2).field(CONSIGNMENT_REF, "K8", 1, 2, 1)
                .group(MARKS, 1, 2, 1).field(MARK, "M9", 1, 2, 1, 1)
        }

    /** The negative control for the starred nesting rows. */
    @Test
    fun theStarredGroupScopeStaysSilentWhenEveryNestedCellIsDistinct() =
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Consignments*)"), expected = false,
            msg = "two outer rows, four distinct marks and two distinct refs",
            model = starredNested) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .group(CONSIGNMENTS, 1, 1).field(CONSIGNMENT_REF, "K7", 1, 1, 1)
                .group(MARKS, 1, 1, 1).field(MARK, "M1", 1, 1, 1, 1)
                .group(MARKS, 1, 1, 2).field(MARK, "M2", 1, 1, 2, 1)
                .group(CONSIGNMENTS, 1, 2).field(CONSIGNMENT_REF, "K8", 1, 2, 1)
                .group(MARKS, 1, 2, 1).field(MARK, "M3", 1, 2, 1, 1)
        }

    /**
     * The **positive control** for the rows above: the nested subgroup's own field, named directly with a star,
     * finds a duplicate the group scope may or may not. Without this row a `false` above could mean "the rule
     * never evaluated" rather than a statement about the expansion's extent.
     */
    @Test
    fun theNestedSubgroupsOwnStarredFieldDoesFindTheDuplicate() =
        agreeFires(guarded("FieldValuesNotUnique(/Shipment/Carrier/Handoffs*/Site)"), expected = true,
            msg = "naming the nested field with its own star finds the duplicate the group scope did not",
            model = nested) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .field(CARRIER_NAME, "DHL", 1, 1, 1)
                .group(HANDOFFS, 1, 1, 1).field(SITE, "UPS", 1, 1, 1, 1)
                .group(HANDOFFS, 1, 1, 2).field(SITE, "UPS", 1, 1, 2, 1)
        }

    // ── message MULTIPLICITY and ANCHOR, which "does it fire" cannot answer ───────────────────────────────

    /**
     * Every row above asks [agreeFires], which reads `any { code }` — a **boolean**. That cannot distinguish one
     * message from two, nor say where either attaches, so the whole family measured firing and left multiplicity
     * unmeasured. The lean project's 2026-08-14 disposition named the gap and its discriminator: a group operand
     * whose expansion holds duplicates **in two distinct rows** — one message or two, and at which address.
     *
     * The question is not answerable from §7.1's "compared as one value set". That sentence is about the compared
     * SET; a kernel could compare one set and still report per involved row. Both accounts predict `FIRES`, which
     * is exactly why the boolean rows could not separate them.
     */
    private fun agreeFireSet(
        condition: String,
        expected: List<String>,
        msg: String,
        model: String = base,
        ruleGroup: String = "/Shipment",
        errorPath: String = "../Reference",
        build: (Both) -> Unit,
    ) {
        val kModel = RuntimeLaws.withRule(model, ruleGroup, "R", errorPath, CODE, condition)
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(build)
        val groovy = RuntimeLaws.messages(kModel, dynamic.kDoc).filter { it.code() == CODE }
            .map { "${it.field()}|${it.type().name}|${it.referenced().sorted()}" }.sorted()

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(build).kDoc
        val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
            .messagesModern(staticDoc, null, Locale.US).filter { it.code() == CODE }
            .map { "${it.field()}|${it.type().name}|${it.referenced().sorted()}" }.sorted()

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc).filter { it.code == CODE }
            .map {
                "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" +
                    "|${it.referenced.sorted()}"
            }
            .sorted()

        assertEquals(expected.sorted(), groovy, "$msg — the kernel groovy-dynamic fire set")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "$msg — kernel vs INTERPRETER")
    }

    /** The named discriminator: the duplicate spans two OUTER rows, and the rule sits on the root group. */
    @Test
    fun aDuplicateSpanningTwoRowsReportsOnceFromARootPlacedRule() =
        agreeFireSet(
            guarded("FieldValuesNotUnique(/Shipment/Consignments*)"),
            expected = listOf("/Shipment[1]/Reference|VALUE_ERROR|$CONSIGNMENT_REFS"),
            msg = "Consignments[1]/Marks[2] and Consignments[2]/Marks[1] both 'M9', rule on /Shipment",
            model = starredNested,
        ) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .group(CONSIGNMENTS, 1, 1).field(CONSIGNMENT_REF, "K7", 1, 1, 1)
                .group(MARKS, 1, 1, 1).field(MARK, "M1", 1, 1, 1, 1)
                .group(MARKS, 1, 1, 2).field(MARK, "M9", 1, 1, 2, 1)
                .group(CONSIGNMENTS, 1, 2).field(CONSIGNMENT_REF, "K8", 1, 2, 1)
                .group(MARKS, 1, 2, 1).field(MARK, "M9", 1, 2, 1, 1)
        }

    /**
     * The placement variant, which is the half their spec answers for the starred FIELD form and not for this one:
     * the same duplicate, but the rule authored on the **repeatable** group the operand names, whose two rows both
     * evaluate. The error field still resolves ABOVE that group, so the two rows address the same cell.
     */
    @Test
    fun theSameDuplicateReportsOnceWhenTheRuleSitsOnTheRepeatableGroup() =
        agreeFireSet(
            guarded("FieldValuesNotUnique(/Shipment/Consignments*)"),
            expected = listOf("/Shipment[1]/Reference|VALUE_ERROR|$CONSIGNMENT_REFS"),
            msg = "the same duplicate, rule authored on /Shipment/Consignments",
            model = starredNested,
            ruleGroup = CONSIGNMENTS,
            errorPath = "../../Reference",
        ) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .group(CONSIGNMENTS, 1, 1).field(CONSIGNMENT_REF, "K7", 1, 1, 1)
                .group(MARKS, 1, 1, 1).field(MARK, "M1", 1, 1, 1, 1)
                .group(MARKS, 1, 1, 2).field(MARK, "M9", 1, 1, 2, 1)
                .group(CONSIGNMENTS, 1, 2).field(CONSIGNMENT_REF, "K8", 1, 2, 1)
                .group(MARKS, 1, 2, 1).field(MARK, "M9", 1, 2, 1, 1)
        }

    /**
     * **The discriminator for the two rows above, which agree on ONE message and do not say why.** Both are
     * equally explained by "the rule evaluated once over the whole operand" and by "it evaluated per row and both
     * messages landed on the same address, where one absorbed the other" — the error field resolves above the
     * repeatable group in each. Moving the error field INSIDE the rule's own group separates them: per-row
     * evaluation now has two distinct addresses to report at, so a single message can no longer be an artefact of
     * address collision.
     */
    @Test
    fun aPerRowErrorFieldSeparatesEvaluatingOnceFromCollapsingOnOneAddress() =
        agreeFireSet(
            guarded("FieldValuesNotUnique(/Shipment/Consignments*)"),
            expected = listOf("/Shipment[1]/Consignments[1]/Ref|VALUE_ERROR|$CONSIGNMENT_REFS"),
            msg = "the same duplicate, rule on /Shipment/Consignments with a per-row error field",
            model = starredNested,
            ruleGroup = CONSIGNMENTS,
            errorPath = "../Ref",
        ) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .group(CONSIGNMENTS, 1, 1).field(CONSIGNMENT_REF, "K7", 1, 1, 1)
                .group(MARKS, 1, 1, 1).field(MARK, "M1", 1, 1, 1, 1)
                .group(MARKS, 1, 1, 2).field(MARK, "M9", 1, 1, 2, 1)
                .group(CONSIGNMENTS, 1, 2).field(CONSIGNMENT_REF, "K8", 1, 2, 1)
                .group(MARKS, 1, 2, 1).field(MARK, "M9", 1, 2, 1, 1)
        }

    /**
     * **Does the anchor follow the duplicate, or is it always the first row?** The case above cannot tell: its
     * duplicate touches row 1, so "anchored at row 1" and "anchored where the duplicate is" predict the same
     * address. Here the duplicate lies WHOLLY inside row 2 — `Ref` = `K8` repeats in that same row's `Marks[1]`,
     * and row 1 holds nothing repeated — so the two accounts finally disagree.
     */
    @Test
    fun theAnchorStaysAtTheFirstRowEvenWhenTheDuplicateLiesWhollyInTheSecond() =
        agreeFireSet(
            guarded("FieldValuesNotUnique(/Shipment/Consignments*)"),
            expected = listOf("/Shipment[1]/Consignments[1]/Ref|VALUE_ERROR|$CONSIGNMENT_REFS"),
            msg = "duplicate wholly inside Consignments[2], per-row error field",
            model = starredNested,
            ruleGroup = CONSIGNMENTS,
            errorPath = "../Ref",
        ) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .group(CONSIGNMENTS, 1, 1).field(CONSIGNMENT_REF, "K7", 1, 1, 1)
                .group(MARKS, 1, 1, 1).field(MARK, "M1", 1, 1, 1, 1)
                .group(CONSIGNMENTS, 1, 2).field(CONSIGNMENT_REF, "K8", 1, 2, 1)
                .group(MARKS, 1, 2, 1).field(MARK, "K8", 1, 2, 1, 1)
        }

    // ── the UNSTARRED group operand, whose message contract the four rows above do NOT reach ──────────────

    /**
     * **Every row above authors the star**, so "the group operand reports once at the first row" was measured for
     * exactly one of the two forms. The lean project's 2026-08-14 reply flagged the over-claim, and it is not
     * pedantry about a form nobody runs: a *repeatable* group operand MUST carry its `*`, so the unstarred form is
     * necessarily the non-repeatable-group-with-a-repeatable-subgroup shape — the arm where the iteration is
     * implicit in the MODEL and there is no star anywhere in the condition from which to read a level off.
     */
    @Test
    fun anUnstarredGroupOperandAlsoReportsOnceFromARootPlacedRule() =
        agreeFireSet(
            guarded("FieldValuesNotUnique(/Shipment/Carrier)"),
            expected = listOf("/Shipment[1]/Reference|VALUE_ERROR|$CARRIER_REFS"),
            msg = "unstarred /Shipment/Carrier, Name duplicates Handoffs[1]/Site, rule on /Shipment",
            model = nested,
        ) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .field(CARRIER_NAME, "DHL", 1, 1, 1)
                .group(HANDOFFS, 1, 1, 1).field(SITE, "DHL", 1, 1, 1, 1)
        }

    /**
     * **The level question, which is the whole point of the unstarred arm.** The error field sits INSIDE the nested
     * repeatable subgroup, so per-row addresses exist to report at — and the duplicate lies wholly within that
     * subgroup, at rows 1 and 2, with the direct `Name` distinct. Nothing in the condition is starred, so whatever
     * level the anchor is read off is a property of the model's repeatability alone.
     */
    @Test
    fun anUnstarredGroupOperandAnchorsAtTheNestedSubgroupsFirstRow() =
        agreeFireSet(
            guarded("FieldValuesNotUnique(/Shipment/Carrier)"),
            expected = listOf("/Shipment[1]/Carrier[1]/Handoffs[1]/Site|VALUE_ERROR|$CARRIER_REFS"),
            msg = "unstarred operand, duplicate inside Handoffs rows 1+2, error field inside Handoffs",
            model = nested,
            ruleGroup = HANDOFFS,
            errorPath = "../Site",
        ) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .field(CARRIER_NAME, "DHL", 1, 1, 1)
                .group(HANDOFFS, 1, 1, 1).field(SITE, "UPS", 1, 1, 1, 1)
                .group(HANDOFFS, 1, 1, 2).field(SITE, "UPS", 1, 1, 2, 1)
        }

    /**
     * The case above places the duplicate at nested rows 1 and 2, so "anchored at the first row" and "anchored
     * where the duplicate is" still predict the same address — the same blind spot the starred arm needed a fourth
     * row to escape. Here rows **2 and 3** carry it and row 1 is distinct, so the two accounts finally disagree on
     * the unstarred form too.
     */
    @Test
    fun theUnstarredAnchorStaysAtTheFirstNestedRowWhenTheDuplicateSitsInLaterOnes() =
        agreeFireSet(
            guarded("FieldValuesNotUnique(/Shipment/Carrier)"),
            expected = listOf("/Shipment[1]/Carrier[1]/Handoffs[1]/Site|VALUE_ERROR|$CARRIER_REFS"),
            msg = "unstarred operand, duplicate in Handoffs rows 2+3, row 1 distinct",
            model = nested,
            ruleGroup = HANDOFFS,
            errorPath = "../Site",
        ) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .field(CARRIER_NAME, "DHL", 1, 1, 1)
                .group(HANDOFFS, 1, 1, 1).field(SITE, "AAA", 1, 1, 1, 1)
                .group(HANDOFFS, 1, 1, 2).field(SITE, "UPS", 1, 1, 2, 1)
                .group(HANDOFFS, 1, 1, 3).field(SITE, "UPS", 1, 1, 3, 1)
        }

    // ── the operand vs the ITERATING ROW: does an anchored middle level pin the compared set? ─────────────

    /**
     * **The case the deeper-rule-group row above looks like it covers and does not.** Iteration here is
     * reference-driven, so `ruleGroup` alone drives nothing: over [deepNested] every reference is non-repeatable,
     * the plan is `Once`, and no level is ever pinned. This row uses [deepNestedMarked] so the guard
     * `FieldFilled(…/Handovers/Marker)` genuinely ANCHORS iteration at the middle repeatable level while the
     * operand still names its ancestor `/Shipment/Depot`.
     *
     * The duplicate spans **two different `Handovers` rows'** `Scans`, and `Name` is distinct, so the two accounts
     * separate cleanly: an operand-bounded compared set sees both codes and fires; one pinned to the iterating row
     * sees one code per row and never finds a pair. IF287 already established operand-boundedness for an
     * unanchored placement — this asks whether an anchored middle level changes it.
     */
    @Test
    fun anAnchoredMiddleLevelDoesNotPinTheOperandsComparedSet() =
        agreeFires(
            "FieldFilled(/Shipment/Depot/Handovers/Marker) And FieldValuesNotUnique(/Shipment/Depot)",
            expected = true,
            msg = "duplicate spans Handovers[1]/Scans[1] and Handovers[2]/Scans[1]; iteration anchored at Handovers",
            model = deepNestedMarked,
            ruleGroup = "/Shipment/Depot/Handovers",
            errorPath = "../Marker",
        ) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .field(DEPOT_NAME, "N0", 1, 1, 1)
                .group(HANDOVERS, 1, 1, 1).field("/Shipment/Depot/Handovers/Marker", "M1", 1, 1, 1, 1)
                .group(SCANS, 1, 1, 1, 1).field(SCAN_CODE, "SAME", 1, 1, 1, 1, 1)
                .group(HANDOVERS, 1, 1, 2).field("/Shipment/Depot/Handovers/Marker", "M2", 1, 1, 2, 1)
                .group(SCANS, 1, 1, 2, 1).field(SCAN_CODE, "SAME", 1, 1, 2, 1, 1)
        }

    /**
     * The same question for the **authored star**, whose arm keeps its own `bindDepth` rather than the operand
     * depth. Guarding on `Consignments/Ref` — a direct field of the starred group — anchors iteration there, so
     * this asks whether an authored star's expansion is pinned by its own iterating row. The existing cross-row
     * row guards on the non-repeatable `/Shipment/Reference`, which binds `Once` and cannot reach it.
     */
    @Test
    fun anAnchoredStarredGroupStillComparesAcrossItsOwnRows() =
        agreeFires(
            "FieldFilled(/Shipment/Consignments/Ref) And FieldValuesNotUnique(/Shipment/Consignments*)",
            expected = true,
            msg = "duplicate spans Consignments[1]/Marks[2] and Consignments[2]/Marks[1]; anchored at Consignments",
            model = starredNested,
            ruleGroup = CONSIGNMENTS,
            errorPath = "../Ref",
        ) { b ->
            b.group("/Shipment", 1).field(REFERENCE, "SHP-1", 1, 1)
                .group(CONSIGNMENTS, 1, 1).field(CONSIGNMENT_REF, "K7", 1, 1, 1)
                .group(MARKS, 1, 1, 1).field(MARK, "M1", 1, 1, 1, 1)
                .group(MARKS, 1, 1, 2).field(MARK, "M9", 1, 1, 2, 1)
                .group(CONSIGNMENTS, 1, 2).field(CONSIGNMENT_REF, "K8", 1, 2, 1)
                .group(MARKS, 1, 2, 1).field(MARK, "M9", 1, 2, 1, 1)
        }
}
