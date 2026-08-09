package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeCheckError
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeValidationParam
import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldValidator
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand
import java.util.Locale
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Registered custom-field admission precedes semantic selection and every typed starred-index consumer: fill,
 * count aggregate, and entity-valued value-list. The project rejection remains visible and the callback runs once
 * per engine call, so identity cannot obtain correctness by hiding the formal channel or repeating validation. */
class IndexIdentityCustomTypeDiffTest {

    private val base = DocumentModelDraft.named("customIndex").locale("en_US")
        .rootGroup("Order") { order ->
            order.stringField("Customer") { }
            order.group("Lines") { lines ->
                lines.repeatable(20)
                    .customField("Code") { it.type("ProjectCode") }
                    .numberField("Qty") { it.fractionDigits(0) }
                    .indexField("Code")
            }
        }
        .build()
        .toJson()

    private val bound = BoundModel.ofJson(base)
    private val renderer = DslRenderer()
    private val fillCondition = renderer.render(
        bound.str(CUSTOMER).filled().and(Dsl.atLeastOneFieldFilled(bound.flatten("/Order/Lines*/Code"))),
    )
    private val readCondition = renderer.render(
        bound.num("/Order/Lines/Qty").semanticIndex(bound.field(CUSTOMER)).filled(),
    )
    private val aggregateCondition = renderer.render(
        bound.str(CUSTOMER).filled()
            .and(Dsl.numberOfFilledFields(bound.flatten("/Order/Lines*/Code")).ge(0)),
    )
    private val valueListCondition = renderer.render(
        bound.str(CUSTOMER).filled().and(
            Dsl.atLeastOneFieldValueIncludedInValueList(
                listOf<FieldOperand>(bound.flatten("/Order/Lines*/Code")),
                bound.field(CUSTOMER),
            ),
        ),
    )
    private val model = RuntimeLaws.withRule(
        RuntimeLaws.withRule(
            RuntimeLaws.withRule(
                RuntimeLaws.withRule(base, "/Order", "Fill", "../Customer", FILL_CODE, fillCondition),
                "/Order",
                "Read",
                "../Customer",
                READ_CODE,
                readCondition,
            ),
            "/Order",
            "Aggregate",
            "../Customer",
            AGGREGATE_CODE,
            aggregateCondition,
        ),
        "/Order",
        "ValueList",
        "../Customer",
        VALUE_LIST_CODE,
        valueListCondition,
    )

    @Test
    fun aCustomInvalidIndexCellIsUnavailableAndCheckedOncePerEngineCall() {
        assertRow("ok", expected = listOf(
            "$AGGREGATE_CODE@/Order[1]/Customer|VALUE_ERROR",
            "$FILL_CODE@/Order[1]/Customer|VALUE_ERROR",
            "$READ_CODE@/Order[1]/Customer|VALUE_ERROR",
            "$VALUE_LIST_CODE@/Order[1]/Customer|VALUE_ERROR",
        ))
        assertRow("bad", expected = listOf(
            "$REJECTION@/Order[1]/Lines[1]/Code|VALUE_ERROR",
        ))
    }

    private fun assertRow(value: String, expected: List<String>) {
        val kernelDoc = RuntimeLaws.doc().group("/Order", 1).field(CUSTOMER, value, 1, 1)
            .group("/Order/Lines", 1, 1).field(INDEX, value, 1, 1, 1).field(QTY, "1", 1, 1, 1)
        val dynamicFactory = Factory()
        val dynamic = RuntimeLaws.messages(model, kernelDoc, dynamicFactory, Locale.US)
            .filter { it.code() in CODES }.map { "${it.code()}@${it.field()}|${it.type().name}" }.sorted()
        assertEquals(expected, dynamic, "dynamic Groovy result for $value")
        assertEquals(listOf(value), dynamicFactory.values, "dynamic Groovy custom check count for $value")

        val staticFactory = Factory()
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.messages(engine.instance(kernelDoc), staticFactory, Locale.US)
            .filter { it.code() in CODES }.map { "${it.code()}@${it.field()}|${it.type().name}" }.sorted()
        assertEquals(dynamic, javaStatic, "static Java result for $value")
        assertEquals(listOf(value), staticFactory.values, "static Java custom check count for $value")

        val def = ModelLoader.load(model)
        val interpreterValues = mutableListOf<String>()
        val types = mapOf("ProjectCode" to CustomFieldType { raw, _ ->
            interpreterValues += raw
            if (raw == "bad") CustomTypeRejection(REJECTION) else null
        })
        val interpreterDoc = def.newDocument().group("/Order", 1).field(CUSTOMER, value, 1, 1)
            .group("/Order/Lines", 1, 1).field(INDEX, value, 1, 1, 1).field(QTY, "1", 1, 1, 1)
        val interpreter = DmInterpreter(def, customFieldTypes = types).validate(interpreterDoc).messages
            .filter { it.code in CODES }
            .map { "${it.code}@${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }
            .sorted()
        assertEquals(dynamic, interpreter, "interpreter result for $value")
        assertEquals(listOf(value), interpreterValues, "interpreter custom check count for $value")
    }

    private class Factory : ICustomFieldTypeFactory {
        val values = mutableListOf<String>()

        override fun createCustomFieldValidator(name: String): Optional<ICustomFieldValidator> =
            if (name != "ProjectCode") Optional.empty()
            else Optional.of(object : ICustomFieldValidator {
                override fun validate(
                    value: String,
                    param: ICustomFieldTypeValidationParam,
                    isDisplay: Boolean,
                ): Optional<ICustomFieldTypeCheckError> {
                    values += value
                    return if (value != "bad") Optional.empty()
                    else Optional.of(object : ICustomFieldTypeCheckError {
                        override fun getErrorMessage() = "rejected"
                        override fun getErrorKey() = REJECTION
                    })
                }
            })
    }

    private companion object {
        const val CUSTOMER = "/Order/Customer"
        const val INDEX = "/Order/Lines/Code"
        const val QTY = "/Order/Lines/Qty"
        const val FILL_CODE = "IDX_CUSTOM_FILL"
        const val READ_CODE = "IDX_CUSTOM_READ"
        const val AGGREGATE_CODE = "IDX_CUSTOM_AGGREGATE"
        const val VALUE_LIST_CODE = "IDX_CUSTOM_VALUE_LIST"
        const val REJECTION = "PROJECT_CODE_INVALID"
        val CODES = setOf(FILL_CODE, READ_CODE, AGGREGATE_CODE, VALUE_LIST_CODE, REJECTION)
    }
}
