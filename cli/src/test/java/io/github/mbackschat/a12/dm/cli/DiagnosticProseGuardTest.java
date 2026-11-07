package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import io.github.mbackschat.a12.dm.rulekit.validate.StructuralKernelFix;

/**
 * Holds the diagnostic correctives an agent receives beside a refusal to their budget and to the current contract.
 * Between 2026-08-02 and 2026-11-06 their prose grew 140%; most of that was new codes, which is wanted, but the texts
 * also accumulated finding ids an agent with only the binary cannot follow.
 *
 * <p>The budget is a total per source rather than per code, because one corrective is a sentence or two and a
 * per-code slack would bound nothing. {@link StructuralKernelFix#forMessage} hints have no code to enumerate, so
 * {@link #MESSAGE_HINT_TRIGGERS} names one message per branch; a new branch needs a trigger here to be measured.
 */
class DiagnosticProseGuardTest {

    private static final List<String> MESSAGE_HINT_TRIGGERS = List.of(
            "The date format is invalid",
            "The kernel could not build this model: (missing required errorEntityRelPath on element R)",
            "The kernel could not build this model: (missing required errorCode on element R)",
            "The kernel could not build this model: (missing required computedFieldRelPath on element C)",
            "The kernel could not build this model: (missing required id on the header)");

    @Test
    void everyCorrectiveSourceStaysWithinItsBudget() {
        Map<String, Integer> actual = new TreeMap<>();
        texts().forEach((source, texts) -> actual.put(source, texts.values().stream().mapToInt(String::length).sum()));
        List<String> defects = AgentProse.budgetDefects(actual, AgentProse.budget("diagnostics"));
        assertTrue(defects.isEmpty(), () -> AgentProse.budgetFailure("diagnostics", defects));
    }

    @Test
    void correctivesCarryNoHistoryAndNoRepeatedSentence() {
        List<String> hits = new ArrayList<>();
        texts().forEach((source, texts) -> texts.forEach((where, text) ->
                hits.addAll(AgentProse.proseDefects(source + " " + where, text))));
        assertTrue(hits.isEmpty(), () -> AgentProse.proseFailure(hits));
    }

    /** Two triggers reaching one branch would leave another branch's prose unmeasured while looking complete. */
    @Test
    void everyMessageTriggerReachesItsOwnHint() {
        Set<StructuralKernelFix.Hint> reached = new HashSet<>();
        MESSAGE_HINT_TRIGGERS.forEach(message -> assertTrue(
                reached.add(StructuralKernelFix.forMessage(message).orElseThrow(() -> new AssertionError(
                        "no forMessage hint for \"" + message + "\", so its prose is not measured"))),
                () -> "\"" + message + "\" reaches a hint another trigger already measures"));
    }

    /** Each source's served texts, keyed by the code or trigger that serves them. */
    private static Map<String, Map<String, String>> texts() {
        Map<String, String> rk = new LinkedHashMap<>();
        for (RkCode code : RkCode.values()) {
            rk.put(code.code() + ".meaning", code.meaning());
            rk.put(code.code() + ".fix", code.fix());
        }
        Map<String, String> kernel = new LinkedHashMap<>();
        for (String code : StructuralKernelFix.codes()) {
            StructuralKernelFix.Hint hint = StructuralKernelFix.forCode(code).orElseThrow();
            kernel.put(code + ".fix", hint.fix());
            kernel.put(code + ".explain", hint.explain());
        }
        for (String message : MESSAGE_HINT_TRIGGERS) {
            StructuralKernelFix.forMessage(message).ifPresent(hint -> {
                kernel.put("\"" + message + "\".fix", hint.fix());
                kernel.put("\"" + message + "\".explain", hint.explain());
            });
        }
        return Map.of("RkCode", rk, "StructuralKernelFix", kernel);
    }
}
