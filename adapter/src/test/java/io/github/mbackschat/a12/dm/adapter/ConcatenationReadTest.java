package io.github.mbackschat.a12.dm.adapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.dsl.StringExpr;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 read tail — string concatenation {@code [Full] != [First] + " " + [Last]} on the right of a
 * consistency rule (a stored composite string vs the parts it should equal). The kernel emits {@code +}
 * as a string-typed {@code ICompositeOperation} (CalcOp ADD); the converter flattens it into a
 * {@code Concatenation} node. Own domain: a person whose stored {@code FullName} must equal first +
 * space + last.
 */
class ConcatenationReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static String personJson() {
        return DocumentModelDraft.named("person").locale("en_US")
                .rootGroup("Person", p -> p
                        .stringField("FirstName", s -> { })
                        .stringField("LastName", s -> { })
                        .stringField("FullName", s -> { }))
                .build().toJson();
    }

    @Test
    void roundTripsAThreePartConcatenation() {
        BoundModel m = BoundModel.ofJson(personJson());
        StringExpr full = m.str("/Person/FirstName").asString()
                .plus(" ").plus(m.str("/Person/LastName").asString());
        Condition cond = m.str("/Person/FullName").ne(full);

        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "FullNameRule", cond)));
        assertEquals(RENDER.render(cond), RENDER.render(converted(adapter.read("/Person/FullNameRule"))));
    }

    @Test
    void concatenationIsKernelValid() {
        ValidationResult r = new RuleValidator().check(personJson(),
                new DraftRule("/Person", "FullNameRule", "../FullName",
                        "AllFieldsFilled(FirstName, LastName) And [/Person/FullName] != [/Person/FirstName] + \" \" + [/Person/LastName]",
                        "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance, got: " + r.diagnostics());
    }

    /** A title whose labels remap its values, and a plan whose labels repeat them. */
    private static String titledPersonJson() {
        return DocumentModelDraft.named("person").locale("en_US")
                .rootGroup("Person", p -> p
                        .stringField("LastName", s -> { })
                        .stringField("FullName", s -> { })
                        .enumField("Title", e -> e.value("DR", l -> l.label("en_US", "Doctor"))
                                .value("PROF", l -> l.label("en_US", "Professor")))
                        .enumField("Plan", e -> e.value("BASIC", l -> l.label("en_US", "BASIC"))))
                .build().toJson();
    }

    /** An enumeration on either side of a text reads back into the same typed concatenation (KF530). */
    @Test
    void roundTripsAnEnumerationConcatenation() {
        BoundModel m = BoundModel.ofJson(titledPersonJson());
        var title = m.enumOf("/Person/Title");
        var last = m.str("/Person/LastName").asString();
        for (Condition cond : java.util.List.of(
                m.str("/Person/FullName").ne(title.plus(" ").plus(last)),
                m.str("/Person/FullName").ne(last.plus(" ").plus(title)))) {
            KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "FullNameRule", cond)));
            assertEquals(RENDER.render(cond), RENDER.render(converted(adapter.read("/Person/FullNameRule"))));
        }
    }

    /** The same concatenation computed into a text field reads back as the computation it was written as. */
    @Test
    void roundTripsAnEnumerationConcatenationComputation() {
        BoundModel m = BoundModel.ofJson(titledPersonJson());
        Computation comp = m.computation(m.field("/Person/FullName")).name("FullNameComp")
                .calc(m.enumOf("/Person/Title").plus(" ").plus(m.str("/Person/LastName").asString()))
                .message(en("x")).build();
        ReadComputation result = KernelAdapter.of(m.add(comp)).readComputation("/Person/FullNameComp");
        Computation got = assertInstanceOf(ReadComputation.Converted.class, result,
                () -> "expected a converted computation, got: " + result).computation();
        assertEquals(comp.draft(), got.draft());
    }

    /**
     * An immediate read of an enumeration whose labels repeat its values is legal, and the typed form it reads into
     * renders braced, which A12 admits whatever the labels (KF396).
     */
    @Test
    void readsAnImmediateEnumerationReadIntoTheBracedForm() {
        String json = new RuleValidator().inject(titledPersonJson(), new DraftRule("/Person", "FullNameRule",
                "../FullName", "FieldFilled(LastName) And [FullName] != [Plan] + [LastName]", "C", "ERROR"));
        assertEquals("FieldFilled(/Person/LastName) And [/Person/FullName] != { [/Person/Plan] } + [/Person/LastName]",
                RENDER.render(converted(KernelAdapter.of(BoundModel.ofJson(json)).read("/Person/FullNameRule"))));
    }

    /**
     * Two enumeration reads side by side, which A12 admits with and without a text after them (KF530), read back
     * into the typed `EnumRef.plus(EnumRef)` form.
     */
    @Test
    void roundTripsAdjacentEnumerations() {
        BoundModel m = BoundModel.ofJson(titledPersonJson());
        var title = m.enumOf("/Person/Title");
        var plan = m.enumOf("/Person/Plan");
        for (Condition cond : java.util.List.of(
                m.str("/Person/FullName").ne(title.plus(plan).plus(m.str("/Person/LastName").asString())),
                m.str("/Person/FullName").ne(title.plus(plan)))) {
            KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, "FullNameRule", cond)));
            assertEquals(RENDER.render(cond), RENDER.render(converted(adapter.read("/Person/FullNameRule"))));
        }
    }

    private static Rule rule(BoundModel m, String name, Condition cond) {
        return m.rule(m.group("/Person")).name(name).errorField(m.field("/Person/FullName")).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
