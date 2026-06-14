package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Guards the native-image verb-hiding contract (docs/NATIVE-IMAGE-GRAALVM.md §4). The runtime-eval verbs
 * ({@code model eval}/{@code rule test}/{@code model compute}) execute kernel Groovy codegen, which is not
 * native-compilable — so in the native build they are removed from the command tree, and a cold native
 * agent must NOT be able to discover them through ANY self-describing surface.
 *
 * <p>Native isn't built in CI, so this runs the command tree in <b>forced-native profile</b> on the JVM
 * (the {@link CliTestSupport#runNative} seam) and asserts the verbs are gone from manifest, {@code --help},
 * and {@code schema}, and are uninvocable. The JVM profile (default) keeps them — the complementary check.
 */
class NativeImageVerbHidingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> JVM_ONLY = List.of("model eval", "model compute", "rule test");
    // controls that must survive in BOTH profiles — proves we hid only the intended verbs
    private static final List<String> NATIVE_KEEPS = List.of("model validate", "rule check", "rule read", "manifest");

    private static Set<String> manifestVerbs(String json) throws Exception {
        Set<String> verbs = new HashSet<>();
        for (JsonNode v : MAPPER.readTree(json).path("verbs")) {
            verbs.add(v.path("verb").asText());
        }
        return verbs;
    }

    @Test
    void nativeManifestOmitsTheRuntimeEvalVerbsButKeepsTheRest() throws Exception {
        CliTestSupport.Result res = CliTestSupport.runNative("manifest");
        assertEquals(0, res.code(), res::err);
        Set<String> verbs = manifestVerbs(res.out());
        for (String hidden : JVM_ONLY) {
            assertFalse(verbs.contains(hidden), () -> "native manifest must NOT list '" + hidden + "': " + verbs);
        }
        for (String kept : NATIVE_KEEPS) {
            assertTrue(verbs.contains(kept), () -> "native manifest must still list '" + kept + "': " + verbs);
        }
    }

    @Test
    void jvmManifestKeepsThemSanityCheck() throws Exception {
        // the complement: in the default (JVM) profile the verbs are present — so hiding is native-only, not a delete
        Set<String> verbs = manifestVerbs(CliTestSupport.run("manifest").out());
        for (String present : JVM_ONLY) {
            assertTrue(verbs.contains(present), () -> "JVM manifest must list '" + present + "': " + verbs);
        }
    }

    @Test
    void nativeHelpOmitsTheRuntimeEvalOps() {
        // `model --help` lists the model ops; eval/compute must be gone. `rule --help` must not list test.
        String modelHelp = CliTestSupport.runNative("model", "--help").out();
        assertFalse(modelHelp.contains(" eval "), () -> "model --help still shows 'eval':\n" + modelHelp);
        assertFalse(modelHelp.contains(" compute "), () -> "model --help still shows 'compute':\n" + modelHelp);
        assertTrue(modelHelp.contains("validate"), () -> "model --help should still show 'validate':\n" + modelHelp);

        String ruleHelp = CliTestSupport.runNative("rule", "--help").out();
        assertFalse(ruleHelp.contains(" test "), () -> "rule --help still shows 'test':\n" + ruleHelp);
        assertTrue(ruleHelp.contains("check"), () -> "rule --help should still show 'check':\n" + ruleHelp);
    }

    @Test
    void nativeSchemaDoesNotResolveTheHiddenVerbs() {
        // on the JVM, `schema model eval` returns the eval contract; in native it must not (verb unknown)
        assertTrue(CliTestSupport.run("schema", "model", "eval").out().contains("\"op\" : \"model eval\""),
                "sanity: JVM schema resolves model eval");
        String nativeSchema = CliTestSupport.runNative("schema", "model", "eval").out();
        assertFalse(nativeSchema.contains("\"op\" : \"model eval\""),
                () -> "native schema must NOT resolve the hidden 'model eval':\n" + nativeSchema);
    }

    @Test
    void nativeCannotInvokeTheHiddenVerbs() {
        // removed from the tree → picocli treats it as an unknown subcommand (not a valid op with a usage error)
        CliTestSupport.Result res = CliTestSupport.runNative("model", "eval", "--doc", "/tmp/none.json");
        assertEquals(2, res.code(), () -> "expected usage exit for an unknown subcommand; got: " + res.out() + res.err());
        assertTrue(res.err().toLowerCase().contains("eval"),
                () -> "native error should name the unmatched 'eval':\n" + res.err());
    }
}
