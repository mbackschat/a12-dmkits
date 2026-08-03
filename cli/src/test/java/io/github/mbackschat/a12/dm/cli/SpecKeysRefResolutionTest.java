package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;

/**
 * The closed-keys walk must follow a {@code $ref}, and must refuse one it cannot resolve.
 *
 * <p>{@code $ref} was not handled at all, and the failure mode was the worst available: a schema node consisting
 * of one produced "nothing to enforce", so the walk stopped at the reference. Since a {@code $ref} is how every
 * non-trivial schema factors out its item shape, that silently disabled enforcement below the reference in <b>9
 * of the 28</b> bundled schemas. It read as coverage — {@code DataSchemaConformanceTest} passed while
 * {@code workspace-list} emitted three keys its own {@code additionalProperties:false} did not declare, and
 * {@code model-diff}'s delta values were unchecked. Resolving refs surfaced both on the first run.
 *
 * <p>Input validation was unaffected: {@code add}/{@code apply}/{@code batch} use no {@code $ref}. That is luck,
 * not design, which is why the unresolvable case now throws rather than being tolerated.
 */
class SpecKeysRefResolutionTest {

    private static final ObjectMapper M = new ObjectMapper();

    /**
     * The walk descends through {@code models.items → $defs/model} and enforces that subtree's closed key set.
     * Before ref resolution this input was clean, because nothing below {@code models} was being looked at.
     */
    @Test
    void anUndeclaredKeyInsideARefdSubtreeIsReported() throws Exception {
        JsonNode data = M.readTree("""
                { "root": "/tmp", "recursive": false, "count": 1,
                  "models": [ { "id": "m", "path": "m.json", "modelType": "document", "abstract": false,
                                "superTypes": [], "subTypes": [], "locales": [], "includes": [],
                                "typedefImports": [], "providesTypedefs": [], "groups": 1, "fields": 2,
                                "bogusKey": true } ] }""");

        List<Diagnostic> violations = SpecKeys.forSchema("workspace-list").validate(data);

        assertEquals(1, violations.size(), () -> "exactly the one undeclared key: " + violations);
        assertEquals("models[]/bogusKey", violations.get(0).where().arg().orElse(""),
                () -> "and it is located inside the $ref'd item, not at the root: " + violations);
    }

    /** An unresolvable {@code $ref} is a broken schema, so it fails loudly rather than quietly enforcing nothing. */
    @Test
    void anUnresolvableRefFailsLoudly() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> SpecKeys.forSchema("broken-ref"));

        assertTrue(thrown.getMessage().contains("#/$defs/nowhere"),
                () -> "the message must name the pointer that could not be resolved: " + thrown.getMessage());
    }

    /**
     * A <b>tagged union</b>'s per-branch value types must not be collapsed onto the first branch. Unioning
     * {@code oneOf} branches is right for <em>allowed keys</em> and wrong for value shapes: {@code model-diff}'s
     * {@code deltaValue.value} is a string under {@code TEXT} and a boolean/number/array under the others, so the
     * first-branch reading rejected four legal shapes the moment refs made the union reachable.
     */
    @Test
    void aTaggedUnionsPerBranchValueTypesAreNotCollapsed() throws Exception {
        JsonNode data = M.readTree("""
                { "changes": [ { "element": "FIELD", "id": "/Order/Flag", "change": "MODIFIED", "risk": "LOW",
                                 "riskReasons": [], "deltas": [
                                   { "aspect": "required",
                                     "beforeValue": { "type": "BOOLEAN", "value": false },
                                     "afterValue": { "type": "BOOLEAN", "value": true } },
                                   { "aspect": "length",
                                     "beforeValue": { "type": "INTEGER", "value": 3 },
                                     "afterValue": { "type": "STRING_LIST", "value": [ "a", "b" ] } } ] } ],
                  "refactors": [] }""");

        List<Diagnostic> violations = SpecKeys.forSchema("model-diff").validate(data);

        assertEquals(List.of(), violations,
                () -> "every branch of the tagged union is a legal value shape: " + violations);
    }
}
