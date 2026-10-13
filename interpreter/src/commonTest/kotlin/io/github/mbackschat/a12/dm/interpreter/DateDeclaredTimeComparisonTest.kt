package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM + Node peer of `adapter.laws.DateDeclaredTimeComparisonDiffTest` (KERNEL-FINDINGS KF211, IF400): a dotted
 * literal compared against a TIME field declared `dd.MM.yyyy` is a date, so it compares chronologically and equals
 * the same day, exactly as against the DATE-kind twin. Typed by the kind, it compared lexically against the
 * field's ISO reading and neither rule fired.
 */
class DateDeclaredTimeComparisonTest {

    private val model = ModelDef(
        fields = listOf(FieldDef(INCIDENT, Kind.TIME, format = DOTTED), FieldDef(REPORTED, Kind.DATE, format = DOTTED)),
        rules = listOf("Incident", "Reported").flatMap { name ->
            listOf(
                RuleDef("$ROOT/${name}BeforeCutoff", ROOT, "$name < \"01.01.2024\"", "${name}BeforeCutoff", "../$name"),
                RuleDef("$ROOT/${name}OnDay", ROOT, "$name == \"05.03.2023\"", "${name}OnDay", "../$name"),
            )
        },
    )

    @Test
    fun theDeclaredFormatTypesTheLiteralOnEitherKind() {
        val document = model.newDocument().group(ROOT, 1).field(INCIDENT, "05.03.2023", 1, 1).field(REPORTED, "05.03.2023", 1, 1)
        assertEquals(
            listOf("IncidentBeforeCutoff", "IncidentOnDay", "ReportedBeforeCutoff", "ReportedOnDay"),
            DmInterpreter(model).validateFull(document).map { it.code }.sorted(),
        )
    }

    private companion object {
        const val ROOT = "/Claim"
        const val INCIDENT = "/Claim/Incident"
        const val REPORTED = "/Claim/Reported"
        const val DOTTED = "dd.MM.yyyy"
    }
}
