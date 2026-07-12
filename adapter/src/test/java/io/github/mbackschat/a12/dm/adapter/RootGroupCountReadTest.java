package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 read tail — {@code NumberOfFilledGroups(/RootGroup*)} over a <b>repeatable root group</b>: the
 * count of its filled repetitions. The kernel models this as {@code INumberOfRootGroupsOperation}
 * (distinct from the nested-group count, which bans root groups), so the converter routes it to a
 * {@code RootGroupCount} node. In scope as single-document-model semantics (a root group that is a
 * list); the multi-form/USB Vordruck <em>conditions</em> stay out. Own domain: a team document whose
 * stored member count must equal the number of member rows.
 */
class RootGroupCountReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String teamJson() {
        return DocumentModelDraft.named("team").locale("en_US")
                .rootGroup("Summary", s -> s.numberField("MemberCount", n -> { }))
                .rootGroup("Member", g -> g.repeatable(50).numberField("Age", n -> { }))
                .build().toJson();
    }

    @Test
    void roundTripsRootGroupCount() {
        // The rule is injected as raw text (the rulekit Rule builder's relPath misbehaves for a rule on a
        // root group that has a sibling repeatable root group — orthogonal to read, which is what we test).
        BoundModel m = BoundModel.ofJson(teamJson());
        Condition expected = m.num("/Summary/MemberCount").ne(Dsl.numberOfFilledRootGroups(m.group("/Member")));
        assertEquals("[/Summary/MemberCount] != NumberOfFilledGroups(/Member*)", RENDER.render(expected));

        KernelAdapter adapter = KernelAdapter.ofJson(teamWith(RENDER.render(expected), "CountRule"));
        assertEquals(RENDER.render(expected), RENDER.render(converted(adapter.read("/Summary/CountRule"))));
    }

    /** Inject a raw-text rule into the first root group's (Summary's) elements. */
    private static String teamWith(String rawCondition, String name) {
        String base = teamJson();
        String escaped = rawCondition.replace("\\", "\\\\").replace("\"", "\\\"");
        String rule = "{ \"type\": \"Rule\", \"id\": \"/Summary/" + name + "\", \"name\": \"" + name + "\","
                + " \"Rule\": { \"errorEntityRelPath\": \"../MemberCount\", \"errorCode\": \"C\","
                + " \"errorCondition\": \"" + escaped + "\", \"severity\": \"ERROR\","
                + " \"errorMessage\": [ { \"locale\": \"en_US\", \"text\": \"x\" } ] } },\n";
        int br = base.indexOf('[', base.indexOf("\"elements\""));
        return base.substring(0, br + 1) + "\n" + rule + base.substring(br + 1);
    }

    @Test
    void rootGroupCountIsKernelValid() {
        ValidationResult r = new RuleValidator().check(teamJson(),
                new DraftRule("/Summary", "CountRule", "../MemberCount",
                        "FieldFilled(MemberCount) And [/Summary/MemberCount] != NumberOfFilledGroups(/Member*)",
                        "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
