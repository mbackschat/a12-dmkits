package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which row a message parameter renders (a12-kernel-lean `SPEC-2026-10-13-12`): the firing row, the enclosing row
 * of a nested rule, a root field, the parallel partner the join pairs, and the row a literal, field or Number key
 * selects, the last also when the selected cell is empty; a Number key selects by value. Every spelling of a field
 * reads the same cell: absolute, parent-relative, a bare short name the rule's group does not own, and each with
 * whitespace inside the parameter.
 *
 * Own domain (an insurance policy: claims keyed by reference, covers paired with limits by grade, tiers keyed by
 * number).
 */
class MessageParameterRowDiffTest {

    private val model: String = run {
        val base = DocumentModelDraft.named("policy-message-rows").locale("en_US")
            .rootGroup("Policy") { p -> p
                .stringField("Holder") { }.stringField("Pick") { }
                .group("Claims") { c -> c.repeatable(5).indexField("Ref")
                    .stringField("Ref") { }.stringField("Note") { }.stringField("Gate") { }
                    .enumField("Status") { e -> e.value("OPEN").value("SHUT").category("Band", "HIGH", "LOW") }
                    .group("Items") { i -> i.repeatable(3).stringField("Item") { } } }
                .group("Covers") { c -> c.repeatable(5).indexField("Grade")
                    .enumField("Grade") { e -> e.value("A") { it.label("en_US", "Alpha") }.value("B") { it.label("en_US", "Beta") } }
                    .stringField("Remark") { } }
                .group("Limits") { c -> c.repeatable(5).indexField("Grade")
                    .enumField("Grade") { e -> e.value("A") { it.label("en_US", "Ace") }.value("B") { it.label("en_US", "Bee") } }
                    .numberField("Cap") { it.fractionDigits(0) } }
                .group("Tiers") { t -> t.repeatable(3).indexField("Num")
                    .numberField("Num") { it.fractionDigits(0) }.stringField("Label") { } }
                .group("Bands") { b -> b.repeatable(3).indexField("Rate")
                    .numberField("Rate") { it.fractionDigits(2) }.stringField("Badge") { } } }
            .build().toJson()
        val session = EditSession.read(base)
        val policy = session.group("/Policy")
        val claims = session.group("/Policy/Claims")
        val items = session.group("/Policy/Claims/Items")
        val covers = session.group("/Policy/Covers")
        val holder = policy.str("Holder")
        val pick = policy.str("Pick")
        val note = policy.str("Claims/Note")
        val c1 = note.semanticIndex("c1")
        val ref = policy.str("Claims/Ref")
        val status = policy.enumOf("Claims/Status")
        val label = policy.str("Tiers/Label")
        val num = policy.num("Tiers/Num")
        claims.addRule { it.name("RowTokens").code("ROW").errorField(claims.str("Note"))
            .`when`(claims.str("Note").filled().and(claims.enumOf("Status").filled()).and(claims.str("Gate").notFilled()))
            .message(Dsl.en("n=[" + Dsl.fieldValue(claims.str("Note").field()) + "] l=[" + Dsl.fieldLabel(claims.str("Note").field())
                + "] s=[" + Dsl.fieldValue(claims.enumOf("Status").field()) + "] c=[" + Dsl.fieldCategory(claims.enumOf("Status").category("Band"))
                + "] g=[" + Dsl.fieldValue(claims.str("Gate").field()) + "] i=[" + Dsl.indexValue(claims.field("Ref")) + "]")).build() }
        // The parent-relative spelling is the subject here, not an authoring shortcut: a model may carry either.
        items.addRule { it.name("EnclosingRow").code("ENCLOSING").errorField(items.str("Item"))
            .`when`(items.str("Item").filled().and(note.filled()))
            .message(Dsl.en("item=[" + Dsl.fieldValue(items.str("Item").field()) + "] abs=[" + Dsl.fieldValue(note.field())
                + "] rel=[\$../Note.value\$] own=[" + Dsl.repetitionIndex(policy.group("Claims/Items")) + "]")).build() }
        claims.addRule { it.name("RootField").code("ROOT_FIELD").errorField(claims.str("Note"))
            .`when`(claims.str("Note").filled().and(holder.filled()))
            .message(Dsl.en("h=[" + Dsl.fieldValue(holder.field()) + "] hl=[" + Dsl.fieldLabel(holder.field()) + "]")).build() }
        val cap = policy.num("Limits/Cap")
        covers.addRule { it.name("Partner").code("PARTNER").errorField(covers.str("Remark"))
            .`when`(covers.str("Remark").filled().and(cap.filled()))
            .message(Dsl.en("p=[" + Dsl.fieldValue(cap.field()) + "] own=[" + Dsl.fieldValue(covers.str("Remark").field())
                + "] pl=[" + Dsl.fieldLabel(cap.field()) + "]")).build() }
        // A bare name the rule's group does not own resolves by its unique short name; the spelling is the subject.
        claims.addRule { it.name("ShortRoot").code("SHORT_ROOT").errorField(claims.str("Note"))
            .`when`(claims.str("Note").filled().and(holder.filled()))
            .message(Dsl.en("h=[\$Holder.value\$] hl=[\$Holder\$]")).build() }
        covers.addRule { it.name("ShortPartner").code("SHORT_PARTNER").errorField(covers.str("Remark"))
            .`when`(covers.str("Remark").filled().and(cap.filled()))
            .message(Dsl.en("p=[\$Cap.value\$] pl=[\$Cap\$]")).build() }
        policy.addRule { it.name("Keyed").code("KEYED").errorField(holder)
            .`when`(holder.filled().and(c1.filled()))
            .message(Dsl.en("kn=[" + Dsl.fieldValue(c1) + "] kr=[" + Dsl.fieldValue(ref.semanticIndex("c1")) + "] kl=["
                + Dsl.fieldLabel(c1) + "]")).build() }
        val picked = note.semanticIndex(pick.field())
        policy.addRule { it.name("FieldKeyed").code("FIELD_KEYED").errorField(pick)
            .`when`(pick.filled().and(picked.filled()))
            .message(Dsl.en("f=[" + Dsl.fieldValue(picked) + "] fk=[" + Dsl.fieldValue(ref.semanticIndex(pick.field())) + "]")).build() }
        val two = label.semanticIndex("2")
        policy.addRule { it.name("NumberKeyed").code("NUMBER_KEYED").errorField(holder)
            .`when`(holder.filled().and(two.filled()))
            .message(Dsl.en("tv=[" + Dsl.fieldValue(two) + "] tk=[" + Dsl.fieldValue(num.semanticIndex("2")) + "] tg=["
                + Dsl.repetitionIndex(policy.flattenGroup("Tiers").forIndex("2")) + "]")).build() }
        // A key spelled `2` against a stored `2.00`: only a lookup by value selects the row.
        val badge = policy.str("Bands/Badge").semanticIndex("2")
        policy.addRule { it.name("NumberScaled").code("NUMBER_SCALED").errorField(holder)
            .`when`(holder.filled().and(badge.filled()))
            .message(Dsl.en("bv=[" + Dsl.fieldValue(badge) + "]")).build() }
        // Whitespace inside a parameter, which A12 admits around every part (KERNEL-FINDINGS KF214); the spellings
        // are the subject, so the text is raw.
        claims.addRule { it.name("Spaced").code("SPACED").errorField(claims.str("Note"))
            .`when`(claims.str("Note").filled().and(claims.enumOf("Status").filled()).and(holder.filled()).and(c1.filled()))
            .message(Dsl.en("a=[\$ Note.value\$] b=[\$Note .value\$] c=[\$Note. value\$] d=[\$Status -> Band\$] " +
                "e=[\$ Holder\$] k=[\$/Policy/Claims/Note.value  For  \"c1\"\$]")).build() }
        // The index terminal needs a condition iterating its group alone, so its spellings take a rule of their own.
        claims.addRule { it.name("SpacedIndex").code("SPACED_INDEX").errorField(claims.str("Note"))
            .`when`(claims.str("Note").filled().and(claims.enumOf("Status").filled()))
            .message(Dsl.en("g=[\$# /Policy/Claims \$] x=[\$ index( Ref ) . value \$] y=[\$Status->Band \$]")).build() }
        val c2 = note.semanticIndex("c2")
        policy.addRule { it.name("KeyedEmpty").code("KEYED_EMPTY").errorField(holder)
            .`when`(holder.filled().and(c2.notFilled()).and(status.semanticIndex("c2").filled()))
            .message(Dsl.en("e=[" + Dsl.fieldValue(c2) + "] sc=[" + Dsl.fieldCategory(status.category("Band"), "c2")
                + "] sv=[" + Dsl.fieldValue(status.semanticIndex("c2")) + "]")).build() }
        val c9 = note.semanticIndex("c9")
        policy.addRule { it.name("KeyedMiss").code("KEYED_MISS").errorField(holder)
            .`when`(holder.filled().and(c9.notFilled()))
            .message(Dsl.en("m=[" + Dsl.fieldValue(c9) + "] h=[" + Dsl.fieldValue(holder.field()) + "]")).build() }
        policy.addRule { it.name("KeyedMissLabel").code("KEYED_MISS_LABEL").errorField(holder)
            .`when`(holder.filled().and(c9.notFilled()))
            .message(Dsl.en("ml=[" + Dsl.fieldLabel(c9) + "]")).build() }
        policy.addRule { it.name("KeyedMissCategory").code("KEYED_MISS_CATEGORY").errorField(holder)
            .`when`(holder.filled().and(status.semanticIndex("c9").notFilled()))
            .message(Dsl.en("mc=[" + Dsl.fieldCategory(status.category("Band"), "c9") + "]")).build() }
        claims.addRule { it.name("KeyedMissRow").code("KEYED_MISS_ROW").errorField(claims.str("Note"))
            .`when`(claims.str("Note").filled().and(c9.notFilled()))
            .message(Dsl.en("mr=[" + Dsl.fieldValue(c9) + "]")).build() }
        policy.addRule { it.name("Unbound").code("UNBOUND").errorField(holder)
            .`when`(holder.filled().and(c1.filled()).and(Dsl.numberOfFilledFields(policy.flatten("Claims*/Note")).gt(0)))
            .message(Dsl.en("u=[" + Dsl.fieldValue(note.field()) + "]")).build() }
        session.toJson()
    }

    private val document = """{"Policy":{"Holder":"Ada","Pick":"c0",
        "Claims":[{"Ref":"c0","Note":"n0","Status":"OPEN"},
                  {"Ref":"c1","Note":"n1","Status":"SHUT","Items":[{"Item":"i1"},{"Item":"i2"}]},
                  {"Ref":"c2","Status":"OPEN"}],
        "Covers":[{"Grade":"A","Remark":"r1"},{"Grade":"B","Remark":"r2"}],
        "Limits":[{"Grade":"B","Cap":7},{"Grade":"A","Cap":5}],
        "Tiers":[{"Num":1,"Label":"t1"},{"Num":2,"Label":"t2"}],
        "Bands":[{"Rate":1.50,"Badge":"b1"},{"Rate":2.00,"Badge":"b2"}]}}"""

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }

    /**
     * Both kernel strategies, row by row. A value read through a key selecting no row replaces the WHOLE text with
     * the evaluation-fault text, at the first iterated row only; a label read of the same key needs no row, and a
     * category read renders empty.
     */
    @Test
    fun bothKernelStrategiesRenderTheRowEachParameterReads() {
        assertEquals(EXPECTED, kernelTexts(dynamic = true))
        assertEquals(EXPECTED, kernelTexts(dynamic = false))
    }

    /**
     * The interpreter renders the same rows. A fault's prose is the interpreter's own (INTERPRETER-FINDINGS IF3),
     * so a fault compares by its structure: code, address and the whole text replaced.
     */
    @Test
    fun theInterpreterRendersTheSameRows() {
        val prepared = PreparedModel.loadExpandedJson(model)
        val report = prepared.createInterpreter().validateFull(prepared.documents.readDocument(document))
        assertEquals(emptyList<Any>(), report.unsupported)
        assertEquals(EXPECTED.map(::structure),
            report.findings.map { "${it.code} ${DocumentDifferential.pointer(it.errorPath)} ${it.text}" }.sorted().map(::structure))
    }

    /** Addresses, types, referenced and fill-to-fix pointers agree too, the fault messages included. */
    @Test
    fun allThreeEnginesAgreeOnEveryMessageSignature() {
        DocumentDifferential.assertAgree(model, mapOf("the claims document" to document))
    }

    /**
     * The three fault shapes IG167 named, on a model of their own because one of them needs a second locale: a key
     * selecting no row in the text of a locale not rendered, an empty FIELD key, a FIELD key whose value fails its
     * own pattern but names a row, and two carriers for a level nothing binds.
     */
    private val faultBase: String = DocumentModelDraft.named("policy-message-faults").locale("en_US").locale("de_DE")
        .rootGroup("Policy") { p -> p
            .stringField("Holder") { }.stringField("Pick") { }.stringField("Code") { it.pattern("[0-9]+", "digits") }
            .group("Claims") { c -> c.repeatable(5).indexField("Ref").stringField("Ref") { }.stringField("Note") { } }
            .group("Covers") { c -> c.repeatable(5).stringField("Remark") { } } }
        .build().toJson()

    private val faultModel: String = run {
        val session = EditSession.read(faultBase)
        val policy = session.group("/Policy")
        val covers = session.group("/Policy/Covers")
        val holder = policy.str("Holder")
        val note = policy.str("Claims/Note")
        val c9 = note.semanticIndex("c9")
        val byPick = note.semanticIndex(policy.str("Pick").field())
        val byCode = note.semanticIndex(policy.str("Code").field())
        val missing = Dsl.fieldValue(c9)
        // A12 admits a keyed text parameter only beside the same keyed read in the condition (MVK_INDEX_FOR_ERROR_TEXT_INVALID);
        // the disjunction keeps each rule firing on Holder whatever that read yields.
        policy.addRule { it.name("OtherLocale").code("OTHER_LOCALE").errorField(holder).`when`(holder.filled().or(c9.filled()))
            .message(Dsl.en("plain"), Dsl.de("m=[" + missing + "]")).build() }
        policy.addRule { it.name("EmptyKey").code("EMPTY_KEY").errorField(holder).`when`(holder.filled().or(byPick.filled()))
            .message(Dsl.en("e=[" + Dsl.fieldValue(byPick) + "]"), Dsl.de("e")).build() }
        policy.addRule { it.name("InvalidKey").code("INVALID_KEY").errorField(holder).`when`(holder.filled().or(byCode.filled()))
            .message(Dsl.en("i=[" + Dsl.fieldValue(byCode) + "]"), Dsl.de("i")).build() }
        // KF561's carrier is a root rule with a keyed and a starred read; this one iterates another repeatable group.
        val remark = covers.str("Remark")
        covers.addRule { it.name("FromRow").code("FROM_ROW").errorField(remark)
            .`when`(remark.filled().and(note.semanticIndex("c1").filled()).and(Dsl.numberOfFilledFields(policy.flatten("Claims*/Note")).gt(0)))
            .message(Dsl.en("r=[" + Dsl.fieldValue(note.field()) + "] own=[" + Dsl.fieldValue(remark.field()) + "]"), Dsl.de("r")).build() }
        session.toJson()
    }

    private val faultDocument = """{"Policy":{"Holder":"Ada","Code":"c1",
        "Claims":[{"Ref":"c0","Note":"n0"},{"Ref":"c1","Note":"n1"}],
        "Covers":[{"Remark":"r1"},{"Remark":"r2"}]}}"""

    @Test
    fun theFaultModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(faultModel))
    }

    /** A level only a starred read or no read reaches cannot be rendered at all: A12 refuses the text. */
    @Test
    fun aLevelNoConditionReadReachesIsRefused() {
        val starOnly = MessageParameterProbes.rule(faultBase, "/Policy", "Holder", Dsl.en("s=[\$/Policy/Claims/Note.value\$]"), Dsl.de("s")) { e ->
            e.group("/Policy").str("Holder").filled().and(Dsl.numberOfFilledFields(e.group("/Policy").flatten("Claims*/Note")).gt(0)) }
        val unread = MessageParameterProbes.rule(faultBase, "/Policy/Covers", "Remark", Dsl.en("r=[\$/Policy/Claims/Note.value\$]"), Dsl.de("r")) { e ->
            e.group("/Policy/Covers").str("Remark").filled() }
        val keyedOnly = MessageParameterProbes.rule(faultBase, "/Policy", "Holder", Dsl.en("k=[\$/Policy/Claims/Note.value\$]"), Dsl.de("k")) { e ->
            e.group("/Policy").str("Holder").filled().and(e.group("/Policy").str("Claims/Note").semanticIndex("c1").filled()) }
        val observed = mapOf("starred read only" to MessageParameterProbes.kernel(starOnly), "keyed read only" to MessageParameterProbes.kernel(keyedOnly),
            "no read" to MessageParameterProbes.kernel(unread))
        assertEquals(mapOf("starred read only" to listOf("PARAM_VALUE_WITH_STARREFERENCE"), "keyed read only" to listOf("INVALID_FIELD"),
            "no read" to listOf("INVALID_FIELD")), observed)
    }

    @Test
    fun theUnmeasuredFaultShapesAgree() {
        val dynamic = kernelTexts(true, faultModel, faultDocument)
        val generated = kernelTexts(false, faultModel, faultDocument)
        val prepared = PreparedModel.loadExpandedJson(faultModel)
        val report = prepared.createInterpreter().validateFull(prepared.documents.readDocument(faultDocument))
        val interpreted = report.findings.map { "${it.code} ${DocumentDifferential.pointer(it.errorPath)} ${it.text}" }.sorted()
        assertEquals(emptyList<Any>(), report.unsupported)
        assertEquals(EXPECTED_FAULTS, dynamic, "dynamic Groovy")
        assertEquals(EXPECTED_FAULTS, generated, "static Java")
        assertEquals(EXPECTED_FAULTS.map(::structure), interpreted.map(::structure), "interpreter")
    }

    private fun kernelTexts(dynamic: Boolean, model: String = this.model, document: String = this.document): List<String> {
        val messages = if (dynamic) RuntimeLaws.kernelEngine(model).let { it.validationMessages(it.instance(document), null, Locale.US) }
            else RuntimeLaws.kernelJavaEngine(model).let { it.validationMessages(it.instance(document), null, Locale.US) }
        return messages.map { m: IMessage -> "${m.errorCode} ${DocumentDifferential.pointer(m.errorFieldPointer)} ${m.errorText}" }.sorted()
    }

    private fun structure(line: String): String =
        FAULT.find(line)?.let { line.substring(0, it.range.first) + "FAULT" } ?: line

    private companion object {
        val FAULT = Regex("An error occurred while evaluati(on|ng) the data check: .*$")
        const val KERNEL_FAULT = "An error occurred while evaluation the data check: " +
            "repetition index must be > 0, or 0 for wildcard, but not negative"
        val EXPECTED = listOf(
            "ENCLOSING /Policy[1]/Claims[2]/Items[1]/Item[1] item=[i1] abs=[n1] rel=[n1] own=[1]",
            "ENCLOSING /Policy[1]/Claims[2]/Items[2]/Item[1] item=[i2] abs=[n1] rel=[n1] own=[2]",
            "FIELD_KEYED /Policy[1]/Pick[1] f=[n0] fk=[c0]",
            "KEYED /Policy[1]/Holder[1] kn=[n1] kr=[c1] kl=[Note]",
            "KEYED_EMPTY /Policy[1]/Holder[1] e=[] sc=[HIGH] sv=[OPEN]",
            "KEYED_MISS /Policy[1]/Holder[1] $KERNEL_FAULT",
            "KEYED_MISS_CATEGORY /Policy[1]/Holder[1] mc=[]",
            "KEYED_MISS_LABEL /Policy[1]/Holder[1] ml=[Note]",
            "KEYED_MISS_ROW /Policy[1]/Claims[1]/Note[1] $KERNEL_FAULT",
            "NUMBER_KEYED /Policy[1]/Holder[1] tv=[t2] tk=[2] tg=[2]",
            "NUMBER_SCALED /Policy[1]/Holder[1] bv=[b2]",
            "PARTNER /Policy[1]/Covers[1]/Remark[1] p=[5] own=[r1] pl=[Cap]",
            "PARTNER /Policy[1]/Covers[2]/Remark[1] p=[7] own=[r2] pl=[Cap]",
            "ROOT_FIELD /Policy[1]/Claims[1]/Note[1] h=[Ada] hl=[Holder]",
            "ROOT_FIELD /Policy[1]/Claims[2]/Note[1] h=[Ada] hl=[Holder]",
            "ROW /Policy[1]/Claims[1]/Note[1] n=[n0] l=[Note] s=[OPEN] c=[HIGH] g=[] i=[c0]",
            "ROW /Policy[1]/Claims[2]/Note[1] n=[n1] l=[Note] s=[SHUT] c=[LOW] g=[] i=[c1]",
            "SHORT_PARTNER /Policy[1]/Covers[1]/Remark[1] p=[5] pl=[Cap]",
            "SHORT_PARTNER /Policy[1]/Covers[2]/Remark[1] p=[7] pl=[Cap]",
            "SHORT_ROOT /Policy[1]/Claims[1]/Note[1] h=[Ada] hl=[Holder]",
            "SHORT_ROOT /Policy[1]/Claims[2]/Note[1] h=[Ada] hl=[Holder]",
            "SPACED /Policy[1]/Claims[1]/Note[1] a=[n0] b=[n0] c=[n0] d=[HIGH] e=[Holder] k=[n1]",
            "SPACED /Policy[1]/Claims[2]/Note[1] a=[n1] b=[n1] c=[n1] d=[LOW] e=[Holder] k=[n1]",
            "SPACED_INDEX /Policy[1]/Claims[1]/Note[1] g=[1] x=[c0] y=[HIGH]",
            "SPACED_INDEX /Policy[1]/Claims[2]/Note[1] g=[2] x=[c1] y=[LOW]",
            // Beside a keyed and a starred read, the unkeyed value binds no level and renders the FIRST claim's
            // note, not the keyed claim's (n1).
            "UNBOUND /Policy[1]/Holder[1] u=[n0]",
        )

        val EXPECTED_FAULTS = listOf(
            // An empty key selects no row; so does a key whose value fails its own pattern, although `c1` names one.
            "EMPTY_KEY /Policy[1]/Holder[1] $KERNEL_FAULT",
            // The first claim again, from a rule iterating covers.
            "FROM_ROW /Policy[1]/Covers[1]/Remark[1] r=[n0] own=[r1]",
            "FROM_ROW /Policy[1]/Covers[2]/Remark[1] r=[n0] own=[r2]",
            "INVALID_KEY /Policy[1]/Holder[1] $KERNEL_FAULT",
            // The German text's unmatched key does not fault the English rendering.
            "OTHER_LOCALE /Policy[1]/Holder[1] plain",
            "stringFalschesMuster /Policy[1]/Code[1] digits",
        )
    }
}
