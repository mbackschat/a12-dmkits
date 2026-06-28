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
 * {@code --kernel} option, which under the native profile is <b>removed from the command tree entirely</b> —
 * not merely refused at runtime. The interpreter is the only native engine, so surfacing a flag that always
 * fails would mislead an LLM agent that discovers it via {@code --help}/{@code manifest}; removing it keeps
 * every self-describing surface honest (agent-first design).
 *
 * <p>Native isn't built in CI, so this runs the command tree in <b>forced-native profile</b> on the JVM
 * (the {@link CliTestSupport#runNative} seam) and asserts: the verbs are present on every self-describing
 * surface (manifest / {@code --help} / {@code schema}), the default (interpreter) path runs, and
 * {@code --kernel} is absent from native help + manifest (but present on the JVM profile, where the kernel
 * engine exists).
 */
class NativeImageRuntimeVerbsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> RUNTIME_VERBS = List.of("model eval", "model compute", "rule eval");

    private static Set<String> manifestVerbs(String json) throws Exception {
        Set<String> verbs = new HashSet<>();
        for (JsonNode v : MAPPER.readTree(json).path("verbs")) {
            verbs.add(v.path("verb").asText());
        }
        return verbs;
    }

    /** The flag names declared on a manifest verb entry (its {@code params[].flag}). */
    private static Set<String> manifestVerbFlags(String json, String verb) throws Exception {
        Set<String> flags = new HashSet<>();
        for (JsonNode v : MAPPER.readTree(json).path("verbs")) {
            if (v.path("verb").asText().equals(verb)) {
                for (JsonNode p : v.path("params")) {
                    flags.add(p.path("flag").asText());
                }
            }
        }
        return flags;
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
        assertTrue(ruleHelp.contains("eval"), () -> "rule --help should show 'eval' on native:\n" + ruleHelp);
    }

    @Test
    void nativeSchemaResolvesTheRuntimeVerbs() {
        String nativeSchema = CliTestSupport.runNative("schema", "model", "eval").out();
        assertTrue(nativeSchema.contains("\"op\" : \"model eval\""),
                () -> "native schema must resolve 'model eval' (it is interpreter-backed):\n" + nativeSchema);
    }

    @Test
    void nativeManifestOmitsTheKernelOptionButJvmKeepsIt() throws Exception {
        String nativeManifest = CliTestSupport.runNative("manifest").out();
        String jvmManifest = CliTestSupport.run("manifest").out();
        for (String verb : RUNTIME_VERBS) {
            Set<String> jvmFlags = manifestVerbFlags(jvmManifest, verb);
            Set<String> nativeFlags = manifestVerbFlags(nativeManifest, verb);
            assertTrue(jvmFlags.contains("--kernel"),
                    () -> "JVM manifest must keep --kernel on '" + verb + "' (the kernel engine exists there): " + jvmFlags);
            assertTrue(!nativeFlags.contains("--kernel"),
                    () -> "native manifest must NOT list --kernel on '" + verb + "' (interpreter is the only native engine): " + nativeFlags);
        }
    }

    @Test
    void nativeHelpOmitsTheKernelOption() {
        String nativeEvalHelp = CliTestSupport.runNative("model", "eval", "--help").out();
        assertTrue(!nativeEvalHelp.contains("--kernel"),
                () -> "native 'model eval --help' must NOT mention --kernel:\n" + nativeEvalHelp);
        String jvmEvalHelp = CliTestSupport.run("model", "eval", "--help").out();
        assertTrue(jvmEvalHelp.contains("--kernel"),
                () -> "JVM 'model eval --help' must still offer --kernel:\n" + jvmEvalHelp);
    }

    @Test
    void nativeRejectsTheRemovedKernelFlagAndRunsTheDefaultEngine() {
        // --kernel is removed from the native tree: passing it is an unknown option (usage error, exit 2).
        CliTestSupport.Result kernel = CliTestSupport.runNative(
                "-m", "x.json", "model", "eval", "--instance", "y.json", "--kernel");
        assertEquals(2, kernel.code(), () -> "native --kernel must be a usage error (unknown option): " + kernel.out() + kernel.err());
        assertTrue(kernel.err().contains("--kernel"),
                () -> "the unknown-option error must name --kernel:\n" + kernel.err());

        // the default (no --kernel) reaches the engine — here it then fails on the dummy model path, NOT on --kernel.
        CliTestSupport.Result def = CliTestSupport.runNative(
                "-m", "x.json", "model", "eval", "--instance", "y.json");
        assertTrue(!def.err().contains("Unknown option") || !def.err().contains("--kernel"),
                () -> "the default path must NOT trip on --kernel:\n" + def.err());
    }
}
