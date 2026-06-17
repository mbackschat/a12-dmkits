package io.github.mbackschat.a12.dm.cli;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The semantic kind of a verb parameter (CLI-CONFORMANCE-SPEC). Tagging a picocli {@code @Parameters}/
 * {@code @Option} field with {@code @ParamKind(...)} does double duty:
 *
 * <ul>
 *   <li><b>self-description</b> — the {@code manifest} surfaces the kind, so a cold agent learns "this arg
 *       is a FIELD_PATH that must exist" vs "a NEW_NAME you choose", instead of guessing from the label;</li>
 *   <li><b>conformance fuzzing</b> — the diagnostic-conformance gate reads it to synthesize a domain error
 *       (feed an entity-ref param a {@link Kind#bogusValue()} → assert a structured not-found refusal) and
 *       to fill the other required params with a {@link Kind#benignDefault()}.</li>
 * </ul>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ParamKind {

    Kind value();

    /** The semantic kinds. {@code entityRef} kinds must reference an existing model element. */
    public enum Kind {
        // --- references to existing model elements (a bogus value must yield a structured not-found) ----
        FIELD_PATH(true, "/__rk_absent__/__field__", null),
        GROUP_PATH(true, "/__rk_absent__/__group__", null),
        RULE_PATH(true, "/__rk_absent__/__rule__", null),
        COMPUTATION_PATH(true, "/__rk_absent__/__computation__", null),
        TYPEDEF_ID(true, "__rk_absent_typedef__", null),
        INCLUDE_ALIAS(true, "__rk_absent_alias__", null),
        // a reference to ANY describable element by name-path (field/group/rule) or type-def id
        ELEMENT_REF(true, "/__rk_absent__/__element__", null),

        // --- values the caller supplies (not references; the fuzzer fills them benignly) -----------------
        NEW_NAME(false, null, "RkConformanceProbe"),
        FIELD_KIND(false, null, "NUMBER"),
        SCALAR(false, null, "1"),
        FILE(false, null, null),
        FREEFORM(false, null, null);

        private final boolean entityRef;
        private final String bogusValue;
        private final String benignDefault;

        Kind(boolean entityRef, String bogusValue, String benignDefault) {
            this.entityRef = entityRef;
            this.bogusValue = bogusValue;
            this.benignDefault = benignDefault;
        }

        /** Whether this param must reference an existing model element (so a bogus value is a domain error). */
        boolean entityRef() {
            return entityRef;
        }

        /** A syntactically-valid but non-existent value for an {@code entityRef} kind (the fuzzer's probe). */
        String bogusValue() {
            return bogusValue;
        }

        /** A benign value the fuzzer can pass for a non-reference required param; {@code null} if none fits. */
        String benignDefault() {
            return benignDefault;
        }
    }
}
