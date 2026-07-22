package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ChangeKind;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.Delta;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementKind;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ElementDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.MountOptions;
import io.github.mbackschat.a12.dm.modelkit.SortOrder;

/** Authored groups and mixed element order are first-class semantic-diff state. */
class ModelDiffGroupTest {

    private static final String LINES = "/Order/Lines";
    private static final String DETAILS = "/Order/Details";

    private static final String SHARED_MODEL = DocumentModelDraft.named("shared-address").locale("en_US")
            .rootGroup("Address", address -> address.field("City", FieldKind.STRING))
            .build().toJson();

    private static final ModelResolver RESOLVER = reference -> "shared-address".equals(reference)
            ? SHARED_MODEL : null;

    @Test
    void detectsGroupAdditionAndRemovalWithoutAChildrenSummaryDelta() {
        String withoutLines = DocumentModelDraft.named("orders").locale("en_US")
                .rootGroup("Order", order -> order.field("Number", FieldKind.STRING))
                .build().toJson();
        String withLines = DocumentModelDraft.named("orders").locale("en_US")
                .rootGroup("Order", order -> order.field("Number", FieldKind.STRING)
                        .group("Lines", lines -> lines.field("Sku", FieldKind.STRING)))
                .build().toJson();

        ElementChange added = groupChange(ModelDiff.between(withoutLines, withLines), LINES);
        assertEquals(ChangeKind.ADDED, added.change());
        assertEquals(ModelDiff.Risk.MEDIUM, added.risk());
        assertEquals(ChangeKind.REMOVED, groupChange(ModelDiff.between(withLines, withoutLines), LINES).change());
        assertFalse(added.deltas().stream().anyMatch(delta -> delta.aspect().equals("children")),
                () -> "child structure is reported by its actual elements, not a duplicate group delta: " + added);
    }

    @Test
    void classifiesARepetitionIncreaseAsLooseningAndADecreaseAsTightening() {
        ElementChange increased = groupChange(ModelDiff.between(linesModel(5, "Sku", SortOrder.ASC),
                linesModel(10, "Sku", SortOrder.ASC)), LINES);
        assertEquals("5", delta(increased, "repeatability").before());
        assertEquals("10", delta(increased, "repeatability").after());
        assertEquals(ModelDiff.Risk.HIGH, increased.risk());
        assertTrue(increased.reasons().contains("CONSTRAINT_LOOSENED"), () -> "reasons: " + increased.reasons());

        ElementChange decreased = groupChange(ModelDiff.between(linesModel(10, "Sku", SortOrder.ASC),
                linesModel(5, "Sku", SortOrder.ASC)), LINES);
        assertEquals(ModelDiff.Risk.MEDIUM, decreased.risk());
        assertTrue(decreased.reasons().contains("CONSTRAINT_TIGHTENED"), () -> "reasons: " + decreased.reasons());
    }

    @Test
    void detectsIndexAndOrderedSortChanges() {
        ElementChange index = groupChange(ModelDiff.between(linesModel(5, "Sku", SortOrder.ASC),
                linesModel(5, "Batch", SortOrder.ASC)), LINES);
        assertEquals("Sku", delta(index, "indexField").before());
        assertEquals("Batch", delta(index, "indexField").after());
        assertEquals(ModelDiff.Risk.MEDIUM, index.risk());

        ElementChange sort = groupChange(ModelDiff.between(linesModel(5, "Sku", SortOrder.ASC),
                linesModel(5, "Sku", SortOrder.DESC)), LINES);
        assertEquals("Sku:ASC", delta(sort, "sort[0]").before());
        assertEquals("Sku:DESC", delta(sort, "sort[0]").after());
        assertEquals(ModelDiff.Risk.MEDIUM, sort.risk());
    }

    @Test
    void detectsMountAliasAndEveryMountOption() {
        String base = mountedHost("primary", MountOptions.single());
        String head = mountedHost("fallback", MountOptions.repeatable(3)
                .excludingRules().excludingComputations().asModelRoot());

        ElementChange mount = groupChange(ModelDiff.between(base, head, RESOLVER), "/Order/Address");
        assertEquals("primary", delta(mount, "mount.alias").before());
        assertEquals("fallback", delta(mount, "mount.alias").after());
        assertEquals("1", delta(mount, "repeatability").before());
        assertEquals("3", delta(mount, "repeatability").after());
        assertEquals("false", delta(mount, "mount.excludeRules").before());
        assertEquals("true", delta(mount, "mount.excludeRules").after());
        assertEquals("false", delta(mount, "mount.excludeComputations").before());
        assertEquals("true", delta(mount, "mount.excludeComputations").after());
        assertEquals("SINGLE_RG", delta(mount, "mount.includeLevel").before());
        assertEquals("MODEL_ROOT", delta(mount, "mount.includeLevel").after());
        assertEquals(ModelDiff.Risk.HIGH, mount.risk(), "the higher repetition cap is a loosening");
    }

    @Test
    void detectsGroupMetadataAtLowRisk() {
        String base = describedLines("Order lines", "Internal base", "External base", "stable");
        String head = describedLines("Purchase lines", "Internal head", "External head", "reviewed");

        ElementChange group = groupChange(ModelDiff.between(base, head), LINES);
        assertEquals("Order lines", delta(group, "label.en_US").before());
        assertEquals("Purchase lines", delta(group, "label.en_US").after());
        assertEquals("Internal base", delta(group, "internalDescription.en_US").before());
        assertEquals("External head", delta(group, "externalDescription.en_US").after());
        assertEquals("stable", delta(group, "annotation.lifecycle").before());
        assertEquals("reviewed", delta(group, "annotation.lifecycle").after());
        assertEquals(ModelDiff.Risk.LOW, group.risk());
        assertEquals(List.of("METADATA_CHANGED"), group.reasons());
    }

    @Test
    void detectsAGroupMovingRelativeToAFieldInTheAuthoredMixedOrder() {
        ModelDiff diff = ModelDiff.between(orderedChildren(true, false), orderedChildren(false, false));

        ElementChange moved = diff.changes().stream()
                .filter(change -> change.change() == ChangeKind.REORDERED)
                .findFirst().orElseThrow(() -> new AssertionError("missing mixed-order change: " + diff.changes()));
        assertEquals(ElementKind.GROUP, moved.element());
        assertEquals(DETAILS, moved.id());
        assertEquals("1", delta(moved, "position").before());
        assertEquals("2", delta(moved, "position").after());
    }

    @Test
    void addingASiblingDoesNotInventAReorderOrModifyItsParent() {
        ModelDiff diff = ModelDiff.between(orderedChildren(true, false), orderedChildren(true, true));

        assertFalse(diff.changes().stream().anyMatch(change -> change.change() == ChangeKind.REORDERED),
                () -> "an inserted sibling preserves the common elements' relative order: " + diff.changes());
        assertFalse(diff.changes().stream().anyMatch(change -> change.element() == ElementKind.GROUP
                        && change.id().equals("/Order") && change.change() == ChangeKind.MODIFIED),
                () -> "the new field is the structural delta; its parent must not also say children changed: "
                        + diff.changes());
    }

    private static String linesModel(int repetitions, String indexField, SortOrder order) {
        return DocumentModelDraft.named("orders").locale("en_US")
                .rootGroup("Order", root -> root.group("Lines", lines -> lines.repeatable(repetitions)
                        .field("Sku", FieldKind.STRING)
                        .field("Batch", FieldKind.STRING)
                        .indexField(indexField)
                        .sortField("Sku", order)))
                .build().toJson();
    }

    private static String describedLines(String label, String internal, String external, String lifecycle) {
        return DocumentModelDraft.named("orders").locale("en_US")
                .rootGroup("Order", root -> root.group("Lines", lines -> lines
                        .label("en_US", label)
                        .internalDescription("en_US", internal)
                        .externalDescription("en_US", external)
                        .annotation("lifecycle", lifecycle)
                        .field("Sku", FieldKind.STRING)))
                .build().toJson();
    }

    private static String mountedHost(String mountedAlias, MountOptions options) {
        var document = io.github.mbackschat.a12.dm.modelkit.ModelDocument.read(
                DocumentModelDraft.named("orders").locale("en_US")
                        .rootGroup("Order", order -> order.field("Number", FieldKind.STRING))
                        .build().toJson());
        return document.addInclude("primary", "shared-address")
                .addInclude("fallback", "shared-address")
                .mount("/Order", "Address", mountedAlias, options)
                .toJson();
    }

    private static String orderedChildren(boolean groupFirst, boolean addFrontSibling) {
        return DocumentModelDraft.named("orders").locale("en_US")
                .rootGroup("Order", order -> {
                    if (addFrontSibling) {
                        order.field("Reference", FieldKind.STRING);
                    }
                    if (groupFirst) {
                        order.group("Details", details -> details.field("Note", FieldKind.STRING));
                    }
                    order.field("Status", FieldKind.STRING);
                    if (!groupFirst) {
                        order.group("Details", details -> details.field("Note", FieldKind.STRING));
                    }
                })
                .build().toJson();
    }

    private static ElementChange groupChange(ModelDiff diff, String path) {
        return diff.changes().stream()
                .filter(change -> change.element() == ElementKind.GROUP && change.id().equals(path))
                .findFirst().orElseThrow(() -> new AssertionError("missing group change for " + path + ": " + diff.changes()));
    }

    private static Delta delta(ElementChange change, String aspect) {
        return change.deltas().stream().filter(candidate -> candidate.aspect().equals(aspect)).findFirst()
                .orElseThrow(() -> new AssertionError("missing " + aspect + " delta: " + change.deltas()));
    }
}
