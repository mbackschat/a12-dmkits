package io.github.mbackschat.a12.dm.rulekit.validate;

import io.github.mbackschat.a12.dm.Checkout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The diagnostic registry never goes stale (CLI-CONFORMANCE-SPEC §never-stale) — bidirectional parity over
 * the main source, build-checked:
 *
 * <ul>
 *   <li><b>forward (no-rot):</b> every {@code "RK_*"} string literal the code emits resolves to an
 *       {@link RkCode} constant — so an un-catalogued code cannot ship;</li>
 *   <li><b>reverse (no dead rows):</b> every {@link RkCode} constant is actually referenced from the source
 *       (as a literal or a whole-word {@code RkCode.X}) — so the catalog can't accrete codes nothing emits.</li>
 * </ul>
 *
 * Scans every tree that mints {@code RK_*}: the Java kits and {@code cli/src/main}, and the portable checker's
 * {@code consistency/src/commonMain}, which cannot reference {@link RkCode} and so spells its codes as text with
 * nothing else joining them to the registry (CLI-CONFORMANCE-GAPS CG25 found fifteen unregistered there).
 * {@code RkCode.java} is excluded because it defines rather than emits, and the checker's generated
 * {@code KernelCorrectives.kt} because it spells every registered code it projects, which would make the reverse
 * direction vacuous. {@code MVK_*} codes are the kernel's — covered by the MVK ledger, not here.
 */
class RkCodeParityTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    private static final Pattern RK_LITERAL = Pattern.compile("\"(RK_[A-Z0-9_]+)\"");

    @Test
    void everyEmittedCodeIsCataloguedAndEveryCatalogueCodeIsEmitted() throws IOException {
        assertTrue(Files.isRegularFile(CHECKOUT.resolve("rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/RkCode.java")),
                "rulekit's RkCode.java, the code catalogue this parity reads, is missing — a rename must update this guard, which used to report the "
                        + "absence as a repository it could not locate");
        List<Path> sources = mainSources();
        assertTrue(sources.size() > 20, () -> "expected to scan the main source trees, found " + sources.size());

        Set<String> emitted = new TreeSet<>();   // RK_* literals found in the source
        StringBuilder allSource = new StringBuilder();
        for (Path f : sources) {
            String text = Files.readString(f);
            allSource.append(text).append('\n');
            Matcher m = RK_LITERAL.matcher(text);
            while (m.find()) {
                emitted.add(m.group(1));
            }
        }

        // forward: every emitted literal is a catalogued code
        List<String> uncatalogued = emitted.stream()
                .filter(code -> RkCode.byCode(code).isEmpty())
                .toList();
        assertTrue(uncatalogued.isEmpty(),
                () -> "emitted RK_* codes missing from the RkCode registry (un-catalogued — add them): " + uncatalogued);

        // reverse: every catalogued code is referenced somewhere — a literal "RK_X" or a whole-word RkCode.X. A
        // plain substring test passed whatever merely CONTAINED the name, such as a checker constant's identifier
        // or a javadoc mention, so a code nothing emitted any more still read as referenced (found by the
        // 2026-10-25 cold review of the F1–F7 batches).
        String src = allSource.toString();
        List<String> dead = new ArrayList<>();
        for (RkCode c : RkCode.values()) {
            boolean referenced = Pattern.compile("\\bRkCode\\." + c.name() + "\\b").matcher(src).find();
            if (!emitted.contains(c.code()) && !referenced) {
                dead.add(c.code());
            }
        }
        assertTrue(dead.isEmpty(),
                () -> "RkCode constants nothing emits/references (dead rows — remove or wire them): " + dead);
    }


    private static List<Path> mainSources() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String tree : List.of("core/src/main/java", "rulekit/src/main/java",
                "modelkit/src/main/java", "edit/src/main/java", "cli/src/main/java", "consistency/src/commonMain")) {
            Path dir = CHECKOUT.resolve(tree);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".kt"))
                        .filter(f -> !Set.of("RkCode.java", "KernelCorrectives.kt").contains(f.getFileName().toString()))
                        .forEach(out::add);
            }
        }
        return out;
    }
}
