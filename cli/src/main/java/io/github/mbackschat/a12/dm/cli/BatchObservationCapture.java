package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Builds the deliberately narrow, deterministic observation artifact for static kernel candidate verdicts. */
final class BatchObservationCapture {

    private static final int SCHEMA_VERSION = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Rejection(int index, String message, String fix) { }

    static final class InvalidInputException extends Exception {
        private final Rejection rejection;

        InvalidInputException(Rejection rejection) {
            super(rejection.message());
            this.rejection = rejection;
        }

        Rejection rejection() {
            return rejection;
        }
    }

    private record PreparedObservation(String id, String inputHash) { }

    static final class Plan {
        private final List<PreparedObservation> observations;
        private final Path model;
        private final Path destination;

        private Plan(List<PreparedObservation> observations, Path model, Path destination) {
            this.observations = List.copyOf(observations);
            this.model = model;
            this.destination = destination;
        }

        void write(ArrayNode childResults) throws IOException {
            if (childResults.size() != observations.size()) {
                throw new IllegalStateException("observation/result cardinality drift");
            }
            VersionInfo version = VersionInfo.current();
            ObjectNode artifact = MAPPER.createObjectNode();
            artifact.put("schemaVersion", SCHEMA_VERSION);
            artifact.put("dmtool", version.rulekit());
            artifact.put("kernelBuilt", version.kernelBuiltAgainst());
            artifact.put("kernelRuntime", version.kernelRuntime());
            ObjectNode source = artifact.putObject("source");
            source.put("revision", version.sourceRevision());
            source.put("state", version.sourceState().name());
            if (model != null && Files.isRegularFile(model)) {
                artifact.put("model", sha256(Files.readAllBytes(model)));
            }
            ArrayNode recorded = artifact.putArray("observations");
            for (int index = 0; index < observations.size(); index++) {
                PreparedObservation prepared = observations.get(index);
                JsonNode child = childResults.get(index);
                JsonNode result = child.path("result");
                ObjectNode observation = recorded.addObject();
                observation.put("id", prepared.id());
                observation.put("inputHash", prepared.inputHash());
                observation.put("ok", result.has("ok")
                        ? result.path("ok").asBoolean()
                        : child.path("exit").asInt(1) == 0);
                if (result.has("valid")) {
                    observation.put("valid", result.path("valid").asBoolean());
                }
                ArrayNode codes = observation.putArray("codes");
                for (JsonNode diagnostic : result.path("diagnostics")) {
                    if (diagnostic.hasNonNull("code")) {
                        codes.add(diagnostic.path("code").asText());
                    }
                }
            }
            Files.writeString(destination, artifact.toPrettyString() + "\n", StandardCharsets.UTF_8);
        }

        String output() {
            return destination.toString();
        }
    }

    private BatchObservationCapture() { }

    static Plan prepare(JsonNode ops, Path opsFile, Path destination, String batchModel)
            throws InvalidInputException, IOException {
        List<PreparedObservation> prepared = new ArrayList<>();
        Set<Path> models = new LinkedHashSet<>();
        boolean childWithoutModel = false;
        Set<Path> protectedInputs = new LinkedHashSet<>();
        protectedInputs.add(normalize(opsFile));
        if (batchModel != null) {
            protectedInputs.add(normalize(Path.of(batchModel)));
        }
        for (int index = 0; index < ops.size(); index++) {
            JsonNode op = ops.get(index);
            if (!op.hasNonNull("id") || op.path("id").asText().isBlank()) {
                throw invalid(index, "observation item " + index + " needs a non-blank caller id",
                        "add an `id` to every batch item captured with --observations");
            }
            List<String> argv = childArgv(op);
            CommandContracts.ParsedParameters parsed;
            try {
                parsed = CommandContracts.parsedParameters(argv);
            } catch (RuntimeException invalidInvocation) {
                throw invalid(index, "observation item " + index + " is not a valid child invocation",
                        "fix the child invocation before capturing observations: " + invalidInvocation.getMessage());
            }
            requireJsonEnvelope(index, parsed);
            byte[] authoredInput;
            switch (parsed.verb()) {
                case "rule check" -> {
                    String condition = parsed.value("--condition");
                    if (condition == null) {
                        throw invalid(index, "rule check observation " + index + " has no --condition",
                                "pass the exact authored condition with --condition");
                    }
                    authoredInput = condition.getBytes(StandardCharsets.UTF_8);
                }
                case "computation add" -> {
                    requireDryRun(index, parsed);
                    String spec = parsed.value("<computation-spec.json>");
                    authoredInput = specBytes(index, spec, protectedInputs);
                }
                case "computation modify" -> {
                    requireDryRun(index, parsed);
                    String spec = parsed.value("--spec");
                    authoredInput = specBytes(index, spec, protectedInputs);
                }
                default -> throw invalid(index,
                        "observation item " + index + " is not a supported static kernel candidate: " + parsed.verb(),
                        "use `rule check --condition ...`, `computation add <spec> --dry-run`, or "
                                + "`computation modify ... --spec <spec> --dry-run`; runtime/interpreter results are excluded");
            }
            String ownModel = parsed.value("--model");
            String effectiveModel = ownModel != null ? ownModel : batchModel;
            if (effectiveModel != null) {
                Path model = normalize(Path.of(effectiveModel));
                models.add(model);
                protectedInputs.add(model);
            } else {
                childWithoutModel = true;
            }
            prepared.add(new PreparedObservation(op.path("id").asText(), sha256(authoredInput)));
        }
        Path normalizedDestination = normalize(destination);
        for (Path input : protectedInputs) {
            if (sameFile(normalizedDestination, input)) {
                throw invalid(-1, "--observations would overwrite an authored input",
                        "choose an observations file distinct from the ops, model, and computation-spec files");
            }
        }
        Path singleModel = !childWithoutModel && models.size() == 1 ? models.iterator().next() : null;
        return new Plan(prepared, singleModel, normalizedDestination);
    }

    private static byte[] specBytes(int index, String spec, Set<Path> protectedInputs)
            throws InvalidInputException {
        if (spec == null || spec.isBlank()) {
            throw invalid(index, "computation observation " + index + " has no computation-spec file",
                    "pass the authored computation-spec file through the command's documented parameter");
        }
        if (JsonInput.isStdin(spec)) {
            throw invalid(index, "computation observation " + index + " takes its spec from stdin",
                    "pass the authored computation-spec as a file; the observation artifact records its exact "
                            + "bytes, which a consumed stream cannot provide");
        }
        Path path = normalize(Path.of(spec));
        protectedInputs.add(path);
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw invalid(index, "cannot read computation-spec bytes for observation " + index,
                    "make the authored computation-spec file readable: " + e.getMessage());
        }
    }

    private static void requireDryRun(int index, CommandContracts.ParsedParameters parsed)
            throws InvalidInputException {
        if (!Boolean.parseBoolean(parsed.value("--dry-run"))) {
            throw invalid(index, "computation observation " + index + " is not a dry-run kernel verdict",
                    "add `--dry-run`; observation capture never persists a computation candidate");
        }
    }

    private static void requireJsonEnvelope(int index, CommandContracts.ParsedParameters parsed)
            throws InvalidInputException {
        if (Boolean.parseBoolean(parsed.value("--text")) || Boolean.parseBoolean(parsed.value("--help"))
                || Boolean.parseBoolean(parsed.value("--version"))) {
            throw invalid(index, "observation item " + index + " would bypass the JSON result envelope",
                    "remove --text/--help/--version so valid and diagnostic codes remain observable");
        }
    }

    private static List<String> childArgv(JsonNode op) {
        List<String> argv = new ArrayList<>();
        argv.add(op.path("verb").asText());
        for (JsonNode arg : op.path("args")) {
            argv.add(arg.asText());
        }
        return argv;
    }

    private static InvalidInputException invalid(int index, String message, String fix) {
        return new InvalidInputException(new Rejection(index, message, fix));
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static boolean sameFile(Path left, Path right) throws IOException {
        if (left.equals(right)) {
            return true;
        }
        return Files.exists(left) && Files.exists(right) && Files.isSameFile(left, right);
    }

    private static String sha256(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("the JDK has no SHA-256 implementation", impossible);
        }
    }
}
