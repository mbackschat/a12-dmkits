package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/**
 * The authoritative operational contract for every public command invocation.
 *
 * <p>The command tree still owns names and parameters, and {@link SchemaCommand} still owns rich JSON shapes.
 * This registry owns the cross-surface facts that neither can derive: actual effect, persistence after a
 * command/domain/child failure, dry-run behavior, aggregate success, output form, and expected provenance.
 * Manifest, schema, and generated leaf help render this same typed owner.
 */
final class CommandContracts {

    enum Execution { STATIC, DM_INTERPRETER_RUNTIME }
    enum Effect { READ_ONLY, MODEL_WRITE, ARTIFACT_WRITE, MULTI_FILE_WRITE, TRANSACTION, DISPATCH }
    enum FailurePersistence { NO_WRITE, ROLLBACK, PARTIAL }
    enum DryRun { NOT_APPLICABLE, SUPPORTED, ACTIVE }
    enum AggregateSuccess { SELF, TRANSACTION_COMMITTED, TRANSACTION_SUCCEEDED, ALL_CHILD_EXITS_ZERO }
    enum TopLevelOk { OUTCOME_SUCCEEDED, EXECUTION_COMPLETED, ALL_CHILD_EXITS_ZERO }
    enum ReturnKind { RESULT_ENVELOPE, RAW_JSON, RAW_TEXT, EMPTY_STDOUT }
    enum Consistency { NONE, OPTIONAL, REQUIRED, NESTED, CHILD_DEFINED }
    enum Runtime { NONE, REQUIRED }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> RUNTIME_VERBS = Set.of("model eval", "model compute", "rule eval");
    private static final Set<String> RAW_JSON_VERBS = Set.of("manifest", "operators", "diagnostics", "model seed");
    private static final List<String> META_WRITE_FLAGS = List.of(
            "--label", "--internal", "--internal-description", "--external", "--external-description",
            "--annotate", "--annotation", "--remove-internal", "--remove-internal-description",
            "--remove-external", "--remove-external-description", "--remove-annotation");

    /** The finite list of commands whose semantics depend on their arguments. */
    private enum Special {
        APPLY("apply"),
        BATCH("batch"),
        EXPORT("export"),
        GROUP_EXTRACT("group extract"),
        META("meta"),
        MODEL_EXPAND("model expand"),
        MODEL_EXPORT_JSONSCHEMA("model export-jsonschema"),
        MODEL_IMPORT_JSONSCHEMA("model import-jsonschema"),
        MODEL_NEW("model new"),
        MODEL_REPORT("model report"),
        PATTERNS("patterns"),
        PROFILE_EXTRACT("profile extract"),
        PROFILE_SYNTHESIZE("profile synthesize"),
        SCHEMA("schema"),
        WORKSPACE_GRAPH("workspace graph"),
        WORKSPACE_LIST("workspace list");

        private final String verb;

        Special(String verb) {
            this.verb = verb;
        }

        static Optional<Special> forVerb(String verb) {
            return Arrays.stream(values()).filter(value -> value.verb.equals(verb)).findFirst();
        }
    }

    /** Structured invocation discriminator; parameter labels are used for positionals. */
    record Selector(List<String> present, List<String> absent, List<String> anyPresent,
                    Map<String, List<String>> values) {
        Selector {
            present = List.copyOf(present);
            absent = List.copyOf(absent);
            anyPresent = List.copyOf(anyPresent);
            values = Map.copyOf(values);
        }

        static Selector always() {
            return new Selector(List.of(), List.of(), List.of(), Map.of());
        }

        ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            addStrings(node.putArray("present"), present);
            addStrings(node.putArray("absent"), absent);
            addStrings(node.putArray("anyPresent"), anyPresent);
            ObjectNode valueNode = node.putObject("values");
            values.forEach((key, allowed) -> addStrings(valueNode.putArray(key), allowed));
            return node;
        }

        String describe() {
            List<String> clauses = new ArrayList<>();
            if (!present.isEmpty()) clauses.add("present " + present);
            if (!absent.isEmpty()) clauses.add("absent " + absent);
            if (!anyPresent.isEmpty()) clauses.add("any of " + anyPresent);
            values.forEach((key, allowed) -> clauses.add(key + "=" + allowed));
            return clauses.isEmpty() ? "always" : String.join(", ", clauses);
        }
    }

    record Returns(ReturnKind kind, String schema, String reason) {
        ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("kind", kind.name());
            if (schema != null) node.put("schema", schema);
            if (reason != null) node.put("reason", reason);
            return node;
        }
    }

    record Provenance(Consistency consistency, Runtime runtime) {
        ObjectNode toJson() {
            return MAPPER.createObjectNode()
                    .put("consistency", consistency.name())
                    .put("runtime", runtime.name());
        }
    }

    record Profile(String id, Selector when, Execution execution, Effect effect,
                   FailurePersistence failurePersistence, DryRun dryRun,
                   AggregateSuccess aggregateSuccess, TopLevelOk topLevelOk,
                   Returns returns, Provenance provenance) {
        Profile {
            if (id.isBlank()) throw new IllegalArgumentException("profile id must not be blank");
        }

        ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("id", id);
            node.set("when", when.toJson());
            node.put("execution", execution.name());
            node.put("effect", effect.name());
            node.put("failurePersistence", failurePersistence.name());
            node.put("dryRun", dryRun.name());
            node.put("aggregateSuccess", aggregateSuccess.name());
            node.put("topLevelOk", topLevelOk.name());
            node.set("returns", returns.toJson());
            node.set("provenance", provenance.toJson());
            return node;
        }

        String helpLine() {
            return "  " + id + " (" + when.describe() + "): " + execution + ", " + effect
                    + ", failure=" + failurePersistence + ", dryRun=" + dryRun
                    + ", aggregate=" + aggregateSuccess + ", ok=" + topLevelOk
                    + ", returns=" + returns.kind + ", provenance=" + provenance.consistency
                    + "/" + provenance.runtime;
        }
    }

    private CommandContracts() {
    }

    static List<Profile> profiles(String verb, CommandSpec command) {
        Optional<Special> special = Special.forVerb(verb);
        if (special.isPresent()) {
            return specialProfiles(special.get());
        }
        if (command.optionsMap().containsKey("--dry-run")) {
            Consistency consistency = verb.equals("model normalize") ? Consistency.NONE : Consistency.REQUIRED;
            return modelEditorProfiles(consistency);
        }
        if (RUNTIME_VERBS.contains(verb)) {
            return List.of(profile("runtime", Selector.always(), Execution.DM_INTERPRETER_RUNTIME,
                    Effect.READ_ONLY, FailurePersistence.NO_WRITE, DryRun.NOT_APPLICABLE,
                    AggregateSuccess.SELF, TopLevelOk.OUTCOME_SUCCEEDED, envelope(),
                    Consistency.NONE, Runtime.REQUIRED));
        }
        if (RAW_JSON_VERBS.contains(verb)) {
            return List.of(profile("default", Selector.always(), Execution.STATIC, Effect.READ_ONLY,
                    FailurePersistence.NO_WRITE, DryRun.NOT_APPLICABLE, AggregateSuccess.SELF,
                    TopLevelOk.EXECUTION_COMPLETED, rawJson("the command's documented primary JSON value"),
                    Consistency.NONE, Runtime.NONE));
        }
        return List.of(profile("default", Selector.always(), Execution.STATIC, Effect.READ_ONLY,
                FailurePersistence.NO_WRITE, DryRun.NOT_APPLICABLE, AggregateSuccess.SELF,
                TopLevelOk.OUTCOME_SUCCEEDED, envelope(), Consistency.OPTIONAL, Runtime.NONE));
    }

    static ArrayNode toJson(String verb, CommandSpec command) {
        ArrayNode array = MAPPER.createArrayNode();
        profiles(verb, command).forEach(profile -> array.add(profile.toJson()));
        return array;
    }

    /** Add the operational facts to every leaf's native picocli help, from the same profiles as the manifest. */
    static void decorateHelp(CommandLine root) {
        decorateHelp(root, "", new java.util.LinkedHashSet<>());
    }

    private static void decorateHelp(CommandLine command, String parent, Set<CommandLine> visited) {
        if (!visited.add(command)) return;
        CommandSpec spec = command.getCommandSpec();
        String own = spec.name();
        String name = command.getParent() == null ? "" : parent.isEmpty() ? own : parent + " " + own;
        Set<CommandLine> children = new java.util.LinkedHashSet<>(command.getSubcommands().values());
        if (children.isEmpty()) {
            List<Profile> profiles = profiles(name, spec);
            spec.usageMessage().footerHeading("%nOperational contract:%n");
            spec.usageMessage().footer(profiles.stream().map(Profile::helpLine).toArray(String[]::new));
            return;
        }
        children.forEach(child -> decorateHelp(child, name, visited));
    }

    private static List<Profile> specialProfiles(Special special) {
        return switch (special) {
            case APPLY -> List.of(
                    profile("commit", absent("--dry-run"), Execution.STATIC, Effect.TRANSACTION,
                            FailurePersistence.ROLLBACK, DryRun.SUPPORTED, AggregateSuccess.TRANSACTION_COMMITTED,
                            TopLevelOk.OUTCOME_SUCCEEDED, envelope(), Consistency.REQUIRED, Runtime.NONE),
                    preview("preview", present("--dry-run"), AggregateSuccess.TRANSACTION_SUCCEEDED));
            case BATCH -> List.of(profile("dispatch", Selector.always(), Execution.STATIC, Effect.DISPATCH,
                    FailurePersistence.PARTIAL, DryRun.NOT_APPLICABLE, AggregateSuccess.ALL_CHILD_EXITS_ZERO,
                    TopLevelOk.ALL_CHILD_EXITS_ZERO, envelope(), Consistency.CHILD_DEFINED, Runtime.NONE));
            case GROUP_EXTRACT -> List.of(
                    profile("commit", absent("--dry-run"), Execution.STATIC, Effect.MULTI_FILE_WRITE,
                            FailurePersistence.PARTIAL, DryRun.SUPPORTED, AggregateSuccess.SELF,
                            TopLevelOk.OUTCOME_SUCCEEDED, envelope(), Consistency.REQUIRED, Runtime.NONE),
                    preview("preview", present("--dry-run"), AggregateSuccess.SELF));
            case META -> List.of(
                    read("read", new Selector(List.of(), META_WRITE_FLAGS, List.of(), Map.of()),
                            envelope(), Consistency.OPTIONAL),
                    profile("commit", new Selector(List.of(), List.of("--dry-run"), META_WRITE_FLAGS, Map.of()),
                            Execution.STATIC, Effect.MODEL_WRITE, FailurePersistence.NO_WRITE, DryRun.SUPPORTED,
                            AggregateSuccess.SELF, TopLevelOk.OUTCOME_SUCCEEDED, envelope(),
                            Consistency.REQUIRED, Runtime.NONE),
                    preview("preview", new Selector(List.of("--dry-run"), List.of(), META_WRITE_FLAGS, Map.of()),
                            AggregateSuccess.SELF));
            case PATTERNS -> List.of(
                    read("catalog", absent("<patternId>"), envelope(), Consistency.NONE),
                    read("detail", new Selector(List.of("<patternId>"), List.of("--arg"), List.of(), Map.of()),
                            envelope(), Consistency.NONE),
                    read("scaffold", new Selector(List.of("--arg"), List.of("--apply"), List.of(), Map.of()),
                            envelope(), Consistency.REQUIRED),
                    profile("commit", new Selector(List.of("--arg", "--apply"), List.of("--dry-run"), List.of(), Map.of()),
                            Execution.STATIC, Effect.MODEL_WRITE, FailurePersistence.NO_WRITE, DryRun.SUPPORTED,
                            AggregateSuccess.SELF, TopLevelOk.OUTCOME_SUCCEEDED, envelope(),
                            Consistency.REQUIRED, Runtime.NONE),
                    preview("preview", present("--arg", "--apply", "--dry-run"), AggregateSuccess.SELF));
            case MODEL_NEW -> stdoutAndFile("model artifact", Consistency.REQUIRED);
            case MODEL_EXPAND -> stdoutAndFile("expanded model artifact", Consistency.NONE);
            case MODEL_EXPORT_JSONSCHEMA -> stdoutAndFile("JSON Schema artifact", Consistency.REQUIRED);
            case PROFILE_SYNTHESIZE -> stdoutAndFile("synthesized model artifact", Consistency.REQUIRED);
            case PROFILE_EXTRACT -> List.of(
                    read("envelope", outputAbsent(), envelope(), Consistency.NONE),
                    fileWriter("file", outputPresent(), Consistency.NONE));
            case MODEL_IMPORT_JSONSCHEMA -> List.of(
                    read("stdout", absent("--output", "-o", "--out-dir"), rawJson("imported DM-JSON artifact"),
                            Consistency.OPTIONAL),
                    fileWriter("file", new Selector(List.of(), List.of("--out-dir"), List.of("--output", "-o"), Map.of()),
                            Consistency.OPTIONAL),
                    profile("bundle", present("--out-dir"), Execution.STATIC, Effect.MULTI_FILE_WRITE,
                            FailurePersistence.PARTIAL, DryRun.NOT_APPLICABLE, AggregateSuccess.SELF,
                            TopLevelOk.OUTCOME_SUCCEEDED, envelope(), Consistency.OPTIONAL, Runtime.NONE));
            case EXPORT -> List.of(
                    read("stdout", absent("--out-dir"), rawText("one selected export artifact"), Consistency.REQUIRED),
                    profile("directory", present("--out-dir"), Execution.STATIC, Effect.MULTI_FILE_WRITE,
                            FailurePersistence.PARTIAL, DryRun.NOT_APPLICABLE, AggregateSuccess.SELF,
                            TopLevelOk.EXECUTION_COMPLETED, empty("writes all four export artifacts"),
                            Consistency.REQUIRED, Runtime.NONE));
            case WORKSPACE_LIST -> List.of(
                    read("json-default", absent("--format", "--validate"), envelope(), Consistency.NONE),
                    read("json", new Selector(List.of(), List.of("--validate"), List.of(),
                            Map.of("--format", List.of("json"))), envelope(), Consistency.NONE),
                    read("json-validated", new Selector(List.of("--validate"), List.of("--format"), List.of(), Map.of()),
                            envelope(), Consistency.NESTED),
                    read("json-validated-explicit", new Selector(List.of("--validate"), List.of(), List.of(),
                            Map.of("--format", List.of("json"))), envelope(), Consistency.NESTED),
                    read("table", new Selector(List.of(), List.of("--validate"), List.of(),
                            Map.of("--format", List.of("table"))), rawText("human workspace table"), Consistency.NONE),
                    read("table-validated", new Selector(List.of("--validate"), List.of(), List.of(),
                            Map.of("--format", List.of("table"))), rawText("human workspace table"),
                            Consistency.NESTED));
            case WORKSPACE_GRAPH -> List.of(
                    read("json-default", absent("--format"), envelope(), Consistency.NONE),
                    read("json", values("--format", "json"), envelope(), Consistency.NONE),
                    read("tree", values("--format", "tree"), rawText("human hierarchy"), Consistency.NONE),
                    read("dot", values("--format", "dot"), rawText("Graphviz digraph"), Consistency.NONE));
            case MODEL_REPORT -> List.of(
                    read("report", absent("--tree"), envelope(), Consistency.REQUIRED),
                    read("tree", present("--tree"), envelope(), Consistency.NONE));
            case SCHEMA -> List.of(
                    read("orientation", absent("<schema>"), rawText("schema orientation help"), Consistency.NONE),
                    read("contract", present("<schema>"), rawJson("JSON Schema or directional contract"),
                            Consistency.NONE));
        };
    }

    private static List<Profile> modelEditorProfiles(Consistency consistency) {
        return List.of(
                profile("commit", absent("--dry-run"), Execution.STATIC, Effect.MODEL_WRITE,
                        FailurePersistence.NO_WRITE, DryRun.SUPPORTED, AggregateSuccess.SELF,
                        TopLevelOk.OUTCOME_SUCCEEDED, envelope(), consistency, Runtime.NONE),
                preview("preview", present("--dry-run"), AggregateSuccess.SELF, consistency));
    }

    private static List<Profile> stdoutAndFile(String artifact, Consistency consistency) {
        return List.of(
                read("stdout", outputAbsent(), rawJson(artifact), consistency),
                fileWriter("file", outputPresent(), consistency));
    }

    private static Profile fileWriter(String id, Selector selector, Consistency consistency) {
        return profile(id, selector, Execution.STATIC, Effect.ARTIFACT_WRITE, FailurePersistence.NO_WRITE,
                DryRun.NOT_APPLICABLE, AggregateSuccess.SELF, TopLevelOk.OUTCOME_SUCCEEDED,
                envelope(), consistency, Runtime.NONE);
    }

    private static Profile preview(String id, Selector selector, AggregateSuccess aggregate) {
        return preview(id, selector, aggregate, Consistency.REQUIRED);
    }

    private static Profile preview(
            String id, Selector selector, AggregateSuccess aggregate, Consistency consistency) {
        return profile(id, selector, Execution.STATIC, Effect.READ_ONLY, FailurePersistence.NO_WRITE,
                DryRun.ACTIVE, aggregate, TopLevelOk.OUTCOME_SUCCEEDED, envelope(), consistency, Runtime.NONE);
    }

    private static Profile read(String id, Selector selector, Returns returns, Consistency consistency) {
        TopLevelOk ok = returns.kind == ReturnKind.RESULT_ENVELOPE
                ? TopLevelOk.OUTCOME_SUCCEEDED : TopLevelOk.EXECUTION_COMPLETED;
        return profile(id, selector, Execution.STATIC, Effect.READ_ONLY, FailurePersistence.NO_WRITE,
                DryRun.NOT_APPLICABLE, AggregateSuccess.SELF, ok, returns, consistency, Runtime.NONE);
    }

    private static Profile profile(String id, Selector selector, Execution execution, Effect effect,
            FailurePersistence failure, DryRun dryRun, AggregateSuccess aggregate, TopLevelOk ok,
            Returns returns, Consistency consistency, Runtime runtime) {
        return new Profile(id, selector, execution, effect, failure, dryRun, aggregate, ok, returns,
                new Provenance(consistency, runtime));
    }

    private static Selector present(String... flags) {
        return new Selector(List.of(flags), List.of(), List.of(), Map.of());
    }

    private static Selector absent(String... flags) {
        return new Selector(List.of(), List.of(flags), List.of(), Map.of());
    }

    private static Selector values(String flag, String... values) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(flag, List.of(values));
        return new Selector(List.of(), List.of(), List.of(), map);
    }

    private static Selector outputPresent() {
        return new Selector(List.of(), List.of(), List.of("--output", "-o"), Map.of());
    }

    private static Selector outputAbsent() {
        return absent("--output", "-o");
    }

    private static Returns envelope() {
        return new Returns(ReturnKind.RESULT_ENVELOPE, "result", null);
    }

    private static Returns rawJson(String reason) {
        return new Returns(ReturnKind.RAW_JSON, null, reason);
    }

    private static Returns rawText(String reason) {
        return new Returns(ReturnKind.RAW_TEXT, null, reason);
    }

    private static Returns empty(String reason) {
        return new Returns(ReturnKind.EMPTY_STDOUT, null, reason);
    }

    private static void addStrings(ArrayNode array, List<String> values) {
        values.forEach(array::add);
    }
}
