package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * SPEC-2026-10-03-06: a DateTime wall label with no instant in the model zone is a RUNTIME failure raised where
 * the label is evaluated, not an unknown operand. A computation clears with `berechnungsWertFehler` and poisons
 * its readers; a validation rule stops its row loop and reports its own code once, at the first iterator address.
 *
 * The model crosses every carrier the label can sit in — a direct constant, a shift source, a nested shift, both
 * extractors, operand-list `Min`/`Max` in both operand orders — with dependents that separate a poisoned read from
 * a clean empty one (`FirstFilledValue` and a `FieldNotFilled` guard would select their fallback on a clean empty).
 * The rules cross the truth pass (`And` in both orders) with the classification pass, whose static enablement is a
 * property of the authored constructs: a field-value read or a child-group presence guard enables it, an
 * unfiltered `FieldFilled` with a field-free `Now` does not, and `FieldNotFilled` does.
 *
 * The JVM default is pinned to UTC for the whole file: under a Berlin default the kernel's STATIC check refuses
 * the gap label (`DateTimeConstantZoneSplitDiffTest`), while the runtime judges it against the declared zone.
 *
 * Own domain (parcel delivery tracking).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemporalValueFailureDiffTest {
    private companion object {
        const val ISO_DT = "yyyy-MM-dd'T'HH:mm:ss"

        /** Europe/Berlin skips 02:00–03:00 on 2024-03-31. */
        const val GAP = "31.03.2024T02:30:00"
        const val ORDINARY = "31.03.2024T01:30:00"
        const val FALLBACK = "05.03.2024T12:30:00"
        const val SEED = "2020-01-01T00:00:00"
        val UTC: TimeZone = TimeZone.getTimeZone("UTC")
        val KERNEL_CLOCK: Date = Date.from(Instant.parse("2026-10-04T10:00:00Z"))
        val CLOCK = EvalClock(today = "2026-10-04", now = "2026-10-04T12:00:00")
    }

    private val dateTimeTargets = listOf("GapStamp", "ShiftOrdinary", "ShiftGap", "NestedGap", "MinPickupFirst",
        "MinGapFirst", "MaxPickupFirst", "MinOrdinary", "OrdinaryStamp", "AfterShiftGap", "AfterMinPickupFirst",
        "AfterMinGapFirst", "AfterMinOrdinary", "FallbackAfterGap", "GuardAfterGap")

    private val model: String = underUtc { buildModel() }

    private fun buildModel(): String {
        val draft = DocumentModelDraft.named("parcel-delivery-temporal-failure").locale("en_US")
            .rootGroup("Delivery") { root ->
                root.dateTimeField("Pickup") { it.format(ISO_DT) }
                for (name in dateTimeTargets) root.dateTimeField(name) { it.format(ISO_DT) }
                root.dateField("GapDay") { it.format("yyyy-MM-dd") }
                root.timeField("GapClock") { }
                root.group("Stops") { stops ->
                    stops.repeatable(9)
                    stops.dateTimeField("Arrival") { it.format(ISO_DT) }
                    stops.stringField("Signed") { }
                    stops.numberField("Qty") { it.fractionDigits(0) }
                }
            }.build().toJson()
        val session = EditSession.read(draft).also { it.config().timeZone("Europe/Berlin") }
        val root = session.group("/Delivery")
        val pickup = root.dateTime("Pickup")
        fun dt(name: String) = root.dateTime(name)
        val gap = Dsl.dateTimeValue(GAP)
        val ordinary = Dsl.dateTimeValue(ORDINARY)
        var json = session.toJson()
        fun comp(target: io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef, name: String,
                 body: (io.github.mbackschat.a12.dm.rulekit.dsl.Computation.Builder) ->
                 io.github.mbackschat.a12.dm.rulekit.dsl.Computation.Builder) {
            json = TypedRules.inject(json, root.computation(target) {
                body(it.name(name + "Comp")).message(Dsl.en("Check the delivery time.")).build()
            })
        }
        comp(dt("GapStamp").field(), "GapStamp") { it.calc(gap.expression()) }
        comp(dt("ShiftOrdinary").field(), "ShiftOrdinary") { it.calc(Dsl.addSeconds(ordinary, 1).expression()) }
        comp(dt("ShiftGap").field(), "ShiftGap") { it.calc(Dsl.addSeconds(gap, 1).expression()) }
        comp(dt("NestedGap").field(), "NestedGap") { it.calc(Dsl.addHours(Dsl.addSeconds(gap, 1), 1).expression()) }
        comp(root.date("GapDay").field(), "GapDay") { it.calc(Dsl.dateFromDateTime(gap).expression()) }
        comp(root.time("GapClock").field(), "GapClock") { it.calc(Dsl.timeFromDateTime(gap).expression()) }
        comp(dt("MinPickupFirst").field(), "MinPickupFirst") { it.calc(Dsl.min(pickup, gap).expression()) }
        comp(dt("MinGapFirst").field(), "MinGapFirst") { it.calc(Dsl.min(gap, pickup).expression()) }
        comp(dt("MaxPickupFirst").field(), "MaxPickupFirst") { it.calc(Dsl.max(pickup, gap).expression()) }
        comp(dt("MinOrdinary").field(), "MinOrdinary") { it.calc(Dsl.min(pickup, ordinary).expression()) }
        comp(dt("OrdinaryStamp").field(), "OrdinaryStamp") { it.calc(Dsl.dateTimeValue(FALLBACK).expression()) }
        for ((target, source) in listOf("AfterShiftGap" to "ShiftGap", "AfterMinPickupFirst" to "MinPickupFirst",
            "AfterMinGapFirst" to "MinGapFirst", "AfterMinOrdinary" to "MinOrdinary")) {
            comp(dt(target).field(), target) { it.calc(Dsl.addSeconds(dt(source), 1).expression()) }
        }
        comp(dt("FallbackAfterGap").field(), "FallbackAfterGap") {
            it.calc(Dsl.firstFilledDateTime(dt("ShiftGap").field(), dt("OrdinaryStamp").field()).expression())
        }
        comp(dt("GuardAfterGap").field(), "GuardAfterGap") {
            it.commonPrecondition(dt("ShiftGap").notFilled()).calc(Dsl.dateTimeValue(FALLBACK).expression())
        }

        val stops = session.group("/Delivery/Stops")
        val arrival = stops.dateTime("Arrival")
        val signed = stops.str("Signed")
        val bulky = stops.num("Qty").asNumber().gt(100)
        val rules: List<Pair<String, Condition>> = listOf(
            "Direct" to arrival.before(gap),
            "Ordinary" to arrival.before(ordinary),
            "GapThenFalse" to arrival.before(gap).and(bulky),
            "FalseThenGap" to bulky.and(arrival.before(gap)),
            "Prefix" to signed.filled().or(arrival.before(gap)),
            "PurePrefix" to signed.filled().or(arrival.filled().and(Dsl.now().before(gap))),
            "GroupPrefix" to stops.ref().filled().and(signed.filled().or(Dsl.now().before(gap))),
            "NotFilledPrefix" to arrival.filled().and(signed.notFilled().or(Dsl.now().before(gap))),
            "Independent" to arrival.filled(),
        )
        for ((code, condition) in rules) {
            json = TypedRules.inject(json, stops.rule {
                val errorField = if (code in setOf("GroupPrefix")) signed.field()
                    else arrival.field()
                it.name(code).code(code).errorField(errorField).`when`(condition)
                    .message(Dsl.en("Check the stop.")).build()
            })
        }
        return json
    }

    private val seeded: String = "\"Pickup\":%s," + dateTimeTargets.joinToString(",") { "\"$it\":\"$SEED\"" } +
        ",\"GapDay\":\"2020-01-01\",\"GapClock\":\"00:00:00\""

    private fun document(pickup: String?, stops: String): String =
        "{\"Delivery\":{" + seeded.format(pickup?.let { "\"$it\"" } ?: "null").replace("\"Pickup\":null,", "") +
            ",\"Stops\":[$stops]}}"

    private fun stop(arrival: String?, signed: Boolean, qty: String = "1"): String =
        "{" + listOfNotNull(arrival?.let { "\"Arrival\":\"$it\"" }, "\"Signed\":\"yes\"".takeIf { signed },
            "\"Qty\":$qty").joinToString(",") + "}"

    private val documents = linkedMapOf(
        "all-signed" to document("2024-03-31T01:00:00",
            listOf(stop("2024-03-31T01:00:00", true), stop("2024-03-31T01:00:00", true),
                stop("2024-03-31T01:00:00", true)).joinToString(",")),
        "signed-unsigned-signed" to document(null,
            listOf(stop("2024-03-31T01:00:00", true), stop("2024-03-31T01:45:00", false),
                stop("2024-03-31T01:00:00", true)).joinToString(",")),
        "malformed" to document("late",
            listOf(stop("soon", true), stop("2024-03-31T01:00:00", false)).joinToString(",")),
        "after-ordinary" to document("2024-03-31T01:45:00", stop("2024-03-31T01:45:00", false)),
        "two-unsigned" to document("2024-03-31T01:00:00",
            listOf(stop("2024-03-31T01:00:00", false), stop("2024-03-31T01:00:00", false)).joinToString(",")),
    )

    private data class Observation(val messages: List<String>, val outcomes: Map<String, String>)

    private fun kernelSignature(m: IMessage): String =
        "${m.errorCode}|${m.messageType.name}|" +
            formatPointer(m.errorFieldPointer.fullName(), m.errorFieldPointer.repetitionIndexes()) + "|" +
            referenced(m.errorCode, m.referencedFieldsPointers.map { formatPointer(it.fullName(), it.repetitionIndexes()) })

    /** A formal code's referenced fields differ on a separate emitter (INTERPRETER-GAPS IG162); this file's claim is
     *  about rule and computation messages, whose referenced fields it compares in full. */
    private fun referenced(code: String, pointers: List<String>): List<String> =
        if (code == "datumFormatFalsch") emptyList() else pointers.sorted()

    private fun kernel(dynamic: Boolean, documentJson: String): Observation = underUtc {
        val (messages, computation) = if (dynamic) {
            val engine = RuntimeLaws.kernelEngine(model)
            val instance = engine.instance(documentJson)
            engine.validationMessages(instance, KERNEL_CLOCK, Locale.US) to
                engine.computation(engine.instance(documentJson), KERNEL_CLOCK, Locale.US)
        } else {
            val engine = RuntimeLaws.kernelJavaEngine(model)
            val instance = engine.instance(documentJson)
            engine.validationMessages(instance, KERNEL_CLOCK, Locale.US) to
                engine.computation(engine.instance(documentJson), KERNEL_CLOCK, Locale.US)
        }
        Observation(
            messages.map(::kernelSignature).sorted(),
            computation.outcomes().associate {
                it.path().substringAfterLast('/') to when {
                    it.errored() -> "ERRORED|" + it.value().orElse("")
                    it.cleared() -> "CLEARED"
                    else -> "VALUE|" + it.value().orElse("")
                }
            }.toSortedMap() + computation.formalErrorsInOperands()
                .associate { "operand@" + it.errorFieldPointer.fullName().substringAfterLast('/') to it.errorCode },
        )
    }

    private fun interpreter(documentJson: String): Observation {
        val def = ModelLoader.load(model)
        val engine = DmInterpreter(def, CLOCK)
        val messages = engine.validate(def.readDocumentJson(documentJson)).also {
            assertEquals(emptyList<Any>(), it.unsupported, "every construct must be evaluable")
        }.messages.map {
            val type = if (it.type == MsgType.VALUE) "VALUE_ERROR" else "OMISSION_ERROR"
            "${it.code}|$type|" + formatPointer(it.errorPointer.fullName, it.errorPointer.repetitionIndexes) + "|" +
                referenced(it.code, it.referencedPointers.map { p -> formatPointer(p.fullName, p.repetitionIndexes) })
        }.sorted()
        val doc = def.readDocumentJson(documentJson)
        // Only cells whose stored state CHANGES are reported by the kernel; every target is seeded, so a no-value
        // result is a reported clear and an unchanged value is absent from both sides.
        val seeds = dateTimeTargets.associateWith { SEED } + mapOf("GapDay" to "2020-01-01", "GapClock" to "00:00:00")
        val outcomes = engine.compute(doc).filter { c ->
            !(c.outcome.name == "VALUE" && seeds[c.path.substringAfterLast('/')] == c.value)
        }.associate {
            it.path.substringAfterLast('/') to when (it.outcome.name) {
                "ERRORED" -> "ERRORED|" + (it.value ?: "")
                "CLEARED" -> "CLEARED"
                else -> "VALUE|" + (it.value ?: "")
            }
        }.toSortedMap()
        val prepared = PreparedModel.loadExpandedJson(model)
        val operand = prepared.createInterpreter().compute(prepared.documents.readDocument(documentJson))
            .formalErrorsInOperands.associate { "operand@" + it.errorPath.fullName.substringAfterLast('/') to it.code }
        return Observation(messages, outcomes + operand)
    }

    @Test
    fun theModelIsKernelConsistentUnderTheAdmittingDefault() {
        assertEquals(emptyList<String>(), underUtc { MessageParameterProbes.kernel(model) })
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        for ((name, json) in documents) {
            val dynamic = kernel(dynamic = true, json)
            assertEquals(dynamic, kernel(dynamic = false, json), "$name: static Java vs dynamic Groovy")
            assertEquals(dynamic, interpreter(json), "$name: interpreter vs dynamic Groovy")
        }
    }

    /**
     * The discriminating rows, pinned against the normative kernel so the agreement above cannot pass vacuously.
     * Each names the rival account it rules out: per-row unknown instead of a failure, an `And`/`Or` that does not
     * short-circuit, a failure that discards rows already fired, `FieldNotFilled` treated as non-classifying, and
     * `Min` reaching its later literal after a malformed earlier operand.
     */
    @Test
    fun theDiscriminatingRowsAreTheKernelsOwn() {
        fun rows(doc: String, code: String) = kernel(dynamic = true, documents.getValue(doc)).messages
            .filter { it.startsWith("$code|") }.map { it.substringAfter("/Stops").substringBefore('/') }
        fun outcome(doc: String, key: String) = kernel(dynamic = true, documents.getValue(doc)).outcomes[key]
        val actual = linkedMapOf(
            "a reached gap reports once, at the first address" to rows("all-signed", "Direct"),
            "a false And prefix skips the gap" to rows("all-signed", "FalseThenGap"),
            "a later false And cannot hide a reached gap" to rows("all-signed", "GapThenFalse"),
            "a field read enables classification, which reaches the gap" to rows("all-signed", "Prefix"),
            "no classification: a true Or prefix hides the gap on every row" to rows("all-signed", "PurePrefix"),
            "rows fired before the failure keep their messages" to rows("signed-unsigned-signed", "PurePrefix"),
            "FieldNotFilled enables classification" to rows("two-unsigned", "NotFilledPrefix"),
            "a malformed first Min operand poisons before the literal" to
                outcome("malformed", "operand@MinPickupFirst"),
            "the literal first fails before the malformed operand" to outcome("malformed", "operand@MinGapFirst"),
        )
        assertEquals(linkedMapOf(
            "a reached gap reports once, at the first address" to listOf("[1]"),
            "a false And prefix skips the gap" to emptyList<String>(),
            "a later false And cannot hide a reached gap" to listOf("[1]"),
            "a field read enables classification, which reaches the gap" to listOf("[1]"),
            "no classification: a true Or prefix hides the gap on every row" to listOf("[1]", "[2]", "[3]"),
            "rows fired before the failure keep their messages" to listOf("[1]", "[1]"),
            "FieldNotFilled enables classification" to listOf("[1]"),
            "a malformed first Min operand poisons before the literal" to null,
            "the literal first fails before the malformed operand" to "berechnungsWertFehler",
        ), actual)
    }

    private fun <T> underUtc(body: () -> T): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(UTC)
        try {
            return body()
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
