package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * A key-selected enumeration cell in a stored text concatenation reads back as a condition that renders the way A12
 * admits it. With remapping labels the immediate read is refused and the braced one admitted
 * ({@code validate.laws.KeyedEnumerationConcatenationLawsTest}), so a read that rendered the cell bare would turn an
 * admitted rule into one the kernel refuses on the way back. The typed surface cannot author the shape, so the
 * rule is stored as text.
 *
 * <p>Own domain (an insurance policy's claims, keyed by code).
 */
class KeyedEnumerationConcatenationReadTest {

    private static final String POLICY = DocumentModelDraft.named("policy-keyed-plan").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Key", f -> { })
                    .stringField("Note", f -> { })
                    .group("Claims", c -> c.repeatable(5).indexField("Code")
                            .stringField("Code", f -> { })
                            .enumField("Plan", e -> e.value("BASIC", l -> l.label("en_US", "Basic cover"))
                                    .value("GOLD", l -> l.label("en_US", "Gold cover")))))
            .build().toJson();

    @Test
    void aBracedKeyedEnumerationCellReadsBackBraced() {
        String condition = "FieldFilled(Note) And [Note] == {[Claims/Plan For Key]} + [Note]";
        RuleValidator validator = new RuleValidator();
        String model = validator.inject(POLICY,
                new DraftRule("/Policy", "PlanNote", "../Note", condition, "PLAN", "ERROR"));
        ReadResult read = KernelAdapter.ofJson(model).read("/Policy/PlanNote");
        String rendered = read instanceof ReadResult.Converted converted
                ? new DslRenderer(ConditionLanguage.EN, "/Policy").render(converted.condition()) : read.toString();
        assertEquals("FieldFilled(Note) And [Note] == { [Claims/Plan For Key] } + [Note]", rendered);
    }
}
