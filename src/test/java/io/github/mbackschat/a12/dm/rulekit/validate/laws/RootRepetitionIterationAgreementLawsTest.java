package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.ModelDrafts;

/**
 * Whether a rule may read a REPEATABLE ROOT group's field while reporting on a fixed root's — and why the
 * class that would refuse it is out of reach.
 *
 * <p>{@code IterationChecker.checkVRefs} refuses a condition whose error entity records no {@code ITERATION}
 * at index level 0 while some other reference does, with {@code MVK_ERROR_FIELD_AND_ITERATION_INVALID}.
 * Seven earlier attempts at that code varied NESTED repeatable groups and reached other classes; this grid
 * varies the ROOT repetition, which is the level the check reads, on the estate's one multi-form carrier.
 *
 * <p><b>It is still not reachable, and the rows say why rather than adding an eighth failed spelling.</b>
 * {@code CheckIdNameRefImpl.setIndexOfCurrentLevel} records {@code WILDCARD} for a starred read and
 * {@code ITERATION} only for an UNSTARRED read of a repeatable level — and an unstarred read of an extent
 * the error field's own path does not enter is refused by the wildcard gate first. So the two states the
 * check compares cannot disagree in an A12 model: where the read is admitted the error field shares the
 * level, and where it does not the parse has already stopped.
 */
class RootRepetitionIterationAgreementLawsTest {

    private static final String MULTIFORM = ModelDrafts.repeatableRoot().toJson();

    private static String verdict(String group, String errorField, String condition) {
        return KernelLaws.verdictModel(MULTIFORM, group, errorField, condition);
    }

    @Test
    void readingARepeatableRootFromAFixedOne() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("fixed root reports, fixed root reads — the control",
                verdict("/Header", "../Title", "FieldFilled(Title)"));
        actual.put("fixed root reports, repeatable root read with a STAR",
                verdict("/Header", "../Title", "FieldFilled(Title) And AtLeastOneFieldFilled(/Persons*/Name)"));
        actual.put("fixed root reports, repeatable root read WITHOUT a star",
                verdict("/Header", "../Title", "FieldFilled(Title) And FieldFilled(/Persons/Name)"));
        actual.put("repeatable root reports, its own field read — the control",
                verdict("/Persons", "../Name", "FieldFilled(Name)"));
        actual.put("repeatable root reports, the fixed root read",
                verdict("/Persons", "../Name", "FieldFilled(Name) And FieldFilled(/Header/Title)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("fixed root reports, fixed root reads — the control", KernelLaws.ADMITTED);
        // A STAR is an AGGREGATE, which the parser records as `WILDCARD` and never as `ITERATION`, so the
        // disagreement `checkVRefs` looks for is not what a starred read creates.
        expected.put("fixed root reports, repeatable root read with a STAR", KernelLaws.ADMITTED);
        // …and the unstarred read, which DOES record `ITERATION`, never survives to be compared: the
        // wildcard gate refuses a per-row read of an extent the error field's own path does not enter.
        expected.put("fixed root reports, repeatable root read WITHOUT a star", "MVK_NO_WILDCARD");
        expected.put("repeatable root reports, its own field read — the control", KernelLaws.ADMITTED);
        expected.put("repeatable root reports, the fixed root read", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "an iteration the error field's own root does not carry");
    }
}
