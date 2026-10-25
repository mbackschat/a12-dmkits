package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.bridge.InterpreterConditionReadBack;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.spi.ConditionReadBack;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * Reading a rule back keeps A12's scale-gate waiver, which the kernel holds on the root of the parsed condition
 * rather than on any comparison (KERNEL-FINDINGS KF75; API-FINDINGS AF192, DG140), on both read-back backends. Before,
 * the kernel backend converted the condition and dropped the directive, so a reader rebuilding the rule from it
 * wrote a rule A12 refuses, and the interpreter backend could not parse the directive and left the rule opaque.
 *
 * <p>The unwaived rule is the control that the flag is read from the model rather than defaulted. A German-condition
 * model stores the directive as {@code @UnterdrueckeWarnung}, which both backends read the same way.
 */
class ScaleWaiverReadTest {

    private static final BoundModel M = BoundModel.ofJson(DocumentModelDraft.named("policy-scale-read").locale("en_US")
            .rootGroup("Policy", p -> p
                    .numberField("Units", n -> n.fractionDigits(0))
                    .numberField("Rate", n -> n.fractionDigits(2)))
            .build().toJson());
    private static final NumberRef UNITS = M.num("/Policy/Units");
    private static final NumberRef RATE = M.num("/Policy/Rate");

    private static Rule waived() {
        return M.rule(M.group("/Policy")).name("Waived").errorField(UNITS.field())
                .when(RATE.filled().and(UNITS.eq(RATE.asNumber().allowingDifferingDecimals())))
                .allowDifferingDecimals().code("WAIVED").message(Dsl.en("Units equal the rate.")).build();
    }

    @Test
    void aWaivedRuleReadsBackWithItsWaiverAndItsCondition() {
        Rule waived = waived();
        Rule plain = M.rule(M.group("/Policy")).name("Plain").errorField(UNITS.field()).when(UNITS.gt(RATE))
                .code("PLAIN").message(Dsl.en("Units exceed the rate.")).build();
        String json = M.add(waived).add(plain).toJson();

        for (ConditionReadBack backend : List.of(new KernelConditionReadBack(), new InterpreterConditionReadBack())) {
            Map<String, ReadResult> read = backend.readAll(json, ReferenceResolvers.selfContained());
            var converted = assertInstanceOf(ReadResult.Converted.class, read.get("/Policy/Waived"),
                    () -> backend + ": " + read.get("/Policy/Waived"));
            assertEquals(true, converted.allowsDifferingDecimals(), backend::toString);
            assertEquals(waived.condition(), converted.condition(), backend::toString);
            assertEquals(false, assertInstanceOf(ReadResult.Converted.class, read.get("/Policy/Plain"), backend::toString)
                    .allowsDifferingDecimals(), backend::toString);
        }
    }

    @Test
    void aGermanWaiverReadsBackOnBothBackends() {
        String german = ModelDocument.read(M.toJson()).setConditionLanguage("de_DE").toJson();
        String json = new RuleValidator().inject(german, waived().draft(ConditionLanguage.DE));
        assertTrue(json.contains("@UnterdrueckeWarnung"), json);

        for (ConditionReadBack backend : List.of(new KernelConditionReadBack(), new InterpreterConditionReadBack())) {
            Map<String, ReadResult> read = backend.readAll(json, ReferenceResolvers.selfContained());
            var converted = assertInstanceOf(ReadResult.Converted.class, read.get("/Policy/Waived"),
                    () -> backend + ": " + read.get("/Policy/Waived"));
            assertEquals(true, converted.allowsDifferingDecimals(), backend::toString);
            assertEquals(waived().condition(), converted.condition(), backend::toString);
        }
    }
}
