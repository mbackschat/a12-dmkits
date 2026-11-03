package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * Instrument 4 of the constraint-mining discipline ({@code CLAUDE.md}): the structural checks that
 * fire <em>before</em> {@code checkConsistency} — specifically the kernel's <strong>write gate</strong>,
 * {@code BasicConsistencyCheck}, which {@code serialize()} runs and aborts on (the F6 DM-JSON write
 * path's output contract). Its {@code BasicDocumentModelConsistencyCategory} enum is the enumerable
 * source; this test fails loudly if a kernel upgrade adds a category we haven't classified.
 *
 * <p>Classification mirrors the MVK ledger's buckets: {@code pre-checked} (a rulekit authoring
 * builder already guarantees it) / {@code n/a-structural} (a model-structure concern rulekit does
 * not author — it only adds rules/computations to an already-valid model).
 *
 * <p>The deserialize tier (Jackson binding: malformed JSON, unknown type discriminators, and the
 * locale-format check — {@code en_us} → "Invalid locale format") is documented in KERNEL-FINDINGS
 * but not enum-enumerable, so it is not locked here.
 */
class SerializerTierParityTest {

    private static final String ENUM =
            "com.mgmtp.a12.kernel.md.serializer.model.internal.service.consistency.BasicDocumentModelConsistencyCategory";

    /** Every write-gate category, classified by how rulekit's write path (F6) satisfies it. */
    private static final Map<String, String> CLASSIFIED = Map.ofEntries(
            Map.entry("ID_EMPTY", "n/a-structural: rulekit derives element ids from model paths"),
            Map.entry("NAME_EMPTY", "n/a-structural: names come from the model"),
            Map.entry("ERROR_ENTITY_EMPTY", "pre-checked: Rule.Builder requires an error field"),
            Map.entry("ERROR_CODE_EMPTY", "pre-checked: Rule.Builder requires a code"),
            Map.entry("ERROR_CONDITION_EMPTY", "pre-checked: Rule.Builder requires a condition"),
            Map.entry("COMPUTED_FIELD_EMPTY", "pre-checked: Computation is built from a computed field handle"),
            Map.entry("COMPUTATION_OPERATION_EMPTY", "pre-checked: Computation requires calc(...)"),
            Map.entry("ENUM_TYPE_DUPLICATE_VALUE", "n/a-structural: rulekit does not author enum types"),
            Map.entry("ENUM_TYPE_DUPLICATE_CATEGORIES", "n/a-structural: rulekit does not author enum types"),
            Map.entry("LOCALIZED_TEXT_ELEMENT_NULL_PROBLEM", "pre-checked: LocalizedText.checkDeclared (every declared locale)"),
            Map.entry("LOCALIZED_TEXT_TYPEDEF_NULL_PROBLEM", "n/a-structural: rulekit does not author typedefs"),
            Map.entry("LOCALIZED_TEXT_UNIQUENESS_CRITERION_NULL_PROBLEM", "n/a-structural: rulekit does not author uniqueness criteria"),
            Map.entry("INDEX_FIELD_NAME_EMPTY", "n/a-structural: index fields are model structure (7f)"),
            Map.entry("SORT_FIELD_NAME_EMPTY", "n/a-structural: sort fields are model structure"),
            Map.entry("TYPEDEF_TYPE_REF_EMPTY", "n/a-structural: rulekit does not author typedefs"),
            Map.entry("TYPEDEF_ID_EMPTY", "n/a-structural: rulekit does not author typedefs"),
            Map.entry("TYPEDEF_NAME_EMPTY", "n/a-structural: rulekit does not author typedefs"));

    @Test
    void everyWriteGateCategoryIsClassified() {
        Class<?> enumClass;
        try {
            enumClass = Class.forName(ENUM);
        } catch (ClassNotFoundException e) {
            throw new TestAbortedException("write-gate enum not on test classpath: " + ENUM);
        }
        Set<String> unclassified = new LinkedHashSet<>();
        for (Object constant : enumClass.getEnumConstants()) {
            String name = ((Enum<?>) constant).name();
            if (!CLASSIFIED.containsKey(name)) {
                unclassified.add(name);
            }
        }
        assertTrue(unclassified.isEmpty(), () -> "BasicConsistencyCheck added write-gate categories rulekit "
                + "has not classified (the F6 write path must satisfy or explicitly mark them): " + unclassified);
    }
}
