package io.github.mbackschat.a12.dm.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mgmtp.a12.kernel.core.tool.a12internal.api.ado.IRule;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.services.IBaseCalculationService.ICalculationRule;
import com.mgmtp.a12.kernel.md.model.internal.service.mapping.IRuleFromComputation;

import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;

/**
 * The members A12 stores on a rule, or on the computation a calculation rule was generated from, read as stored and
 * independent of converting the formula, so an opaque formula still reports them.
 */
final class StoredMembers {

    private StoredMembers() {}

    /** The preamble A12 stores for a computation, after its reader folded a legacy suppression list into it. */
    static Optional<String> additionalParserInformation(ICalculationRule calculation) {
        if (!(calculation.getRule() instanceof IRuleFromComputation source)) {
            return Optional.empty();
        }
        return source.getOriginalComputation().getAdditionalParserInformation().filter(text -> !text.isBlank());
    }

    /** The code A12 stores for a computation's generated validation, when it declares one. */
    static Optional<String> errorCode(ICalculationRule calculation) {
        return calculation.getRule() instanceof IRuleFromComputation source
                ? source.getOriginalComputation().getErrorCode() : Optional.empty();
    }

    /** The verbatim stored text to preserve for an opaque computation: a computation is stored as a rule, so this
     *  is its raw {@code errorConditionText}. */
    static String verbatimComputationText(ICalculationRule calculation) {
        String raw = calculation.getRule().getErrorConditionText();
        return raw == null ? "" : raw;
    }

    /** A rule's or a computation's error messages (the kernel requires them) as rulekit {@link LocalizedText}. */
    static List<LocalizedText> messages(IRule rule) {
        List<LocalizedText> messages = new ArrayList<>();
        rule.getErrorMessages().forEach((locale, text) -> messages.add(new LocalizedText(locale.toString(), text)));
        return messages;
    }
}
