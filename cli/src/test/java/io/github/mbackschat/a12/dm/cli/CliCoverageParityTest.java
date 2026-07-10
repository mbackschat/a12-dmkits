package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import io.github.mbackschat.a12.dm.edit.EditSession;
import org.junit.jupiter.api.Test;

/**
 * The <b>CLI-coverage parity guard</b> — the mechanical net for the class of gap that let the model's
 * <b>base year</b> ship in the library ({@code EditSession.setBaseYear}) with no {@code dmtool} path for years:
 * <em>a capability that exists in one component (the modelkit/{@code dm.edit} authoring facade) with no guard
 * forcing the component that should expose it (the CLI) to cover it.</em>
 *
 * <p>{@link EditSession} is the unified authoring facade the CLI's {@code apply}/{@code batch} + the standalone
 * verbs all drive. This test reflectively enumerates <b>every public mutator</b> on it and requires each to be
 * <b>classified</b> — either mapped to the CLI verb/apply-op that covers it, or explicitly marked {@code OPEN:}
 * (a tracked {@code FEATURE-GAPS} gap) or {@code EXCLUDED:} (intentionally not in the CLI, with a reason).
 * A NEW mutator with no classification <b>fails the build</b> — forcing the author to answer "does the CLI expose
 * this?" at the moment it is added, instead of a user discovering the gap months later (as happened with base-year).
 *
 * <p>This is the CLI peer of {@code adapter.laws.CatalogParityTest} (operator catalog ↔ fuzz coverage) and the
 * interpreter's renderer-modifier net — the same "enumerate the surface, classify every member, fail on
 * unclassified" pattern. It guards <b>method-level</b> coverage (a whole mutator with no CLI path); a mutator that
 * is polymorphic over entity kinds and covered for only SOME (e.g. {@code setLabel} covers a FIELD via
 * {@code field modify} but a GROUP/RULE label is the open G38/G39) is noted in its classification, not separately
 * enforced here.
 */
class CliCoverageParityTest {

    /**
     * Every {@link EditSession} public mutator → the CLI surface that covers it (or {@code OPEN:}/{@code EXCLUDED:}).
     * The VALUE documents where the coverage lives; the TEST enforces only that the map is COMPLETE (every mutator
     * classified) and not STALE (no classified name that no longer exists) — so a new mutator can't slip in uncovered.
     */
    private static final Map<String, String> COVERAGE = Map.ofEntries(
            // ── document config (modelInfo / modelConfig) — `config modify` + apply CONFIG_MODIFY keys ──
            Map.entry("setDecimalSeparator", "config modify --decimal-separator"),
            Map.entry("setTimeZone", "config modify --time-zone"),
            Map.entry("setConditionLanguage", "config modify --condition-language"),
            Map.entry("setModelComment", "config modify --comment"),
            Map.entry("clearModelComment", "config modify --clear-comment (G34)"),
            Map.entry("setModelAnnotation", "config modify --annotation / --role"),
            Map.entry("removeModelAnnotation", "config modify --remove-annotation / --clear-roles (G35)"),
            Map.entry("setModelLabel", "config modify --label"),
            Map.entry("setModelId", "model rename"),
            Map.entry("setBaseYear", "config modify --base-year"),
            Map.entry("setFieldRefByShortNameAllowed", "config modify --field-ref-by-short-name (G32)"),
            Map.entry("setSupportedCharacters", "config modify --supported-character"),
            Map.entry("addLocale", "config modify --add-locale"),
            Map.entry("removeLocale", "config modify --remove-locale"),
            // ── fields ──
            Map.entry("addField", "field add"),
            Map.entry("removeField", "field remove"),
            Map.entry("modifyField", "field modify"),
            Map.entry("renameField", "field rename"),
            Map.entry("moveField", "field move"),
            Map.entry("setFieldType", "field modify --type / typedef"),
            Map.entry("setRequiredness", "field modify --required/--optional/--required-if"),
            Map.entry("setRequiredErrorMessages", "field add/modify (requiredMessages in the field spec)"),
            Map.entry("setHelperText", "field add/modify (helperText in the field spec)"),
            Map.entry("setGlobal", "field add/modify (global in the field spec)"),
            Map.entry("setTransient", "field add/modify (transient in the field spec)"),
            // field/group/rule metadata (polymorphic): FIELDS via field modify (FieldSpec), GROUPS via group
            // modify (G38), RULES via rule modify + rule add + apply RULE_* (G39). A rule has no label slot, so
            // setLabel stays field/group only (correct by construction).
            Map.entry("setLabel", "field modify --label + group modify --label (rules have no label slot)"),
            Map.entry("setAnnotation", "field/group/rule modify --annotation (G39)"),
            Map.entry("removeAnnotation", "field/group/rule modify --remove-annotation (G39)"),
            Map.entry("setInternalDescription", "field/group/rule modify --internal-description (G39)"),
            Map.entry("setExternalDescription", "field/group/rule modify --external-description (G39)"),
            Map.entry("removeInternalDescription", "field modify (group/rule modify have no remove-description flag)"),
            Map.entry("removeExternalDescription", "field modify (group/rule modify have no remove-description flag)"),
            // ── groups ──
            Map.entry("addGroup", "group add"),
            Map.entry("addRepeatableGroup", "group add --repeatable"),
            Map.entry("addAttachmentGroup", "group attachment"),
            Map.entry("addMultiSelectGroup", "group multiselect"),
            Map.entry("removeGroup", "group remove"),
            Map.entry("renameGroup", "group rename"),
            Map.entry("moveGroup", "group move"),
            Map.entry("setRepeatable", "group modify --repeatable"),
            Map.entry("setNonRepeatable", "group modify --non-repeatable"),
            Map.entry("setIndexField", "group modify --index-field"),
            Map.entry("clearIndexField", "group modify --clear-index-field"),
            Map.entry("setSortFields", "group modify --sort-field"),
            Map.entry("clearSortFields", "group modify --clear-sort-fields"),
            // ── rules ──
            Map.entry("addRule", "rule add"),
            Map.entry("removeRule", "rule remove"),
            Map.entry("modifyRule", "rule modify"),
            Map.entry("renameRule", "rule rename"),
            Map.entry("modifyRuleMessages", "rule modify --message"),
            Map.entry("modifyRuleSeverity", "rule modify --severity"),
            // ── computations ──
            Map.entry("addComputation", "computation add"),
            Map.entry("removeComputation", "computation remove"),
            Map.entry("modifyComputation", "computation modify"),
            // ── type definitions ──
            Map.entry("addTypeDefinition", "typedef add"),
            Map.entry("addTypedefField", "typedef add (field)"),
            Map.entry("removeTypeDefinition", "typedef remove"),
            Map.entry("renameTypeDefinition", "typedef rename"),
            Map.entry("setTypeDefinitionType", "typedef modify"),
            Map.entry("extractTypeDefinition", "typedef extract"),
            Map.entry("inlineTypeDefinition", "typedef inline"),
            Map.entry("addImport", "typedef import"),
            Map.entry("removeImport", "typedef unimport"),
            // ── includes ──
            Map.entry("addInclude", "include add"),
            Map.entry("removeInclude", "include remove"),
            Map.entry("mount", "include add (mount a declared include)"),
            Map.entry("inlineInclude", "include inline"),
            Map.entry("setIncludeReference", "include set-reference"),
            Map.entry("renameInclude", "include rename"),
            // ── terminal (not a CLI-authored mutation) ──
            Map.entry("validate", "EXCLUDED: terminal validate-and-bind, not an authoring op"));

    /** Every public, non-static mutator method NAME on {@link EditSession} (a fluent mutator returns EditSession). */
    private static Set<String> editSessionMutators() {
        return java.util.Arrays.stream(EditSession.class.getMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers()))
                .filter(m -> !m.isSynthetic() && m.getDeclaringClass() == EditSession.class)
                .filter(m -> m.getReturnType() == EditSession.class)   // the fluent-mutator signature
                .map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyEditSessionMutatorIsClassifiedForCliCoverage() {
        Set<String> mutators = editSessionMutators();
        Set<String> unclassified = new TreeSet<>(mutators);
        unclassified.removeAll(COVERAGE.keySet());
        assertTrue(unclassified.isEmpty(), () ->
                "EditSession mutators with NO CLI-coverage classification — the base-year gap class. For each, add "
                        + "the CLI verb/flag (or apply-op) that exposes it and map it here, OR record it in "
                        + "FEATURE-GAPS as an OPEN: gap, OR mark it EXCLUDED: with a reason:\n  "
                        + String.join("\n  ", unclassified));
    }

    @Test
    void coverageMapHasNoStaleEntries() {
        Set<String> mutators = editSessionMutators();
        Set<String> stale = new TreeSet<>(COVERAGE.keySet());
        stale.removeAll(mutators);
        assertTrue(stale.isEmpty(), () ->
                "classified names that are no longer EditSession mutators (renamed/removed — fix the map):\n  "
                        + String.join("\n  ", stale));
    }

    @Test
    void openGapsAreTrackedNotForgotten() {
        Set<String> open = COVERAGE.entrySet().stream()
                .filter(e -> e.getValue().startsWith("OPEN:"))
                .map(e -> e.getKey() + " → " + e.getValue())
                .collect(Collectors.toCollection(TreeSet::new));
        // Not an assertion of emptiness — OPEN gaps are legitimately tracked in FEATURE-GAPS; this prints
        // the current set so a reviewer sees exactly which library mutators still lack a CLI path.
        System.out.println("[cli-coverage] EditSession mutators still lacking a CLI path (tracked in FEATURE-GAPS):\n  "
                + (open.isEmpty() ? "(none)" : String.join("\n  ", open)));
    }
}
