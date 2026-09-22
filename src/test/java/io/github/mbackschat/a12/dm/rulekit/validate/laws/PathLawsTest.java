package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §10 — path / wildcard laws (accept/reject, parse + consistency tier). Uses the
 * nested-repeatable {@code project} fixture (Milestones ▸ Tasks), the structure needed to exercise the
 * wildcard-propagation rule.
 *
 * <p>This file is also the worked example for DEV-API-GAPS DG59, because its project rows are where the
 * two legitimate reasons for a raw-String condition genuinely live. Every one was attempted typed on
 * 2026-09-22 and the render compared: four are authored typed and the three that remain carry a
 * {@link RawCondition} naming which reason applies and what the typed surface produces instead. Read a
 * bare condition string in this file as unexamined, not as sanctioned.
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

    // The ANCESTOR-group shape behind the retired "bare names resolve upward" claim (feedback F9, the
    // 2026-06-05 probe): a rule ON /Subscription/Addons referencing bare [Tier], a field of the PARENT group.
    private static final String SUBSCRIPTION = DocumentModelDraft.named("subscription-ancestor").locale("en_US")
            .rootGroup("Subscription", s -> s
                    .field("Tier", FieldKind.STRING)
                    .group("Addons", a -> a.field("MonthlyFee", FieldKind.STRING)))
            .build().toJson();

    @Test
    void aBareAncestorFieldNameResolvesViaTheShortNameFallback() {
        // What the June-5 probe actually observed: `Tier` is model-wide UNIQUE and the draft default sets
        // `fieldRefByShortNameAllowed` on → the SHORT-NAME fallback resolves it (not an ancestor walk).
        KernelLaws.acceptsModel(SUBSCRIPTION, "/Subscription/Addons", "../../Tier", "FieldFilled(Tier)");
    }

    @Test
    void aBareAncestorFieldNameIsRejectedWithTheFlagOff_thereIsNoAncestorWalk() {
        // The decisive arm: with the fallback off, the declaring-group candidate /Subscription/Addons/Tier
        // misses and NOTHING walks the ancestors → MVK_INVALID_ENTITY. An ancestor field needs explicit
        // `../` navigation or the flag-gated unique-short-name fallback (KERNEL-FINDINGS §10 / IF119).
        KernelLaws.rejectsModel(ModelDocument.read(SUBSCRIPTION).setFieldRefByShortNameAllowed(false).toJson(),
                "/Subscription/Addons", "../../Tier", "FieldFilled(Tier)", "MVK_INVALID_ENTITY");
    }

    @Test
    void anOuterWildcardRequiresEveryRepeatableGroupBelowItToAlsoWildcard() {
        // outer * on Milestones but a plain (non-*) Tasks below it → rejected. Authored TYPED on purpose:
        // the builder constructs this shape happily, so the row also shows that rulekit does NOT pre-empt
        // the kernel's wildcard-propagation rule client-side — the refusal measured here is the kernel's.
        BoundModel m = Fixtures.project();
        KernelLaws.rejectsProject("/Project", "../ProjectName",
                m.field("/Project/ProjectName").filled()
                        .and(Dsl.sum(m.flatten("/Project/Milestones*/Tasks/Effort")).gt(0)),
                "MVK_NO_WILDCARD");
    }

    @Test
    void wildcardingEveryRepeatableLevelIsAccepted() {
        // both repeatable levels starred → the flattened nested list is valid. Authored TYPED, unlike its
        // siblings here: this is the one shape in this family the builder can represent, so it is the row that
        // shows the others are raw by necessity rather than by habit (DEV-API-GAPS DG59).
        BoundModel m = Fixtures.project();
        KernelLaws.acceptsProject("/Project", "../ProjectName",
                m.field("/Project/ProjectName").filled()
                        .and(Dsl.sum(m.flatten("/Project/Milestones*/Tasks*/Effort")).gt(0)));
    }

    @Test
    void aSingleWildcardOverTheOuterRepeatableAggregatesItsOwnField() {
        // starring just the outer repeatable to aggregate its own (non-nested) field is fine
        BoundModel m = Fixtures.project();
        KernelLaws.acceptsProject("/Project", "../ProjectName",
                m.field("/Project/ProjectName").filled()
                        .and(Dsl.sum(m.flatten("/Project/Milestones*/Budget")).gt(0)));
    }

    @Test
    void aStarOnTheRelativeUpNavigationIsRejected_useAbsolute() {
        // "In a relative path with ..-notation, the '..' parts may not use the '*'." Starring the ..
        // itself (to reach all repetitions of an ancestor repeatable) → rejected; use the absolute form.
        KernelLaws.rejectsProject("/Project/Milestones/Tasks", "../Effort",
                RawCondition.shapeErased("FieldFilled(Effort) And Sum(..*/Budget) > 0",
                        "flatten refuses a relative path outright — \"flatten takes a MODEL-ABSOLUTE path\" — "
                                + "so a star on '..' has no typed form at all, measured 2026-09-22"),
                "MVK_INVALID_WILDCARD_REL");
        // a star on a *named* segment (not the ..) is fine, and the absolute form expresses the intent
        KernelLaws.acceptsProject("/Project/Milestones/Tasks", "../Effort",
                RawCondition.concreteSyntax("FieldFilled(Effort) And Sum(/Project/Milestones*/Budget) > 0",
                        "the ABSOLUTE spelling is this row's subject, and render(group, …) emits "
                                + "Sum(../../Milestones*/Budget) instead: path style is a renderer-wide "
                                + "choice, so the mixed relative-error-field / absolute-aggregate text a "
                                + "human writes here is unreachable typed (measured 2026-09-22, DG61)"));
    }

    @Test
    void aFieldNamedLikeAKeywordMustBeSingleQuotedInAPath() {
        // the project fixture has a field literally named "Date" (a language keyword)
        // unquoted → the parser reads the keyword and rejects
        KernelLaws.rejectsProject("/Project", "../Date",
                RawCondition.concreteSyntax("FieldFilled(Date)",
                        "the UNQUOTED spelling is the subject, and it is exactly what the typed surface "
                                + "will not produce: m.field(\"/Project/Date\").filled() renders "
                                + "FieldFilled('Date'), i.e. the accepted arm below (measured 2026-09-22)"),
                "MVK_UNEXPECTED_TOKEN");
        // single-quoting it makes it a plain identifier → accepted; the typed surface quotes it for you,
        // which is what makes the rejected arm above unreachable typed rather than merely inconvenient
        KernelLaws.acceptsProject("/Project", "../Date", Fixtures.project().field("/Project/Date").filled());
    }
}
