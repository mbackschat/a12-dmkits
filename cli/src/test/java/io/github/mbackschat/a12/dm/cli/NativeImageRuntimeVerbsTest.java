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
 * Guards the native-image contract for the interpreter-backed verbs (docs/NATIVE-IMAGE-GRAALVM.md §4).
 * Runtime evaluation used to be HIDDEN in native because it ran the kernel's Groovy codegen; evaluation,
 * sample generation, and portable model expansion now use the native-safe {@code dm.interpreter}, so they
 * stay in the native command tree. The user-facing {@code --kernel} eval flag was retired
 * (INTERPRETER-DROPIN-CERTIFICATE): it is gone from every profile.
 *
 * <p>Native isn't built in CI, so this runs the command tree in <b>forced-native profile</b> on the JVM
 * (the {@link CliTestSupport#runNative} seam) and asserts: the verbs are present on every self-describing
 * surface (manifest / {@code --help} / {@code schema}), and the retired {@code --kernel} flag is an unknown
 * option on every profile.
 */
class NativeImageRuntimeVerbsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> INTERPRETER_VERBS =
            List.of("model expand", "model eval", "model compute", "model seed", "rule eval");
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
        for (String verb : INTERPRETER_VERBS) {
            assertTrue(verbs.contains(verb), () -> "native manifest must list '" + verb + "' (interpreter-backed): " + verbs);
        }
    }

    @Test
    void nativeHelpShowsTheRuntimeOps() {
        String modelHelp = CliTestSupport.runNative("model", "--help").out();
        assertTrue(modelHelp.contains("expand"), () -> "model --help should show 'expand' on native:\n" + modelHelp);
        assertTrue(modelHelp.contains("eval"), () -> "model --help should show 'eval' on native:\n" + modelHelp);
        assertTrue(modelHelp.contains("compute"), () -> "model --help should show 'compute' on native:\n" + modelHelp);
        assertTrue(modelHelp.contains("seed"), () -> "model --help should show 'seed' on native:\n" + modelHelp);
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
    void noProfileOffersTheRetiredKernelFlag() throws Exception {
        // The --kernel eval flag was retired: it appears on NO runtime verb, on either profile's manifest.
        String nativeManifest = CliTestSupport.runNative("manifest").out();
        String jvmManifest = CliTestSupport.run("manifest").out();
        for (String verb : RUNTIME_VERBS) {
            Set<String> jvmFlags = manifestVerbFlags(jvmManifest, verb);
            Set<String> nativeFlags = manifestVerbFlags(nativeManifest, verb);
            assertTrue(!jvmFlags.contains("--kernel"),
                    () -> "JVM manifest must NOT list --kernel on '" + verb + "' (retired): " + jvmFlags);
            assertTrue(!nativeFlags.contains("--kernel"),
                    () -> "native manifest must NOT list --kernel on '" + verb + "' (retired): " + nativeFlags);
        }
    }

    @Test
    void theRetiredKernelFlagIsRejectedOnEveryProfile() {
        // --kernel no longer exists on any verb: passing it is an unknown option (usage error, exit 2), on
        // both the native and JVM profiles.
        for (CliTestSupport.Result res : List.of(
                CliTestSupport.runNative("-m", "x.json", "model", "eval", "--instance", "y.json", "--kernel"),
                CliTestSupport.run("-m", "x.json", "model", "eval", "--instance", "y.json", "--kernel"))) {
            assertEquals(2, res.code(), () -> "--kernel must be a usage error (unknown option): " + res.out() + res.err());
            assertTrue(res.err().contains("--kernel"),
                    () -> "the unknown-option error must name --kernel:\n" + res.err());
        }
    }
}
