package io.github.mbackschat.a12.dm.rulekit.catalog;

import java.util.List;
import java.util.Map;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * One named validation idiom in the pattern catalog (FEATURE-GAPS G2) — the rule/computation-level peer of
 * {@link OperatorEntry}. A BA's recurring tasks (required-when, date-order, mutually-exclusive, at-least-one-of,
 * sum-of-line-items) re-risk the polarity + error-field traps every time they're re-derived; a pattern bakes
 * the <b>correct violation polarity</b> and a <b>valid error field</b> in, so scaffolding one is a parameter
 * fill, not a re-derivation.
 *
 * <p>The catalog is split like the operator catalog — <em>metadata is data</em> (id/summary/params/skeleton,
 * read by {@code dmtool patterns}), and the <em>condition is produced by code</em>. The built-in patterns
 * ({@link BuiltInPattern}) produce theirs through the <b>typed DSL</b> — correct by construction — so an
 * illegal condition is unrepresentable, not merely rejected. The interface is the seam a future
 * <em>data-template</em> user extension plugs into (a {@code ${param}} template impl, kernel-validated on load)
 * without reworking the verb.
 *
 * <p>Correctness is guarded the same way the operator catalog guards its examples: every pattern's
 * {@link #example()} is CI-instantiated and run through the real kernel ({@code PatternCatalogKernelTest}), so a
 * pattern that no longer scaffolds a kernel-valid rule cannot ship.
 */
public interface PatternEntry {

    /** The stable id, e.g. {@code "date-order"} — the token {@code patterns <id>} takes. */
    String id();

    /** A one-line description of the BA task this idiom serves. */
    String summary();

    /** What the scaffolded artifact is — a rule (v1) or, later, a computation. */
    PatternKind kind();

    /** The named parameters the idiom takes (field/value slots), in order. */
    List<PatternParam> params();

    /** The polarity note — what the scaffolded condition is TRUE on (always the violation, per A12). */
    String polarityNote();

    /** An illustrative {@code ${param}}-templated DSL skeleton (human/agent-facing; the typed build is authoritative). */
    String skeleton();

    /** Example args (param → value) — CI-instantiated through the kernel so the pattern can't go stale. */
    Map<String, String> example();

    /**
     * Instantiate the idiom against {@code model} with {@code args} (param name → value) — build the typed
     * condition, render it, and name the error field. Throws {@link IllegalArgumentException} if a required
     * arg is missing or a referenced path doesn't resolve.
     */
    Instantiation instantiate(BoundModel model, Map<String, String> args);

    /** The artifact a pattern scaffolds. */
    enum PatternKind {
        /** A validation rule. */
        RULE
    }

    /** A pattern parameter — a named slot the caller fills (a field path or a literal value). */
    record PatternParam(String name, Kind kind, String doc) {

        /** The kind of value a parameter slot accepts (drives the {@code patterns <id>} help + validation). */
        public enum Kind {
            /** Any field path. */
            FIELD,
            /** A NUMBER field path. */
            NUMBER_FIELD,
            /** A DATE field path. */
            DATE_FIELD,
            /** A field path whose value is compared for equality (STRING or ENUMERATION). */
            EQUATABLE_FIELD,
            /** A flattened repetition-list path, the {@code Group*}{@code /field} form. */
            FIELD_FLATTEN,
            /** A comma-separated list of field paths. */
            FIELD_LIST,
            /** A literal value (e.g. the value an {@code EQUATABLE_FIELD} is compared against). */
            VALUE
        }
    }

    /**
     * The result of {@link #instantiate} — the rule-spec parts an agent reviews and {@code rule add}s (or that
     * {@code patterns <id> --apply} persists directly): the rendered violation {@code condition}, the chosen
     * {@code errorField} (a referenced field in the condition's scope), and a suggested error {@code code}.
     */
    record Instantiation(String condition, String errorField, String suggestedCode) {
    }
}
