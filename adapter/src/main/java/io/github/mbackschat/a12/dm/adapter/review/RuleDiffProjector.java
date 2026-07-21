package io.github.mbackschat.a12.dm.adapter.review;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;

/** Projects canonical rule logic plus its complete authored envelope into deterministic diff aspects. */
final class RuleDiffProjector {

    private RuleDiffProjector() {}

    static Map<String, Map<String, String>> project(ModelDiffState state) {
        Map<String, Map<String, String>> rules = new LinkedHashMap<>();
        for (String name : state.adapter().ruleFullNames()) {
            rules.put(name, aspects(state, name));
        }
        return rules;
    }

    private static Map<String, String> aspects(ModelDiffState state, String name) {
        Map<String, String> aspects = new LinkedHashMap<>();
        String condition;
        try {
            condition = state.adapter().canonicalConditionText(name);
        } catch (RuntimeException exception) {
            condition = state.adapter().conditionText(name);
        }
        aspects.put("condition", condition);
        aspects.put("severity", state.adapter().ruleSeverity(name));
        aspects.put("code", state.adapter().ruleErrorCode(name));
        state.adapter().ruleErrorField(name).ifPresent(error -> aspects.put("errorEntity", error));
        state.adapter().ruleMessages(name).stream().sorted(Comparator.comparing(LocalizedText::locale))
                .forEach(message -> aspects.put("message." + message.locale(), message.text()));
        putMetadata(aspects, state, name);
        return aspects;
    }

    private static void putMetadata(Map<String, String> aspects, ModelDiffState state, String name) {
        var authored = state.authored().rules().get(name);
        if (authored == null) {
            return;
        }
        FieldDiffProjector.putDescriptive(aspects, authored.meta());
    }
}
