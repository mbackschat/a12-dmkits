package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §10 — path / wildcard laws (accept/reject, parse + consistency tier). Uses the
 * nested-repeatable {@code project} fixture (Milestones ▸ Tasks), the structure needed to exercise the
 * wildcard-propagation rule.
 */
class PathLawsTest {

    // Cross-group short-name resolution (IF119): a catalog with two sibling subgroups. `Sku`/`Region` are
    // UNIQUE short names; a second model gives both subgroups a field named `Code` (an AMBIGUOUS short name).
    private static final String UNIQUE = DocumentModelDraft.named("catalog-shortnames").locale("en_US")
            .rootGroup("Catalog", c -> c
                    .group("Warehouse", w -> w.field("Sku", FieldKind.STRING))
                    .group("Store", s -> s.field("Region", FieldKind.STRING)))
            .build().toJson();
    private static final String UNIQUE_FLAG_OFF =
            ModelDocument.read(UNIQUE).setFieldRefByShortNameAllowed(false).toJson();
    private static final String AMBIGUOUS = DocumentModelDraft.named("catalog-ambiguous").locale("en_US")
            .rootGroup("Catalog", c -> c
                    .group("Warehouse", w -> w.field("Code", FieldKind.STRING))
                    .group("Store", s -> s.field("Code", FieldKind.STRING)))
            .build().toJson();

    @Test
    void aUniqueCrossGroupShortNameResolvesWhenTheFlagIsOn() {
        // rule in /Catalog references bare [Sku] (a field in the sibling /Catalog/Warehouse); the model's
        // fieldRefByShortNameAllowed (default on) lets it resolve model-wide since the name is unique.
        KernelLaws.acceptsModel(UNIQUE, "/Catalog", "../Warehouse/Sku", "FieldFilled(Sku)");
    }

    @Test
    void aCrossGroupShortNameIsRejectedWhenTheFlagIsOff() {
        // with the flag off, the bare cross-group name has no model-wide fallback → the declaring-group
        // candidate /Catalog/Sku does not exist → MVK_INVALID_ENTITY.
        KernelLaws.rejectsModel(UNIQUE_FLAG_OFF, "/Catalog", "../Warehouse/Sku", "FieldFilled(Sku)",
                "MVK_INVALID_ENTITY");
    }

    @Test
    void anAmbiguousShortNameIsRejectedAsNotUnique() {
        // `Code` exists in BOTH subgroups → the short name does not suffice → MVK_FIELDNAME_NOT_UNIQUE
        // (the kernel rejects; it never picks one). This is why the interpreter resolves ONLY a unique match.
        KernelLaws.rejectsModel(AMBIGUOUS, "/Catalog", "../Warehouse/Code", "FieldFilled(Code)",
                "MVK_FIELDNAME_NOT_UNIQUE");
    }

    @Test
    void anOuterWildcardRequiresEveryRepeatableGroupBelowItToAlsoWildcard() {
        // outer * on Milestones but a plain (non-*) Tasks below it → rejected
        KernelLaws.rejectsProject("/Project", "../ProjectName",
                "FieldFilled(ProjectName) And Sum(Milestones*/Tasks/Effort) > 0", "MVK_NO_WILDCARD");
    }

    @Test
    void wildcardingEveryRepeatableLevelIsAccepted() {
        // both repeatable levels starred → the flattened nested list is valid
        KernelLaws.acceptsProject("/Project", "../ProjectName",
                "FieldFilled(ProjectName) And Sum(Milestones*/Tasks*/Effort) > 0");
    }

    @Test
    void aSingleWildcardOverTheOuterRepeatableAggregatesItsOwnField() {
        // starring just the outer repeatable to aggregate its own (non-nested) field is fine
        KernelLaws.acceptsProject("/Project", "../ProjectName",
                "FieldFilled(ProjectName) And Sum(Milestones*/Budget) > 0");
    }

    @Test
    void aStarOnTheRelativeUpNavigationIsRejected_useAbsolute() {
        // "In a relative path with ..-notation, the '..' parts may not use the '*'." Starring the ..
        // itself (to reach all repetitions of an ancestor repeatable) → rejected; use the absolute form.
        KernelLaws.rejectsProject("/Project/Milestones/Tasks", "../Effort",
                "FieldFilled(Effort) And Sum(..*/Budget) > 0", "MVK_INVALID_WILDCARD_REL");
        // a star on a *named* segment (not the ..) is fine, and the absolute form expresses the intent
        KernelLaws.acceptsProject("/Project/Milestones/Tasks", "../Effort",
                "FieldFilled(Effort) And Sum(/Project/Milestones*/Budget) > 0");
    }

    @Test
    void aFieldNamedLikeAKeywordMustBeSingleQuotedInAPath() {
        // the project fixture has a field literally named "Date" (a language keyword)
        // unquoted → the parser reads the keyword and rejects
        KernelLaws.rejectsProject("/Project", "../Date", "FieldFilled(Date)", "MVK_UNEXPECTED_TOKEN");
        // single-quoting it makes it a plain identifier → accepted
        KernelLaws.acceptsProject("/Project", "../Date", "FieldFilled('Date')");
    }
}
