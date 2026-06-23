package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Guards the native-image contract for the runtime-eval verbs (docs/NATIVE-IMAGE-GRAALVM.md §4). They used
 * to be HIDDEN in native (they ran the kernel's Groovy codegen) — but they now default to the native-safe
 * {@code dm.interpreter}, so they STAY in the native command tree and run there. The JVM-only part is the
 * {@code --kernel} option, which is refused under the native profile.
 *
 * <p>Native isn't built in CI, so this runs the command tree in <b>forced-native profile</b> on the JVM
 * (the {@link CliTestSupport#runNative} seam) and asserts: the verbs are present on every self-describing
 * surface (manifest / {@code --help} / {@code schema}), the default (interpreter) path runs, and
 * {@code --kernel} is refused.
 */
class NativeImageRuntimeVerbsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> RUNTIME_VERBS = List.of("model eval", "model compute", "rule test");

    private static Set<String> manifestVerbs(String json) throws Exception {
        Set<String> verbs = new HashSet<>();
        for (JsonNode v : MAPPER.readTree(json).path("verbs")) {
            verbs.add(v.path("verb").asText());
        }
        return verbs;
    }

    @Test
    void nativeManifestKeepsTheRuntimeVerbs() throws Exception {
        CliTestSupport.Result res = CliTestSupport.runNative("manifest");
        assertEquals(0, res.code(), res::err);
        Set<String> verbs = manifestVerbs(res.out());
        for (String verb : RUNTIME_VERBS) {
            assertTrue(verbs.contains(verb), () -> "native manifest must list '" + verb + "' (interpreter-backed): " + verbs);
        }
    }

    @Test
    void nativeHelpShowsTheRuntimeOps() {
        String modelHelp = CliTestSupport.runNative("model", "--help").out();
        assertTrue(modelHelp.contains("eval"), () -> "model --help should show 'eval' on native:\n" + modelHelp);
        assertTrue(modelHelp.contains("compute"), () -> "model --help should show 'compute' on native:\n" + modelHelp);
        String ruleHelp = CliTestSupport.runNative("rule", "--help").out();
        assertTrue(ruleHelp.contains("test"), () -> "rule --help should show 'test' on native:\n" + ruleHelp);
    }

    @Test
    void nativeSchemaResolvesTheRuntimeVerbs() {
        String nativeSchema = CliTestSupport.runNative("schema", "model", "eval").out();
        assertTrue(nativeSchema.contains("\"op\" : \"model eval\""),
                () -> "native schema must resolve 'model eval' (it is interpreter-backed):\n" + nativeSchema);
    }

    @Test
    void nativeRefusesTheKernelFlagButRunsTheDefaultEngine() {
        // --kernel is the JVM-only part: refused under the native profile (the interpreter is the only engine).
        CliTestSupport.Result kernel = CliTestSupport.runNative(
                "-m", "x.json", "model", "eval", "--doc", "y.json", "--kernel");
        assertEquals(2, kernel.code(), () -> "native --kernel must be refused (usage error): " + kernel.out() + kernel.err());
        assertTrue(kernel.err().contains("--kernel is not available in the native image"),
                () -> "the refusal must name --kernel:\n" + kernel.err());

        // the default (no --kernel) reaches the engine — here it then fails on the dummy model path, NOT on --kernel.
        CliTestSupport.Result def = CliTestSupport.runNative(
                "-m", "x.json", "model", "eval", "--doc", "y.json");
        assertTrue(!def.err().contains("--kernel is not available"),
                () -> "the default path must NOT hit the --kernel refusal:\n" + def.err());
    }
}
