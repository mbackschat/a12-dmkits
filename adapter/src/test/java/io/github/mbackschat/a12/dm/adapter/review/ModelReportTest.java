package io.github.mbackschat.a12.dm.adapter.review;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.context.ModelCard;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/** MODEL-REVIEW — TDD for the comprehension engine (task #12): identity + structure + usage assembly. */
class ModelReportTest {

    private static final String FEE = "/Subscription/Addons/MonthlyFee";
    private static final String FEE_CAP = "/Subscription/Addons/FeeCapRule";

    /** subscription + one rulekit-authored rule that references MonthlyFee (so usage has a referrer). */
    private static String modelWithRule() {
        BoundModel m = BoundModel.ofJson(readResource("models/subscription.dm.json"));
        NumberRef fee = m.num(FEE);
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeeCapRule").errorField(fee).when(fee.gt(1000))
                .code("FEE_CAP").severity(Severity.ERROR)
                .message(en("Monthly fee too high."), de("Monatsgebühr zu hoch.")).build();
        return m.add(rule).toJson();
    }

    @Test
    void assemblesIdentity() {
        ModelReport r = ModelReport.of(modelWithRule());
        assertEquals("document", r.identity().modelType(), "it is a document model");
        assertFalse(r.identity().id().isBlank(), "the header id is read");
        assertFalse(r.identity().locales().isEmpty(), "the declared locales are read");
    }

    @Test
    void assemblesStructureOutline() {
        ModelReport r = ModelReport.of(modelWithRule());
        assertTrue(r.structure().fields().stream().anyMatch((ModelCard.Field f) -> f.path().equals(FEE)),
                "the structure outline lists the model's fields");
    }

    @Test
    void assemblesUsageWithReferrersAndUnreferenced() {
        ModelReport r = ModelReport.of(modelWithRule());
        assertTrue(r.usage().referrers().getOrDefault(FEE, java.util.List.of()).contains(FEE_CAP),
                () -> "MonthlyFee should be referenced by FeeCapRule, got: " + r.usage().referrers().get(FEE));
        assertFalse(r.usage().unreferenced().isEmpty(),
                "a model this size has fields no rule reads — the audit headline");
    }

    private static String readResource(String path) {
        try (InputStream in = ModelReportTest.class.getClassLoader().getResourceAsStream(path)) {
            assertTrue(in != null, "missing test resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
