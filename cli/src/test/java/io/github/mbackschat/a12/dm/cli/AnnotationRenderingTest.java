package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loading/expanding a model whose header carries a {@code roles} annotation serializes that annotation
 * through the kernel (Jackson reflects {@code AnnotationImpl.getName()}/{@code getValue()}). On the native
 * image that reflective call crashed (the v0.6.0 report): the reachability metadata had the annotation
 * <b>setters</b> (deserialize) but not the <b>getters</b> (serialize), because no test ever <i>rendered</i>
 * an annotated model — the getters only fire during include-expansion / copy. This test drives that exact
 * path so:
 * <ol>
 *   <li>the <b>test-suite-driven</b> native capture ({@code scripts/capture-native-config.sh}) records the
 *       annotation getters automatically — closing the gap at its root, not by hand-listing methods;</li>
 *   <li>it is a JVM regression test that an annotated model AND an annotated include load/describe/export
 *       cleanly.</li>
 * </ol>
 * The native <i>runtime</i> guard is {@code scripts/native-battery.sh} (the annotated-include block); this
 * test is what makes that guard pass after a re-capture.
 */
class AnnotationRenderingTest {

    @Test
    void anAnnotatedIncludeAndAnnotatedParentSerializeCleanly(@TempDir Path dir) {
        // an included sub-model whose header carries a `roles` annotation
        Path sub = dir.resolve("catalog.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "catalog", "--locale", "en_US",
                "--root", "Catalog", "-o", sub.toString()).code());
        assertEquals(0, CliTestSupport.run("-m", sub.toString(), "field", "add",
                "--group", "/Catalog", "--name", "Sku", "--kind", "STRING").code());
        assertEquals(0, CliTestSupport.run("-m", sub.toString(), "config", "modify", "--role", "admin").code(),
                "annotate the included model's header");

        // a parent that includes it AND carries its own header annotation (trigger A + B in one fixture)
        Path parent = dir.resolve("store.dm.json");
        assertEquals(0, CliTestSupport.run("model", "new", "--id", "store", "--locale", "en_US",
                "--root", "Store", "-o", parent.toString()).code());
        assertEquals(0, CliTestSupport.run("-m", parent.toString(), "include", "add",
                "--reference", "catalog", "--parent", "/Store", "--name", "Catalog",
                "-w", dir.toString()).code());
        assertEquals(0, CliTestSupport.run("-m", parent.toString(), "config", "modify", "--role", "manager").code());

        // each LOADS → kernel EXPAND → serializes the annotated include (and parent) → AnnotationImpl.getName()
        for (String op : new String[]{"check", "describe", "export"}) {
            CliTestSupport.Result res = "export".equals(op)
                    ? CliTestSupport.run("-m", parent.toString(), "-w", dir.toString(), "export")
                    : CliTestSupport.run("-m", parent.toString(), "-w", dir.toString(), "model", op);
            assertEquals(0, res.code(),
                    () -> "`" + op + "` over an annotated include must succeed (not crash): " + res.err() + res.out());
        }
    }
}
