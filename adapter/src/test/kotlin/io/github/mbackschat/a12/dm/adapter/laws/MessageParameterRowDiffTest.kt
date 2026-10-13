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
 * selects, the last also when the selected cell is empty. Every spelling of a field reads the same cell: absolute,
 * parent-relative, and a bare short name the rule's group does not own.
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
                    .numberField("Num") { it.fractionDigits(0) }.stringField("Label") { } } }
            .build().toJson()
        val session = EditSession.read(base)
        val policy = session.group("/Policy")
        val claims = session.group("/Policy/Claims")
        val items = session.group("/Policy/Claims/Items")
        val covers = session.group("/Policy/Covers")
        val holder = policy.str("Holder")
        val pick = policy.str("Pick")
        val note = policy.str("Claims/Note")
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
        val c1 = note.semanticIndex("c1")
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
        "Tiers":[{"Num":1,"Label":"t1"},{"Num":2,"Label":"t2"}]}}"""

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

    private fun kernelTexts(dynamic: Boolean): List<String> {
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
            // Beside a keyed and a starred read, the unkeyed value binds no level and renders the FIRST claim's
            // note, not the keyed claim's (n1).
            "UNBOUND /Policy[1]/Holder[1] u=[n0]",
        )
    }
}
