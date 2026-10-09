package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * Whether a rule may read a REPEATABLE ROOT group's field while reporting on another root's, and what A12 asks
 * of the error field once a read makes the rule iterate.
 *
 * <p>{@code CheckIdNameRefImpl.setIndexOfCurrentLevel} records {@code WILDCARD} for a starred read and
 * {@code ITERATION} only for an UNSTARRED read of a repeatable level. {@code IterationChecker} then refuses a
 * condition with {@code MVK_ERROR_FIELD_AND_ITERATION_INVALID} when some reference iterates a level the error
 * field's own references do not — through {@code checkVRefs} when only the error field's root group names it,
 * through {@code checkIteration4NotOnlyVRefs} when the field or a containing non-root group does.
 *
 * <p><b>Corrected 2026-10-09.</b> The first grid concluded that the class had no A12 carrier, because a per-row
 * read outside the error field's extent is refused by the wildcard gate first. That half holds, but every one of
 * its rows also read the error field PER ROW, so the error field always iterated and the disagreement could not
 * appear. A star on the error field's own reference, beside a per-row sibling, reaches both arms. The
 * non-repeatable-root case is {@link IterationBindingLawsTest}.
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
        // A STAR is an AGGREGATE, recorded as `WILDCARD`: nothing iterates, so there is nothing to disagree with.
        expected.put("fixed root reports, repeatable root read with a STAR", KernelLaws.ADMITTED);
        // The unstarred read records `ITERATION`, but the wildcard gate refuses a per-row read of an extent the
        // error field's own path does not enter before the iteration check runs.
        expected.put("fixed root reports, repeatable root read WITHOUT a star", "MVK_NO_WILDCARD");
        expected.put("repeatable root reports, its own field read — the control", KernelLaws.ADMITTED);
        expected.put("repeatable root reports, the fixed root read", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "an iteration the error field's own root does not carry");
    }

    /** A repeatable root with two fields, so the error field and the per-row read can differ. */
    private static final String FLEET = DocumentModelDraft.named("fleet").locale("en_US")
            .rootGroup("Vehicles", v -> v.repeatable(50)
                    .stringField("Plate", f -> { })
                    .numberField("Mileage", n -> n.fractionDigits(0)))
            .rootGroup("Header", h -> h.stringField("Title", f -> { }))
            .build().toJson();

    /**
     * The rows the first grid could not hold: the error field named ONLY through a star while a sibling is read
     * per row. Both arms of the class answer, which is what reading the error field per row on every earlier
     * row had hidden.
     */
    @Test
    void aStarredErrorReferenceBesideAPerRowReadIsRefused() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the root group starred, a sibling per row",
                KernelLaws.verdictModel(FLEET, "/Vehicles", "../Plate",
                        "NumberOfFilledGroups(/Vehicles*) > 0 And FieldFilled(Mileage)"));
        actual.put("the error field starred, a sibling per row",
                KernelLaws.verdictModel(FLEET, "/Vehicles", "../Plate",
                        "AtLeastOneFieldFilled(/Vehicles*/Plate) And FieldFilled(Mileage)"));
        actual.put("the error field per row, the same star beside it — the control",
                KernelLaws.verdictModel(FLEET, "/Vehicles", "../Plate",
                        "AtLeastOneFieldFilled(/Vehicles*/Plate) And FieldFilled(Plate)"));
        actual.put("the error field never named — the referenced-ness control",
                KernelLaws.verdictModel(FLEET, "/Vehicles", "../Plate",
                        "Sum(/Vehicles*/Mileage) > 0 And FieldFilled(Mileage)"));

        Map<String, String> expected = new LinkedHashMap<>();
        // Only a root-group reference names the error field: `checkVRefs`.
        expected.put("the root group starred, a sibling per row", "MVK_ERROR_FIELD_AND_ITERATION_INVALID");
        // A direct reference names it: `checkIteration4NotOnlyVRefs`, at level 0.
        expected.put("the error field starred, a sibling per row", "MVK_ERROR_FIELD_AND_ITERATION_INVALID");
        expected.put("the error field per row, the same star beside it — the control", KernelLaws.ADMITTED);
        expected.put("the error field never named — the referenced-ness control", "MVK_ERROR_FIELD_NOT_REFERENCED");
        assertEquals(expected, actual, "a star names the error field without iterating it");
    }
}
