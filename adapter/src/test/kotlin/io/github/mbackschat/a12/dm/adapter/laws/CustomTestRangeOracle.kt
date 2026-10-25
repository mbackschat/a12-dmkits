package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.core.customfieldtype.ICustomFieldTypeFactory
import com.mgmtp.a12.kernel.core.rt.a12internal.ValidationMode
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.DocumentV2
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel
import com.mgmtp.a12.kernel.md.rt.a12internal.service.DocumentRtService
import com.mgmtp.a12.kernel.md.rt.a12internal.service.DocumentValidationResultImpl
import com.mgmtp.a12.kernel.md.rt.internal.service.DocumentPointerConverter
import com.mgmtp.a12.kernel.md.rt.internal.service.IdentifierToStringForValidation
import com.mgmtp.a12.kernel.md.rt.internal.service.LowKernelApiUtils
import java.util.Locale

/** Uses A12's core mode API because LowKernelApiUtils fixes the modern route to fullExamination. */
internal class CustomTestRangeOracle(private val service: DocumentRtService, private val model: IDocumentModel) {
    fun hasUniqueEntities(): Boolean {
        val metadata = service.getMetaModel(model).getValue(
            com.mgmtp.a12.kernel.core.rt._30_8.internal.util.IMetaKeysInternal.MODEL_METADATAVALIDIERUNG,
        ) as com.mgmtp.a12.kernel.core.rt._30_8.internal.core.IMetaDataValidierung
        return metadata.hasUniqueEntities()
    }

    fun validate(
        document: DocumentV2,
        relevant: Set<RuntimeLaws.Relevant>?,
        mode: ValidationMode.ValidationType,
        fieldTypes: ICustomFieldTypeFactory,
        observeIncorrect: (Set<com.mgmtp.a12.kernel.md.document.apiV2.DocumentPointer>) -> Unit = {},
    ): Map<String, Any> {
        val result = DocumentValidationResultImpl()
        val data = LowKernelApiUtils.createData(document, service.getMetaModel(model), Locale.US, result,
            IdentifierToStringForValidation(Locale.US, null, { model }, model.header.id))
        val config = ValidationMode().apply {
            language = Locale.US
            validationType = mode
            ruleType = ValidationMode.RuleType.ALL
            setCustomFieldTypeFactory(fieldTypes)
        }
        val observations = mutableListOf<Map<String, Any?>>()
        val observer = com.mgmtp.a12.kernel.core.rt.a12internal.validation.ICustomCondition { _, supplied, incorrect, _ ->
            observeIncorrect(DocumentPointerConverter.convertToDocumentPointerSet(incorrect))
            observations += mapOf("relevant" to supplied?.map { it.toString() }?.sorted(),
                "incorrect" to incorrect.map { it.toString() }.sorted())
            false
        }
        val conditions = mapOf("ObserveMembership" to observer)
        val validator = service.getValidator(model)
        if (relevant == null) validator.validateAll(data, conditions, result, config)
        else validator.validatePartlyWith3ValueLogic(data,
            DocumentPointerConverter.convertToIIdentifierSet(RuntimeLaws.relevantPointers(relevant)), conditions, result, config)
        return mapOf("messages" to messages(result.messages), "membership" to observations)
    }

    /** Full examination with CORE conditions bound, so a law can observe the core `IData` view that the md-API
     *  wrapper (`CustomConditionWrapper`) hides behind its `DocumentV2` argument. */
    fun validateWithCoreConditions(
        document: DocumentV2,
        conditions: Map<String, com.mgmtp.a12.kernel.core.rt.a12internal.validation.ICustomCondition>,
    ): List<List<Any>> {
        val result = DocumentValidationResultImpl()
        val data = LowKernelApiUtils.createData(document, service.getMetaModel(model), Locale.US, result,
            IdentifierToStringForValidation(Locale.US, null, { model }, model.header.id))
        val config = ValidationMode().apply {
            language = Locale.US
            validationType = ValidationMode.ValidationType.fullExamination
            ruleType = ValidationMode.RuleType.ALL
        }
        service.getValidator(model).validateAll(data, conditions, result, config)
        return messages(result.messages)
    }

    companion object {
        fun messages(messages: List<com.mgmtp.a12.kernel.md.rt.api.IMessage>, sorted: Boolean = true) =
            (if (sorted) messages.sortedBy { "${it.errorCode}|${it.messageType}|${it.errorFieldPointer.fullName()}|${it.errorFieldPointer.repetitionIndexes()}" } else messages)
            .map { listOf(it.errorCode, it.messageType.toString(), it.errorFieldPointer.fullName(), it.errorFieldPointer.repetitionIndexes()) }
    }
}
