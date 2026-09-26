package io.github.mbackschat.a12.dm.adapter.review;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.TestResources;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
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
        BoundModel m = BoundModel.ofJson(TestResources.text("models/subscription.dm.json"));
        NumberRef fee = m.num(FEE);
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeeCapRule").errorField(fee).when(fee.gt(1000))
                .code("FEE_CAP").severity(Severity.ERROR)
                .message(en("Monthly fee too high."), de("Monatsgebühr zu hoch.")).build();
        return m.add(rule).toJson();
    }

    @Test
    void assemblesAnIncludesBearingModelThroughTheKernelFreeResolver() {
        // MR7 — the review surface accepts a dm.core.ModelResolver, so a consumer resolves includes without
        // importing com.mgmtp.*; the mount can only bind if the resolver was threaded through.
        ModelResolver resolver = id -> "pricelist".equals(id)
                ? DocumentModelDraft.named("pricelist").locale("en_US")
                        .rootGroup("Pricelist", p -> p.field("Currency", FieldKind.STRING)).build().toJson()
                : null;
        EditSession s = EditSession.read(DocumentModelDraft.named("catalog").locale("en_US")
                .rootGroup("Catalog", c -> c.field("StoreName", FieldKind.STRING)).build().toJson(), resolver);
        s.addInclude("pricelist", "pricelist", "/Catalog", "Retail");
        String mounted = s.toJson();

        ModelReport r = ModelReport.of(mounted, resolver);   // dm.core.ModelResolver overload — no kernel import
        assertEquals("catalog", r.identity().id());
        assertTrue(r.structure().groups().stream().anyMatch(g -> g.path().equals("/Catalog/Retail")),
                () -> "the mounted include's group appears in the assembled structure: " + r.structure().groups());
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
    void assemblesUnreferencedDeadFields() {
        ModelReport r = ModelReport.of(modelWithRule());
        assertFalse(r.usage().unreferenced().isEmpty(),
                "a model this size has fields no rule reads — the dead-field audit headline");
        assertFalse(r.usage().unreferenced().contains(FEE),
                "MonthlyFee IS referenced (by FeeCapRule), so it must not be in the unreferenced set");
    }

    @Test
    void ruleCatalogCarriesGlossPolarityAndVerbatimMessage() {
        ModelReport r = ModelReport.of(modelWithRule());
        ModelReport.RuleEntry fee = r.rules().stream().filter(e -> e.rule().equals(FEE_CAP)).findFirst()
                .orElseThrow(() -> new AssertionError("FeeCapRule missing from the rule catalog: " + r.rules()));

        assertTrue(fee.explained(), "the FeeCap condition is rulekit-modeled");
        assertFalse(fee.gloss().isBlank(), "a plain-language gloss is computed (the tool half of the Hybrid)");
        assertEquals("/Subscription/Addons", fee.scope(), "the entry is grouped by its scope group");
        assertEquals("ERROR", fee.severity());
        assertEquals("FEE_CAP", fee.code());
        assertTrue(fee.errorField() != null && fee.errorField().endsWith("MonthlyFee"),
                () -> "the error field is reported, got: " + fee.errorField());
        assertTrue(fee.messages().stream().anyMatch(m -> m.locale().equals("en_US") && m.text().contains("too high")),
                () -> "the verbatim stored message rides alongside the gloss, got: " + fee.messages());
    }

    @Test
    void catalogsArePresentEvenWhenAModelHasNoComputations() {
        ModelReport r = ModelReport.of(modelWithRule());
        assertFalse(r.rules().isEmpty(), "the rule catalog is populated");
        assertTrue(r.computations() != null, "the computation catalog is present (empty here — exercised at the CLI level)");
    }

    @Test
    void polarityIsAModelLevelConstantNotRepeatedPerRule() {
        assertEquals("violation", ModelReport.VIOLATION_POLARITY.firesOn(),
                "an A12 rule fires on the violation — carried once at the model level, not duplicated per rule");
        assertFalse(ModelReport.VIOLATION_POLARITY.note().isBlank(), "the polarity note explains the trap");
    }
}
