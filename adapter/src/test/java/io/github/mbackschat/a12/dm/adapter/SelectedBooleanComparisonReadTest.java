package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoolRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConfirmRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * A key-selected Boolean or confirm cell compared with {@code True} or {@code False} reads back as authored. A12
 * admits these comparisons in either direction (a12-kernel-lean's selected-typed-comparison capture, which also
 * recorded them reading back unconverted); the kernel's consistency gate re-checks each one here.
 *
 * <p>Own domain (an insurance policy whose claims are keyed by code).
 */
class SelectedBooleanComparisonReadTest {

    private static final String POLICY = DocumentModelDraft.named("policy-selected-flags").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Key", f -> { })
                    .group("Claims", c -> c.repeatable(5).indexField("Code")
                            .stringField("Code", f -> { })
                            .booleanField("Approved", b -> { })
                            .confirmField("Signed", b -> { })))
            .build().toJson();

    @Test
    void aSelectedFlagComparisonReadsBackAsAuthored() {
        BoundModel m = BoundModel.ofJson(POLICY);
        FieldRef key = m.field("/Policy/Key");
        BoolRef approved = m.bool("/Policy/Claims/Approved");
        ConfirmRef signed = m.confirm("/Policy/Claims/Signed");
        Map<String, Condition> authored = new LinkedHashMap<>();
        authored.put("ApprovedTrue", key.filled().and(approved.forIndex(key).isTrue()));
        authored.put("ApprovedFalse", key.filled().and(approved.forIndex(key).isFalse()));
        authored.put("ApprovedNotTrue", key.filled().and(approved.forIndex("C1").isNotTrue()));
        authored.put("ApprovedNotFalse", key.filled().and(approved.forIndex("C1").isNotFalse()));
        authored.put("SignedTrue", key.filled().and(signed.forIndex(key).confirmed()));
        authored.put("SignedNotTrue", key.filled().and(signed.forIndex("C1").notConfirmed()));
        BoundModel withRules = m;
        for (Map.Entry<String, Condition> rule : authored.entrySet()) {
            withRules = withRules.add(m.rule(m.group("/Policy")).name(rule.getKey()).errorField(key)
                    .when(rule.getValue()).code("FLAG").severity(Severity.ERROR).message(en("Check the claim."))
                    .build());
        }
        // the consistency gate is the admission oracle: it throws on a rule the kernel refuses
        KernelAdapter adapter = KernelAdapter.checkAndOpen(withRules);
        Map<String, Object> read = new LinkedHashMap<>();
        authored.keySet().forEach(name -> read.put(name, adapter.read("/Policy/" + name) instanceof
                ReadResult.Converted converted ? converted.condition() : adapter.read("/Policy/" + name)));
        assertEquals(authored, read);
    }
}
