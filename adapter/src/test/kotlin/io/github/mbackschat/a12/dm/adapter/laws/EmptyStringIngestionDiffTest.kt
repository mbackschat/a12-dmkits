package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine lock for an explicitly stored empty string. The kernel keeps the cell present but treats `""` as
 * no value at every presence consumer. The profile-derived discriminator exposed direct `FieldFilled`; the
 * predicted siblings here lock negative field presence, non-repeatable group presence, and requiredness.
 */
class EmptyStringIngestionDiffTest {

    private val base = DocumentModelDraft.named("empty-string-ingestion").locale("en_US")
        .rootGroup("Application") { application ->
            application.stringField("Code") { }
            application.stringField("RequiredCode") { it.required() }
            application.group("Details") { details -> details.stringField("Note") { } }
        }
        .build()
        .toJson()
    private val model = BoundModel.ofJson(base)
    private val code = model.str(CODE)
    private val note = model.str(NOTE)
    private val rules = listOf(
        rule(CODE_FILLED, code, code.filled()),
        rule(CODE_EMPTY, code, code.notFilled()),
        rule(DETAILS_FILLED, note, model.group(DETAILS).filled()),
        rule(DETAILS_EMPTY, note, model.group(DETAILS).notFilled()),
    )
    private val modelJson = rules.fold(base, TypedRules::inject)
    private val definition = ModelLoader.load(modelJson)
    private val javaEngine = RuntimeLaws.kernelJavaEngine(modelJson)

    @Test
    fun everyPresenceConsumerTreatsAnEmptyStringAsPresentButUnfilled() {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
            .field(CODE, "", 1, 1)
            .field(REQUIRED_CODE, "", 1, 1)
            .group(DETAILS, 1, 1)
            .field(NOTE, "", 1, 1, 1)
        val interpreterDocument = definition.newDocument().group(ROOT, 1)
            .field(CODE, "", 1, 1)
            .field(REQUIRED_CODE, "", 1, 1)
            .group(DETAILS, 1, 1)
            .field(NOTE, "", 1, 1, 1)

        val dynamic = RuntimeLaws.messages(modelJson, kernelDocument).signatures()
        val generated = javaEngine.messages(javaEngine.instance(kernelDocument)).signatures()
        val interpreted = DmInterpreter(definition).validateFull(interpreterDocument).mapTo(sortedSetOf()) {
            "${it.code}|${if (it.type == MsgType.VALUE) "VALUE_ERROR" else "OMISSION_ERROR"}|${it.errorPath}"
        }

        assertEquals(
            sortedSetOf(
                "$CODE_EMPTY|OMISSION_ERROR|/Application[1]/Code",
                "$DETAILS_EMPTY|OMISSION_ERROR|/Application[1]/Details[1]/Note",
                "mandatoryField|OMISSION_ERROR|/Application[1]/RequiredCode",
            ),
            dynamic,
            "normative dynamic-Groovy outcome",
        )
        assertEquals(dynamic, generated, "generated Java must agree")
        assertEquals(dynamic, interpreted, "interpreter must agree")
    }

    private fun rule(
        name: String,
        errorField: io.github.mbackschat.a12.dm.rulekit.dsl.StringRef,
        condition: io.github.mbackschat.a12.dm.rulekit.dsl.Condition,
    ): Rule = model.rule(model.group(ROOT)).name(name).code(name).errorField(errorField)
        .`when`(condition).severity(Severity.ERROR).message(Dsl.en("empty-string presence outcome")).build()

    private fun List<RuntimeLaws.Msg>.signatures(): Set<String> = mapTo(sortedSetOf()) {
        "${it.code()}|${it.type().name}|${it.field()}"
    }

    private companion object {
        const val ROOT = "/Application"
        const val CODE = "$ROOT/Code"
        const val REQUIRED_CODE = "$ROOT/RequiredCode"
        const val DETAILS = "$ROOT/Details"
        const val NOTE = "$DETAILS/Note"
        const val CODE_FILLED = "CODE_FILLED"
        const val CODE_EMPTY = "CODE_EMPTY"
        const val DETAILS_FILLED = "DETAILS_FILLED"
        const val DETAILS_EMPTY = "DETAILS_EMPTY"
    }
}
