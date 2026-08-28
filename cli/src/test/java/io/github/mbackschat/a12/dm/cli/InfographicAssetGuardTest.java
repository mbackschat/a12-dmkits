package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Keeps each public release's local infographic copy identical to the canonical source asset and locks the linked
 * scaled-preview contract in the READMEs and regeneration source. GitHub must be able to render each preview and
 * open the full PNG without depending on the private source repository or a mutable external URL.
 */
class InfographicAssetGuardTest {

    private static final Path ROOT = Path.of("..");
    private static final Path CANONICAL = ROOT.resolve("assets/infographics");

    private static final List<Placement> PLACEMENTS = List.of(
            new Placement("a12-dmkits-toolchain.png", ROOT.resolve("README.md"),
                    "assets/infographics/a12-dmkits-toolchain.png", 720, null),
            new Placement("dmtool-model-workbench.png", ROOT.resolve("README.md"),
                    "assets/infographics/dmtool-model-workbench.png", 720, null),
            new Placement("a12-interpreter-runtime.png", ROOT.resolve("README.md"),
                    "assets/infographics/a12-interpreter-runtime.png", 720, null),
            new Placement("interpreter-vs-kernel.png", ROOT.resolve("README.md"),
                    "assets/infographics/interpreter-vs-kernel.png", 720, null),
            new Placement("dmtool-model-workbench.png", ROOT.resolve("plugin/README.md"),
                    "assets/infographics/dmtool-model-workbench.png", 720,
                    ROOT.resolve("plugin/assets/infographics/dmtool-model-workbench.png")),
            new Placement("interpreter-vs-kernel.png", ROOT.resolve("plugin/README.md"),
                    "assets/infographics/interpreter-vs-kernel.png", 720,
                    ROOT.resolve("plugin/assets/infographics/interpreter-vs-kernel.png")),
            new Placement("a12-interpreter-runtime.png", ROOT.resolve("interpreter/public-release/README.md"),
                    "assets/infographics/a12-interpreter-runtime.png", 720,
                    ROOT.resolve("interpreter/public-release/assets/infographics/a12-interpreter-runtime.png")),
            new Placement("interpreter-vs-kernel.png", ROOT.resolve("interpreter/public-release/README.md"),
                    "assets/infographics/interpreter-vs-kernel.png", 720,
                    ROOT.resolve("interpreter/public-release/assets/infographics/interpreter-vs-kernel.png")),
            new Placement("a12-dmkits-toolchain.png", ROOT.resolve("docs/INFOGRAPHICS.md"),
                    "../assets/infographics/a12-dmkits-toolchain.png", 540, null),
            new Placement("dmtool-model-workbench.png", ROOT.resolve("docs/INFOGRAPHICS.md"),
                    "../assets/infographics/dmtool-model-workbench.png", 540, null),
            new Placement("a12-interpreter-runtime.png", ROOT.resolve("docs/INFOGRAPHICS.md"),
                    "../assets/infographics/a12-interpreter-runtime.png", 540, null),
            new Placement("interpreter-vs-kernel.png", ROOT.resolve("docs/INFOGRAPHICS.md"),
                    "../assets/infographics/interpreter-vs-kernel.png", 540, null));

    @Test
    void everyPlacementUsesTheExpectedPreviewLinkedToTheFullPng() throws IOException {
        for (var placement : PLACEMENTS) {
            var document = Files.readString(placement.document());
            var relative = placement.relativePath();
            assertTrue(document.contains("<a href=\"" + relative + "\">"),
                    () -> placement.document() + " does not link to the full infographic " + relative);
            var imageStart = document.indexOf("<img src=\"" + relative + "\"");
            var imageEnd = imageStart < 0 ? -1 : document.indexOf('>', imageStart);
            assertTrue(imageStart >= 0 && imageEnd >= 0
                            && document.substring(imageStart, imageEnd)
                                    .contains("width=\"" + placement.previewWidth() + "\""),
                    () -> placement.document() + " does not render the expected preview for " + relative);
        }
    }

    @Test
    void canonicalAssetsArePngsAndReleaseCopiesAreByteIdentical() throws IOException {
        for (var placement : PLACEMENTS) {
            var canonical = CANONICAL.resolve(placement.fileName());
            assertTrue(Files.isRegularFile(canonical), () -> "missing canonical infographic: " + canonical);
            assertTrue(isPng(canonical), () -> "infographic is not a PNG: " + canonical);
            if (placement.releaseCopy() != null) {
                assertTrue(Files.isRegularFile(placement.releaseCopy()),
                        () -> "missing public-release infographic copy: " + placement.releaseCopy());
                assertArrayEquals(Files.readAllBytes(canonical), Files.readAllBytes(placement.releaseCopy()),
                        () -> placement.releaseCopy() + " has drifted from " + canonical);
            }
        }
    }

    @Test
    void performancePromptTracksThePublicPerformanceReport() throws IOException {
        var report = Files.readString(ROOT.resolve("interpreter/public-release/PERFORMANCE.md"));
        var specification = Files.readString(ROOT.resolve("docs/INFOGRAPHICS.md"));
        assertTrue(specification.contains("## Synchronization contract"),
                "the regeneration source must explain how mutable measurements stay synchronized");

        var stressRow = tableRow(report, "10,000 rows — stress");
        var interpreterJvm = tableRow(report, "**Interpreter — JVM**");
        var generatedJavaJvm = tableRow(report, "A12 Kernel, generated Java — JVM");
        var dynamicGroovyJvm = tableRow(report, "A12 Kernel, dynamic Groovy — JVM");
        var defaultInventory = tableRow(report, "Default inventory, 15 cases");
        var repeatedDocuments = tableRow(report, "Repeated-document evaluation");
        var hugeDocument = tableRow(report, "Huge-document edge, 20,000 rows");
        var hardestCase = tableRow(report, "Hardest case — 384 concentrated low-firing rules");
        var interpreterMemory = tableRow(report, "**Interpreter** — prepared model");
        var generatedJavaMemory = tableRow(report, "A12 Kernel, generated Java");
        var dynamicGroovyMemory = tableRow(report, "A12 Kernel, dynamic Groovy");

        var comparison = Pattern.compile(
                        "interpreter is about \\*\\*(\\d+×) the A12 Kernel's generated Java\\*\\* and about "
                                + "\\*\\*(\\d+×) its dynamic Groovy\\*\\*")
                .matcher(report);
        assertTrue(comparison.find(), "missing current JVM comparison ratios in public performance report");

        var evidence = Pattern.compile("<sub>Measured ([^·]+) · ([^·]+) · ([^·]+) · ([^·]+) ·")
                .matcher(report);
        assertTrue(evidence.find(), "missing dated measurement evidence in public performance report");

        var expectedPromptLines = List.of(
                "- Section title: \"WARM VALIDATION • " + plain(stressRow[1]).replace(" — stress", "")
                        + " • " + plain(stressRow[2]) + " findings\"",
                "- Bar value: \"" + plain(interpreterJvm[2]) + "\"",
                "- Bar value: \"" + plain(generatedJavaJvm[2]) + "\"",
                "- Bar value: \"" + plain(dynamicGroovyJvm[2]) + "\"",
                "- Callout: \"≈" + comparison.group(1) + " faster vs generated Java\"",
                "- Callout: \"≈" + comparison.group(2) + " faster vs dynamic Groovy\"",
                "- Card value: \"" + plain(defaultInventory[2]) + " faster\"",
                "- Card value: \"" + plain(repeatedDocuments[2]) + " faster\"",
                "- Card value: \"" + plain(hardestCase[2]) + " faster\"",
                "- Card value: \"" + plain(hugeDocument[2]) + " faster\"",
                "- Memory value: \"" + plain(interpreterMemory[2]) + "\"",
                "- Memory value: \"" + plain(generatedJavaMemory[2]) + "\"",
                "- Memory callout: \"" + plain(generatedJavaMemory[3]) + "\"",
                "- Memory value: \"" + plain(dynamicGroovyMemory[2]) + "\"",
                "- Memory callout: \"" + plain(dynamicGroovyMemory[3]) + "\"",
                "- Browser value: \"" + plain(tableRow(report, "200 rows — representative")[3])
                        .replace(" median", "") + " first answer\"",
                "- Browser value: \"" + plain(tableRow(report, "200 rows — representative")[4]) + " warm\"",
                "- Bottom evidence: \"Measured " + evidence.group(1).trim() + " • " + evidence.group(2).trim()
                        + " • " + evidence.group(4).trim() + " • " + evidence.group(3).trim() + "\"");

        for (var expectedPromptLine : expectedPromptLines) {
            assertTrue(specification.contains(expectedPromptLine),
                    () -> "performance infographic prompt has drifted from its public source: " + expectedPromptLine);
        }
    }

    private static boolean isPng(Path file) throws IOException {
        var bytes = Files.readAllBytes(file);
        return bytes.length >= 8
                && bytes[0] == (byte) 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4e
                && bytes[3] == 0x47
                && bytes[4] == 0x0d
                && bytes[5] == 0x0a
                && bytes[6] == 0x1a
                && bytes[7] == 0x0a;
    }

    private static String[] tableRow(String markdown, String label) {
        var row = markdown.lines()
                .filter(line -> line.startsWith("|") && line.contains("| " + label + " |"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing performance-report table row: " + label));
        return row.split("\\|", -1);
    }

    private static String plain(String cell) {
        return cell.trim().replace("**", "");
    }

    private record Placement(String fileName, Path document, String relativePath, int previewWidth, Path releaseCopy) {
    }
}
