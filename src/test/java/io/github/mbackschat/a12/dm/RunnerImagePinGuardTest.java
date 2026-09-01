package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

/** Guards owned GitHub Actions workflows against the moving {@code ubuntu-latest} runner label. */
class RunnerImagePinGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    private static final Pattern MOVING_UBUNTU_LABEL =
            Pattern.compile("(?<![A-Za-z0-9_.-])ubuntu-latest(?![A-Za-z0-9_.-])");
    private static final String REVIEWABLE_MATRIX_EXPRESSION = "${{ matrix.os }}";
    private static final ObjectMapper WORKFLOW_YAML = new ObjectMapper(new YAMLFactory());

    private record Finding(String file, int line, String problem, String source) {
        @Override
        public String toString() {
            return file + ":" + line + "  " + problem + "\n      " + source.strip();
        }
    }

    @Test
    void everyOwnedLinuxRunnerPinsItsUbuntuRelease() throws Exception {
        List<String> workflows = trackedWorkflows();
        assertTrue(workflows.size() >= 6,
                "only " + workflows.size() + " tracked workflow files found — the live or mirror workflow "
                        + "tree moved, so a green scan would not establish the claimed denominator");

        var findings = new ArrayList<Finding>();
        for (String relative : workflows) {
            String yaml = Files.readString(CHECKOUT.resolve(relative), StandardCharsets.UTF_8);
            findings.addAll(scan(relative, yaml));
            findings.addAll(auditRunnerSelections(relative, yaml));
        }

        assertEquals(List.of(), findings, () -> """
                An owned GitHub Actions workflow leaves its runner release moving or outside the tracked tree.
                `ubuntu-latest` lets GitHub change the OS release without a repository edit; an external runner
                expression can resolve to the same label without this guard seeing the decision.

                %s

                Pin the intended release (currently `ubuntu-24.04`) in both live workflows and workflow
                templates copied into mirrors. The finite `${{ matrix.os }}` expression is allowed because its
                values remain in the same tracked file. Image revisions and bundled tool patches still roll;
                pin load-bearing tools independently.
                Contract and measurement: docs/BUILD-ENVIRONMENT.md "Hosted Linux runners pin the OS release"."""
                .formatted(findings.stream().map(Finding::toString)
                        .reduce("", (a, b) -> a.isEmpty() ? "  " + b : a + "\n  " + b)));
    }

    @Test
    void theScannerCatchesDirectMatrixAndQuotedLabels() {
        assertEquals(1, scan("direct.yml", "runs-on: ubuntu-latest").size());
        assertEquals(1, scan("matrix.yml", "  - os: ubuntu-latest").size());
        assertEquals(1, scan("custom.yml", "runner: 'ubuntu-latest'").size());
        assertEquals(1, scan("list.yml", "  - \"ubuntu-latest\"").size());
    }

    @Test
    void runnerExpressionsMustResolveFromAStaticMatrixInTheSameJob() throws IOException {
        assertEquals(List.of(), auditRunnerSelections("matrix.yml", """
                jobs:
                  build:
                    strategy:
                      matrix:
                        include:
                          - os: ubuntu-24.04
                          - os: windows-latest
                    runs-on: ${{ matrix.os }}
                """));
        assertEquals(1, auditRunnerSelections("variable.yml", """
                jobs:
                  build:
                    runs-on: ${{ vars.RUNNER_IMAGE }}
                """).size());
        assertEquals(1, auditRunnerSelections("quoted-variable.yml", """
                jobs:
                  build:
                    runs-on: '${{ vars.RUNNER_IMAGE }}'
                """).size());
        assertEquals(1, auditRunnerSelections("dynamic-matrix.yml", """
                jobs:
                  build:
                    strategy:
                      matrix: ${{ fromJSON(vars.MATRIX) }}
                    runs-on: ${{ matrix.os }}
                """).size());
        assertEquals(1, auditRunnerSelections("dynamic-os.yml", """
                jobs:
                  build:
                    strategy:
                      matrix:
                        include:
                          - os: ${{ vars.LINUX_IMAGE }}
                    runs-on: ${{ matrix.os }}
                """).size());
        assertEquals(1, auditRunnerSelections("multiline.yml", """
                jobs:
                  build:
                    runs-on:
                      - self-hosted
                      - ${{ vars.RUNNER_IMAGE }}
                """).size());
    }

    @Test
    void theScannerAllowsPinnedAndArmImagesAndDocumentationComments() {
        assertEquals(List.of(), scan("pinned.yml", "runs-on: ubuntu-24.04"));
        assertEquals(List.of(), scan("arm.yml", "runs-on: ubuntu-24.04-arm"));
        assertEquals(List.of(), scan("comment.yml", "# ubuntu-latest is intentionally forbidden"));
        assertEquals(List.of(), scan("trailing.yml", "runs-on: ubuntu-24.04 # replaces ubuntu-latest"));
        assertEquals(List.of(),
                scan("escaped.yml", "run: \"C:\\\\\" # ubuntu-latest remains documentation"));
    }

    private static List<Finding> scan(String file, String yaml) {
        var findings = new ArrayList<Finding>();
        List<String> lines = yaml.lines().toList();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            String effective = beforeYamlComment(line);
            if (MOVING_UBUNTU_LABEL.matcher(effective).find()) {
                findings.add(new Finding(file, index + 1, "moving ubuntu-latest label", line));
            }
        }
        return findings;
    }

    private static List<Finding> auditRunnerSelections(String file, String yaml) throws IOException {
        JsonNode jobs = WORKFLOW_YAML.readTree(yaml).path("jobs");
        if (!jobs.isObject()) {
            return List.of();
        }

        var findings = new ArrayList<Finding>();
        List<Integer> runsOnLines = runsOnLines(yaml);
        int runsOnIndex = 0;
        for (Map.Entry<String, JsonNode> field : jobs.properties()) {
            JsonNode job = field.getValue();
            JsonNode runsOn = job.get("runs-on");
            if (runsOn == null) {
                continue;
            }
            int line = runsOnIndex < runsOnLines.size() ? runsOnLines.get(runsOnIndex++) : 1;
            String problem = runnerSelectionProblem(job, runsOn);
            if (problem != null) {
                findings.add(new Finding(file, line, problem,
                        "job `" + field.getKey() + "`: runs-on " + runsOn));
            }
        }
        return findings;
    }

    private static String runnerSelectionProblem(JsonNode job, JsonNode runsOn) {
        if (runsOn.isTextual()) {
            String value = runsOn.textValue();
            if (!value.contains("${{")) {
                return null;
            }
            if (!REVIEWABLE_MATRIX_EXPRESSION.equals(value)) {
                return "runner expression is resolved outside a static matrix.os in this job";
            }
            return staticMatrixOsProblem(job);
        }
        if (runsOn.isArray()) {
            for (JsonNode label : runsOn) {
                if (!label.isTextual() || label.textValue().contains("${{")) {
                    return "multiline runner labels include a dynamic or non-text value";
                }
            }
            return null;
        }
        return "runner selection has an unsupported non-text shape";
    }

    private static String staticMatrixOsProblem(JsonNode job) {
        JsonNode matrix = job.path("strategy").get("matrix");
        if (matrix == null || !matrix.isObject()) {
            return "matrix.os runner is not backed by a statically authored matrix object in this job";
        }

        int staticValues = 0;
        JsonNode osAxis = matrix.get("os");
        if (osAxis != null) {
            if (!osAxis.isArray() || osAxis.isEmpty()) {
                return "matrix.os axis is not a non-empty static list";
            }
            for (JsonNode value : osAxis) {
                if (!isStaticText(value)) {
                    return "matrix.os axis contains a dynamic or non-text value";
                }
                staticValues++;
            }
        }

        JsonNode include = matrix.get("include");
        if (include != null) {
            if (!include.isArray() || include.isEmpty()) {
                return "matrix.include is not a non-empty static list";
            }
            for (JsonNode row : include) {
                if (!row.isObject()) {
                    return "matrix.include contains a dynamic or non-object row";
                }
                JsonNode value = row.get("os");
                if (value == null) {
                    if (osAxis == null) {
                        return "matrix.include row does not define os and no static os axis supplies it";
                    }
                    continue;
                }
                if (!isStaticText(value)) {
                    return "matrix.include os contains a dynamic or non-text value";
                }
                staticValues++;
            }
        }
        return staticValues == 0 ? "matrix.os has no statically authored values in this job" : null;
    }

    private static boolean isStaticText(JsonNode value) {
        return value.isTextual() && !value.textValue().contains("${{");
    }

    private static List<Integer> runsOnLines(String yaml) {
        var lines = new ArrayList<Integer>();
        List<String> source = yaml.lines().toList();
        for (int index = 0; index < source.size(); index++) {
            if (beforeYamlComment(source.get(index)).stripLeading().startsWith("runs-on:")) {
                lines.add(index + 1);
            }
        }
        return lines;
    }

    /** Removes a YAML comment while preserving hashes inside quoted scalars. */
    private static String beforeYamlComment(String line) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
            } else if (character == '"' && !singleQuoted && !isEscaped(line, index)) {
                doubleQuoted = !doubleQuoted;
            } else if (character == '#' && !singleQuoted && !doubleQuoted
                    && (index == 0 || Character.isWhitespace(line.charAt(index - 1)))) {
                return line.substring(0, index);
            }
        }
        return line;
    }

    private static boolean isEscaped(String text, int index) {
        int backslashes = 0;
        for (int cursor = index - 1; cursor >= 0 && text.charAt(cursor) == '\\'; cursor--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    /** The tracked workflow files — the denominator a workflow guard must not narrow by hand. */
    private static List<String> trackedWorkflows() {
        return CHECKOUT.tracked(".github").stream()
                .map(CHECKOUT::relative)
                .filter(path -> path.endsWith(".yml") || path.endsWith(".yaml"))
                .toList();
    }

}
