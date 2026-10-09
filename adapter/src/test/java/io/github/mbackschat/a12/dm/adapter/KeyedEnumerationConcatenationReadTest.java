package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * A key-selected enumeration cell in a stored text concatenation reads back as a condition that renders the way A12
 * admits it. With remapping labels the immediate read is refused and the braced one admitted
 * ({@code validate.laws.KeyedEnumerationConcatenationLawsTest}), so a read that rendered the cell bare would turn an
 * admitted rule into one the kernel refuses on the way back. The stored-text row asks it of the brace written by hand;
 * the typed rows author the cell and a first-filled enumeration through {@code EnumExpr.plus}.
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

    @Test
    void aTypedEnumerationExpressionInAConcatenationReadsBackAsAuthored() {
        BoundModel m = BoundModel.ofJson(POLICY);
        StringExpr note = m.str("/Policy/Note").asString();
        Map<String, Condition> authored = new LinkedHashMap<>();
        authored.put("KeyedPlanNote", m.str("/Policy/Note").filled().and(note.eq(
                m.enumOf("/Policy/Claims/Plan").forIndex(m.field("/Policy/Key")).plus(note))));
        authored.put("FirstPlanNote", m.str("/Policy/Note").filled().and(note.ne(
                Dsl.firstFilledEnum(m.flatten("/Policy/Claims*/Plan")).plus("-"))));
        BoundModel withRules = m;
        for (Map.Entry<String, Condition> rule : authored.entrySet()) {
            withRules = withRules.add(m.rule(m.group("/Policy")).name(rule.getKey())
                    .errorField(m.field("/Policy/Note")).when(rule.getValue())
                    .code("PLAN").severity(Severity.ERROR).message(en("Check the plan.")).build());
        }
        // the consistency gate is the admission oracle: it throws on a rule the kernel refuses
        KernelAdapter adapter = KernelAdapter.checkAndOpen(withRules);
        Map<String, Object> read = new LinkedHashMap<>();
        authored.keySet().forEach(name -> read.put(name, adapter.read("/Policy/" + name) instanceof
                ReadResult.Converted converted ? converted.condition() : adapter.read("/Policy/" + name)));
        assertEquals(authored, read);
    }
}
