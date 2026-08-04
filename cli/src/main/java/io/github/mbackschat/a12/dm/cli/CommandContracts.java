package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;
import picocli.CommandLine.ParseResult;

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

    enum Effect {
        READ_ONLY, MODEL_WRITE, ARTIFACT_WRITE, MULTI_FILE_WRITE, TRANSACTION, DISPATCH;

        /**
         * Whether this effect can leave bytes on disk — the discriminator for who needs the persistence scope.
         * Only {@code READ_ONLY} cannot: a stdout-only artifact route is {@code READ_ONLY} by construction, since
         * {@code fileWriter} is selected on a <i>supplied</i> output option. {@code DISPATCH} counts because its
         * children write.
         */
        boolean writes() {
            return this != READ_ONLY;
        }
    }

    /**
     * What survives a failure — and, in {@link #SCOPE}, the failure classes that claim actually covers.
     *
     * <p>The scope is <b>published</b>, not merely commented here: {@code NO_WRITE} rides profiles whose effect
     * is a write, where a reader may fairly take it for an unconditional durability promise. It is not one, and
     * the boundary has to travel with the value rather than live in a doc the value does not point at.
     */
    enum FailurePersistence {
        NO_WRITE("the destination is not written at all"),
        ROLLBACK("every write this invocation already made is reverted, leaving each destination as it was"),
        PARTIAL("writes already made remain, and the ones after the failure do not happen"),
        CONTINUE_AFTER_CHILD_FAILURE("batch-level or preflight rejection writes nothing; once dispatch starts, "
                + "a child failure is retained, earlier writes are not rolled back, later children still run, "
                + "and any selected terminal artifact is still attempted");

        /**
         * The failure classes these values describe, and the two they deliberately do not. The excluded pair is a
         * withheld guarantee rather than a measured behavior: no write path in this tool does an atomic replace
         * ({@code Files.writeString}/{@code Files.write} truncate the destination in place), so neither case can
         * be promised to leave it unchanged.
         */
        static final String SCOPE = "These values describe what survives a failure this tool detects and "
                + "reports: a rejected invocation, a domain refusal, and a failing child op. They are not claims "
                + "about an I/O failure during the write itself, or about abnormal process termination — every "
                + "destination is truncated and rewritten in place, never atomically replaced, so in those two "
                + "cases the destination is not guaranteed to be left unchanged. Re-verify an interrupted run "
                + "with `" + Cli.TOOL_NAME + " model check`.";

        private final String meaning;

        FailurePersistence(String meaning) {
            this.meaning = meaning;
        }

        String meaning() {
            return meaning;
        }
    }

    enum DryRun { NOT_APPLICABLE, SUPPORTED, ACTIVE }
    enum AggregateSuccess {
        SELF, TRANSACTION_COMMITTED, TRANSACTION_SUCCEEDED, ALL_CHILD_EXITS_ZERO,
        ALL_CHILD_EXITS_ZERO_AND_ARTIFACT_WRITTEN
    }
    enum TopLevelOk {
        OUTCOME_SUCCEEDED, EXECUTION_COMPLETED, ALL_CHILD_EXITS_ZERO,
        ALL_CHILD_EXITS_ZERO_AND_ARTIFACT_WRITTEN
    }
    enum ReturnKind { RESULT_ENVELOPE, RAW_JSON, RAW_TEXT, EMPTY_STDOUT }
    enum Consistency { NONE, OPTIONAL, REQUIRED, NESTED, CHILD_DEFINED, NOT_EMITTED }
    enum Runtime { NONE, REQUIRED }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> RUNTIME_VERBS = Set.of("model eval", "model compute", "rule eval");
    private static final Set<String> RAW_JSON_VERBS = Set.of("manifest", "operators", "diagnostics", "model seed");
    private static final Set<String> REQUIRED_STATIC_READS = Set.of(
            "model check", "model describe", "model diff", "model read", "model usage",
            "rule read", "rule explain", "rule deps", "rule format",
            "computation read", "computation explain", "computation format", "where-used");
    private static final Set<String> OPTIONAL_STATIC_READS = Set.of("rule check", "workspace check");
    private static final List<String> META_WRITE_FLAGS = List.of(
            "--label", "--internal", "--internal-description", "--external", "--external-description",
            "--annotate", "--annotation", "--remove-internal", "--remove-internal-description",
            "--remove-external", "--remove-external-description", "--remove-annotation");
    private static final List<String> PATTERN_GENERATION_FLAGS = List.of(
            "--arg", "--code", "--message", "--name", "--apply", "--dry-run", "--output", "-o");

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

    /** Parsed parameters: which tokens were supplied, plus their resolved semantic values (including defaults). */
    private record Invocation(Set<String> supplied, Map<String, Object> values) {

        boolean supplied(String parameter) {
            return supplied.contains(parameter);
        }

        boolean set(String parameter) {
            Object value = values.get(parameter);
            return switch (value) {
                case null -> false;
                case Boolean bool -> bool;
                case CharSequence text -> !text.isEmpty();
                case java.util.Collection<?> collection -> !collection.isEmpty();
                case Map<?, ?> map -> !map.isEmpty();
                default -> true;
            };
        }

        String value(String parameter) {
            Object value = values.get(parameter);
            return value == null ? null : String.valueOf(value);
        }
    }

    /**
     * Structured invocation discriminator. {@code supplied}/{@code omitted} refer to command-line occurrence;
     * {@code anySet} and {@code values} refer to parsed semantic values, so {@code --dry-run=false} is a commit
     * and accepted case variants such as {@code --format TREE} still match the canonical {@code tree} branch.
     */
    record Selector(List<String> supplied, List<String> omitted, List<String> anySet, List<String> noneSet,
                    Map<String, List<String>> values) {
        Selector {
            supplied = List.copyOf(supplied);
            omitted = List.copyOf(omitted);
            anySet = List.copyOf(anySet);
            noneSet = List.copyOf(noneSet);
            values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        static Builder builder() {
            return new Builder();
        }

        static Selector always() {
            return builder().build();
        }

        boolean matches(Invocation invocation) {
            return supplied.stream().allMatch(invocation::supplied)
                    && omitted.stream().noneMatch(invocation::supplied)
                    && (anySet.isEmpty() || anySet.stream().anyMatch(invocation::set))
                    && noneSet.stream().noneMatch(invocation::set)
                    && values.entrySet().stream().allMatch(entry -> {
                        String actual = invocation.value(entry.getKey());
                        return actual != null && entry.getValue().stream().anyMatch(value -> value.equalsIgnoreCase(actual));
                    });
        }

        ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("valueComparison", "CASE_INSENSITIVE");
            addStrings(node.putArray("supplied"), supplied);
            addStrings(node.putArray("omitted"), omitted);
            addStrings(node.putArray("anySet"), anySet);
            addStrings(node.putArray("noneSet"), noneSet);
            ObjectNode valueNode = node.putObject("values");
            values.forEach((key, allowed) -> addStrings(valueNode.putArray(key), allowed));
            return node;
        }

        String describe() {
            List<String> clauses = new ArrayList<>();
            if (!supplied.isEmpty()) clauses.add("supplied " + supplied);
            if (!omitted.isEmpty()) clauses.add("omitted " + omitted);
            if (!anySet.isEmpty()) clauses.add("any set " + anySet);
            if (!noneSet.isEmpty()) clauses.add("none set " + noneSet);
            values.forEach((key, allowed) -> clauses.add(key + "=" + allowed));
            return clauses.isEmpty() ? "always" : String.join(", ", clauses);
        }

        static final class Builder {
            private final List<String> supplied = new ArrayList<>();
            private final List<String> omitted = new ArrayList<>();
            private final List<String> anySet = new ArrayList<>();
            private final List<String> noneSet = new ArrayList<>();
            private final Map<String, List<String>> values = new LinkedHashMap<>();

            Builder supplied(String... parameters) {
                if (parameters != null) supplied.addAll(List.of(parameters));
                return this;
            }

            Builder omitted(String... parameters) {
                if (parameters != null) omitted.addAll(List.of(parameters));
                return this;
            }

            Builder anySet(List<String> parameters) {
                if (parameters != null) anySet.addAll(parameters);
                return this;
            }

            Builder noneSet(List<String> parameters) {
                if (parameters != null) noneSet.addAll(parameters);
                return this;
            }

            Builder values(String parameter, String... allowed) {
                if (parameter != null && !parameter.isEmpty() && allowed != null && allowed.length > 0) {
                    values.put(parameter, List.of(allowed));
                }
                return this;
            }

            Selector build() {
                return new Selector(supplied, omitted, anySet, noneSet, values);
            }
        }
    }

    /** A conditional presentation of the primary return value, such as the global envelope-to-text renderer. */
    record ReturnView(Selector when, ReturnKind kind, String reason) {
        ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.set("when", when.toJson());
            node.put("kind", kind.name());
            node.put("reason", reason);
            return node;
        }
    }

    record Returns(ReturnKind kind, String schema, String reason, List<ReturnView> views) {
        Returns {
            views = List.copyOf(views);
        }

        static Builder builder(ReturnKind kind) {
            return new Builder(kind);
        }

        ObjectNode toJson() {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("kind", kind.name());
            if (schema != null) node.put("schema", schema);
            if (reason != null) node.put("reason", reason);
            ArrayNode viewNodes = node.putArray("views");
            views.forEach(view -> viewNodes.add(view.toJson()));
            return node;
        }

        String describe() {
            String modifiers = views.isEmpty() ? "" : views.stream()
                    .map(view -> "; " + view.when().describe() + " => " + view.kind())
                    .reduce("", String::concat);
            return kind + modifiers;
        }

        static final class Builder {
            private final ReturnKind kind;
            private String schema;
            private String reason;
            private final List<ReturnView> views = new ArrayList<>();

            private Builder(ReturnKind kind) {
                this.kind = kind;
            }

            Builder schema(String value) {
                if (value != null && !value.isEmpty()) schema = value;
                return this;
            }

            Builder reason(String value) {
                if (value != null && !value.isEmpty()) reason = value;
                return this;
            }

            Builder view(ReturnView value) {
                if (value != null) views.add(value);
                return this;
            }

            Returns build() {
                return new Returns(kind, schema, reason, views);
            }
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
            for (Object required : List.of(when, execution, effect, failurePersistence, dryRun, aggregateSuccess,
                    topLevelOk, returns, provenance)) {
                if (required == null) throw new IllegalArgumentException("profile '" + id + "' is incomplete");
            }
        }

        static Builder builder(String id) {
            return new Builder(id);
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
                    + ", returns=" + returns.describe() + ", provenance=" + provenance.consistency
                    + "/" + provenance.runtime;
        }

        static final class Builder {
            private final String id;
            private Selector when = Selector.always();
            private Execution execution = Execution.STATIC;
            private Effect effect = Effect.READ_ONLY;
            private FailurePersistence failurePersistence = FailurePersistence.NO_WRITE;
            private DryRun dryRun = DryRun.NOT_APPLICABLE;
            private AggregateSuccess aggregateSuccess = AggregateSuccess.SELF;
            private TopLevelOk topLevelOk = TopLevelOk.OUTCOME_SUCCEEDED;
            private Returns returns = envelope();
            private Provenance provenance = new Provenance(Consistency.NONE, Runtime.NONE);

            private Builder(String id) {
                this.id = id;
            }

            Builder when(Selector value) { if (value != null) when = value; return this; }
            Builder execution(Execution value) { if (value != null) execution = value; return this; }
            Builder effect(Effect value) { if (value != null) effect = value; return this; }
            Builder failurePersistence(FailurePersistence value) {
                if (value != null) failurePersistence = value;
                return this;
            }
            Builder dryRun(DryRun value) { if (value != null) dryRun = value; return this; }
            Builder aggregateSuccess(AggregateSuccess value) {
                if (value != null) aggregateSuccess = value;
                return this;
            }
            Builder topLevelOk(TopLevelOk value) { if (value != null) topLevelOk = value; return this; }
            Builder returns(Returns value) { if (value != null) returns = value; return this; }
            Builder provenance(Consistency consistency, Runtime runtime) {
                if (consistency != null && runtime != null) provenance = new Provenance(consistency, runtime);
                return this;
            }

            Profile build() {
                return new Profile(id, when, execution, effect, failurePersistence, dryRun, aggregateSuccess,
                        topLevelOk, returns, provenance);
            }
        }
    }

    private record ParsedInvocation(String verb, CommandSpec command, Invocation invocation) {}

    /** The live parser's resolved command and parameter values, without executing the invocation. */
    record ParsedParameters(String verb, Map<String, Object> values) {
        ParsedParameters {
            values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        String value(String parameter) {
            Object value = values.get(parameter);
            return value == null ? null : String.valueOf(value);
        }
    }

    private CommandContracts() {
    }

    static List<Profile> profiles(String verb, CommandSpec command) {
        Optional<Special> special = Special.forVerb(verb);
        if (special.isPresent()) return specialProfiles(special.get());
        if (command.optionsMap().containsKey("--dry-run")) {
            Consistency consistency = verb.equals("model normalize") ? Consistency.NONE : Consistency.REQUIRED;
            return modelEditorProfiles(consistency);
        }
        if (RUNTIME_VERBS.contains(verb)) {
            return List.of(Profile.builder("runtime")
                    .execution(Execution.DM_INTERPRETER_RUNTIME)
                    .provenance(Consistency.NONE, Runtime.REQUIRED)
                    .build());
        }
        if (RAW_JSON_VERBS.contains(verb)) {
            return List.of(Profile.builder("default")
                    .topLevelOk(TopLevelOk.EXECUTION_COMPLETED)
                    .returns(rawJson("the command's documented primary JSON value"))
                    .build());
        }
        Consistency consistency = REQUIRED_STATIC_READS.contains(verb) ? Consistency.REQUIRED
                : OPTIONAL_STATIC_READS.contains(verb) ? Consistency.OPTIONAL : Consistency.NONE;
        return List.of(Profile.builder("default").provenance(consistency, Runtime.NONE).build());
    }

    static ArrayNode toJson(String verb, CommandSpec command) {
        ArrayNode array = MAPPER.createArrayNode();
        profiles(verb, command).forEach(profile -> array.add(profile.toJson()));
        return array;
    }

    /**
     * The published meaning of the contract axes whose bare enum name under-describes what it claims. Derived
     * from the enum itself, so a new value or a sharpened meaning cannot leave the vocabulary behind.
     */
    static ObjectNode vocabulary() {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode persistence = root.putObject("failurePersistence");
        persistence.put("scope", FailurePersistence.SCOPE);
        ObjectNode values = persistence.putObject("values");
        for (FailurePersistence value : FailurePersistence.values()) {
            values.put(value.name(), value.meaning());
        }
        return root;
    }

    /**
     * Attach this leaf's operational owner to a response frame: always the profiles, and the persistence
     * vocabulary exactly when the leaf can write — that is the only case where a bare {@code NO_WRITE} can be
     * read as a promise the tool does not make. A whole-tree surface carries {@link #vocabulary()} once instead.
     */
    static void attachTo(ObjectNode frame, String verb, CommandSpec command) {
        List<Profile> profiles = profiles(verb, command);
        ArrayNode array = MAPPER.createArrayNode();
        profiles.forEach(profile -> array.add(profile.toJson()));
        frame.set("operationalProfiles", array);
        if (writes(profiles)) {
            frame.set("contractVocabulary", vocabulary());
        }
    }

    private static boolean writes(List<Profile> profiles) {
        return profiles.stream().anyMatch(profile -> profile.effect().writes());
    }

    /** Parse without executing and return exactly the profiles whose selectors describe the real parsed values. */
    static List<Profile> matchingProfiles(List<String> args) {
        ParsedInvocation parsed = parse(args);
        return profiles(parsed.verb(), parsed.command()).stream()
                .filter(profile -> profile.when().matches(parsed.invocation()))
                .toList();
    }

    static String qualifiedVerb(List<String> args) {
        return parse(args).verb();
    }

    /** Resolve one authored argv through the same picocli tree that will dispatch it, but do not execute it. */
    static ParsedParameters parsedParameters(List<String> args) {
        ParsedInvocation parsed = parse(args);
        return new ParsedParameters(parsed.verb(), parsed.invocation().values());
    }

    private static ParsedInvocation parse(List<String> args) {
        CommandLine root = new CommandLine(new RootCommand()).setExpandAtFiles(false);
        ParseResult result = root.parseArgs(args.toArray(String[]::new));
        Set<String> supplied = new LinkedHashSet<>();
        Map<String, Object> values = new LinkedHashMap<>();
        ParseResult cursor = result;
        CommandSpec leaf = result.commandSpec();
        while (cursor != null) {
            CommandSpec command = cursor.commandSpec();
            leaf = command;
            for (OptionSpec option : command.options()) {
                for (String name : option.names()) values.put(name, option.getValue());
            }
            for (OptionSpec option : cursor.matchedOptions()) {
                supplied.addAll(List.of(option.names()));
            }
            for (PositionalParamSpec positional : command.positionalParameters()) {
                values.put(positional.paramLabel(), positional.getValue());
            }
            for (PositionalParamSpec positional : cursor.matchedPositionals()) {
                supplied.add(positional.paramLabel());
            }
            cursor = cursor.subcommand();
        }
        return new ParsedInvocation(verbName(leaf), leaf, new Invocation(Set.copyOf(supplied),
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values))));
    }

    private static String verbName(CommandSpec leaf) {
        List<String> names = new ArrayList<>();
        CommandSpec cursor = leaf;
        while (cursor != null && cursor.parent() != null) {
            names.addFirst(cursor.name());
            cursor = cursor.parent();
        }
        return String.join(" ", names);
    }

    /** Add the operational facts to every leaf's native picocli help, from the same profiles as the manifest. */
    static void decorateHelp(CommandLine root) {
        decorateHelp(root, "", new LinkedHashSet<>());
    }

    private static void decorateHelp(CommandLine command, String parent, Set<CommandLine> visited) {
        if (!visited.add(command)) return;
        CommandSpec spec = command.getCommandSpec();
        String own = spec.name();
        String name = command.getParent() == null ? "" : parent.isEmpty() ? own : parent + " " + own;
        Set<CommandLine> children = new LinkedHashSet<>(command.getSubcommands().values());
        if (children.isEmpty()) {
            List<Profile> profiles = profiles(name, spec);
            List<String> footer = new ArrayList<>(profiles.stream().map(Profile::helpLine).toList());
            if (writes(profiles)) {
                footer.add("  persistence scope: " + FailurePersistence.SCOPE);
            }
            spec.usageMessage().footerHeading("%nOperational contract:%n");
            spec.usageMessage().footer(footer.toArray(String[]::new));
            return;
        }
        children.forEach(child -> decorateHelp(child, name, visited));
    }

    private static List<Profile> specialProfiles(Special special) {
        return switch (special) {
            case APPLY -> List.of(
                    Profile.builder("commit")
                            .when(semantic("--dry-run", "false"))
                            .effect(Effect.TRANSACTION)
                            .failurePersistence(FailurePersistence.ROLLBACK)
                            .dryRun(DryRun.SUPPORTED)
                            .aggregateSuccess(AggregateSuccess.TRANSACTION_COMMITTED)
                            .provenance(Consistency.REQUIRED, Runtime.NONE)
                            .build(),
                    preview("preview", semantic("--dry-run", "true"), AggregateSuccess.TRANSACTION_SUCCEEDED));
            case BATCH -> List.of(
                    Profile.builder("dispatch")
                            .when(omitted("--observations"))
                            .effect(Effect.DISPATCH)
                            .failurePersistence(FailurePersistence.CONTINUE_AFTER_CHILD_FAILURE)
                            .aggregateSuccess(AggregateSuccess.ALL_CHILD_EXITS_ZERO)
                            .topLevelOk(TopLevelOk.ALL_CHILD_EXITS_ZERO)
                            .provenance(Consistency.CHILD_DEFINED, Runtime.NONE)
                            .build(),
                    Profile.builder("dispatch-with-observations")
                            .when(supplied("--observations"))
                            .effect(Effect.ARTIFACT_WRITE)
                            .failurePersistence(FailurePersistence.CONTINUE_AFTER_CHILD_FAILURE)
                            .aggregateSuccess(AggregateSuccess.ALL_CHILD_EXITS_ZERO_AND_ARTIFACT_WRITTEN)
                            .topLevelOk(TopLevelOk.ALL_CHILD_EXITS_ZERO_AND_ARTIFACT_WRITTEN)
                            .provenance(Consistency.CHILD_DEFINED, Runtime.NONE)
                            .build());
            case GROUP_EXTRACT -> List.of(
                    Profile.builder("commit")
                            .when(semantic("--dry-run", "false"))
                            .effect(Effect.MULTI_FILE_WRITE)
                            .failurePersistence(FailurePersistence.PARTIAL)
                            .dryRun(DryRun.SUPPORTED)
                            .provenance(Consistency.REQUIRED, Runtime.NONE)
                            .build(),
                    preview("preview", semantic("--dry-run", "true"), AggregateSuccess.SELF));
            case META -> List.of(
                    read("read", Selector.builder().noneSet(META_WRITE_FLAGS).omitted("--dry-run", "--output", "-o").build(),
                            envelope(), Consistency.NONE),
                    modelWrite("commit", Selector.builder().anySet(META_WRITE_FLAGS)
                            .values("--dry-run", "false").build(), DryRun.SUPPORTED, Consistency.REQUIRED),
                    preview("preview", Selector.builder().anySet(META_WRITE_FLAGS)
                            .values("--dry-run", "true").build(), AggregateSuccess.SELF));
            case PATTERNS -> List.of(
                    read("catalog", Selector.builder().omitted("<patternId>").omitted(PATTERN_GENERATION_FLAGS
                            .toArray(String[]::new)).build(), envelope(), Consistency.NONE),
                    read("detail", Selector.builder().supplied("<patternId>").omitted(PATTERN_GENERATION_FLAGS
                            .toArray(String[]::new)).build(), envelope(), Consistency.NONE),
                    read("scaffold", Selector.builder().supplied("<patternId>", "--arg")
                            .values("--apply", "false").omitted("--dry-run", "--output", "-o")
                            .build(), envelope(), Consistency.REQUIRED),
                    modelWrite("commit", Selector.builder().supplied("<patternId>", "--arg")
                            .values("--apply", "true").values("--dry-run", "false").build(),
                            DryRun.SUPPORTED, Consistency.REQUIRED),
                    preview("preview", Selector.builder().supplied("<patternId>", "--arg")
                            .values("--apply", "true").values("--dry-run", "true").build(), AggregateSuccess.SELF));
            case MODEL_NEW -> stdoutAndFile("model artifact", Consistency.REQUIRED);
            case MODEL_EXPAND -> stdoutAndFile("expanded model artifact", Consistency.NONE);
            case MODEL_EXPORT_JSONSCHEMA -> stdoutAndFile("JSON Schema artifact", Consistency.REQUIRED);
            case PROFILE_SYNTHESIZE -> stdoutAndFile("synthesized model artifact", Consistency.REQUIRED);
            case PROFILE_EXTRACT -> List.of(
                    read("envelope", outputOmitted(), envelope(), Consistency.NONE),
                    fileWriter("file", outputSupplied(), Consistency.NONE));
            case MODEL_IMPORT_JSONSCHEMA -> List.of(
                    read("stdout", Selector.builder().omitted("--output", "-o", "--out-dir").build(),
                            rawJson("imported DM-JSON artifact"), Consistency.NOT_EMITTED),
                    fileWriter("file", Selector.builder().omitted("--out-dir").supplied("--output").build(),
                            Consistency.OPTIONAL),
                    Profile.builder("bundle")
                            .when(Selector.builder().supplied("--out-dir")
                                    .omitted("--component", "--output", "-o").build())
                            .effect(Effect.MULTI_FILE_WRITE)
                            .failurePersistence(FailurePersistence.PARTIAL)
                            .provenance(Consistency.OPTIONAL, Runtime.NONE)
                            .build());
            case EXPORT -> List.of(
                    read("stdout", omitted("--out-dir"), rawText("one selected export artifact"),
                            Consistency.NOT_EMITTED),
                    Profile.builder("directory")
                            .when(supplied("--out-dir"))
                            .effect(Effect.MULTI_FILE_WRITE)
                            .failurePersistence(FailurePersistence.PARTIAL)
                            .topLevelOk(TopLevelOk.EXECUTION_COMPLETED)
                            .returns(empty("writes all four export artifacts"))
                            .provenance(Consistency.NOT_EMITTED, Runtime.NONE)
                            .build());
            case WORKSPACE_LIST -> List.of(
                    read("json-default", Selector.builder().omitted("--format").values("--validate", "false").build(),
                            envelope(), Consistency.NONE),
                    read("json", Selector.builder().supplied("--format").values("--format", "json")
                            .values("--validate", "false").build(),
                            envelope(), Consistency.NONE),
                    read("json-validated", Selector.builder().omitted("--format").values("--validate", "true").build(),
                            envelope(), Consistency.NESTED),
                    read("json-validated-explicit", Selector.builder().supplied("--format").values("--format", "json")
                            .values("--validate", "true").build(), envelope(), Consistency.NESTED),
                    read("table", Selector.builder().supplied("--format").values("--format", "table")
                            .values("--validate", "false").build(),
                            rawText("human workspace table"), Consistency.NONE),
                    read("table-validated", Selector.builder().supplied("--format").values("--format", "table")
                            .values("--validate", "true").build(), rawText("human workspace table"),
                            Consistency.NOT_EMITTED));
            case WORKSPACE_GRAPH -> List.of(
                    read("json-default", omitted("--format"), envelope(), Consistency.NONE),
                    read("json", suppliedValue("--format", "json"), envelope(), Consistency.NONE),
                    read("tree", suppliedValue("--format", "tree"), rawText("human hierarchy"), Consistency.NONE),
                    read("dot", suppliedValue("--format", "dot"), rawText("Graphviz digraph"), Consistency.NONE));
            case MODEL_REPORT -> List.of(
                    read("report", Selector.builder().values("--tree", "false")
                            .omitted("--root", "--depth", "--include").build(), envelope(), Consistency.REQUIRED),
                    read("tree", semantic("--tree", "true"), envelope(), Consistency.NONE));
            case SCHEMA -> List.of(
                    read("orientation", omitted("<schema>"), rawText("schema orientation help"), Consistency.NONE),
                    read("contract", supplied("<schema>"), rawJson("JSON Schema, directional, or operational contract"),
                            Consistency.NONE));
        };
    }

    private static List<Profile> modelEditorProfiles(Consistency consistency) {
        return List.of(
                modelWrite("commit", semantic("--dry-run", "false"), DryRun.SUPPORTED, consistency),
                preview("preview", semantic("--dry-run", "true"), AggregateSuccess.SELF, consistency));
    }

    private static List<Profile> stdoutAndFile(String artifact, Consistency consistency) {
        return List.of(
                read("stdout", outputOmitted(), rawJson(artifact),
                        consistency == Consistency.NONE ? Consistency.NONE : Consistency.NOT_EMITTED),
                fileWriter("file", outputSupplied(), consistency));
    }

    private static Profile fileWriter(String id, Selector selector, Consistency consistency) {
        return Profile.builder(id)
                .when(selector)
                .effect(Effect.ARTIFACT_WRITE)
                .provenance(consistency, Runtime.NONE)
                .build();
    }

    private static Profile modelWrite(String id, Selector selector, DryRun dryRun, Consistency consistency) {
        return Profile.builder(id)
                .when(selector)
                .effect(Effect.MODEL_WRITE)
                .dryRun(dryRun)
                .provenance(consistency, Runtime.NONE)
                .build();
    }

    private static Profile preview(String id, Selector selector, AggregateSuccess aggregate) {
        return preview(id, selector, aggregate, Consistency.REQUIRED);
    }

    private static Profile preview(String id, Selector selector, AggregateSuccess aggregate,
            Consistency consistency) {
        return Profile.builder(id)
                .when(selector)
                .dryRun(DryRun.ACTIVE)
                .aggregateSuccess(aggregate)
                .provenance(consistency, Runtime.NONE)
                .build();
    }

    private static Profile read(String id, Selector selector, Returns returns, Consistency consistency) {
        TopLevelOk ok = returns.kind() == ReturnKind.RESULT_ENVELOPE
                ? TopLevelOk.OUTCOME_SUCCEEDED : TopLevelOk.EXECUTION_COMPLETED;
        return Profile.builder(id)
                .when(selector)
                .topLevelOk(ok)
                .returns(returns)
                .provenance(consistency, Runtime.NONE)
                .build();
    }

    private static Selector supplied(String... parameters) {
        return Selector.builder().supplied(parameters).build();
    }

    private static Selector omitted(String... parameters) {
        return Selector.builder().omitted(parameters).build();
    }

    private static Selector semantic(String parameter, String... values) {
        return Selector.builder().values(parameter, values).build();
    }

    private static Selector suppliedValue(String parameter, String... values) {
        return Selector.builder().supplied(parameter).values(parameter, values).build();
    }

    private static Selector outputSupplied() {
        return Selector.builder().supplied("--output").build();
    }

    private static Selector outputOmitted() {
        return Selector.builder().omitted("--output", "-o").build();
    }

    private static ReturnView textView() {
        return new ReturnView(semantic("--text", "true"), ReturnKind.RAW_TEXT,
                "global --text renders the result envelope as human-readable text");
    }

    private static Returns envelope() {
        return Returns.builder(ReturnKind.RESULT_ENVELOPE).schema("result").view(textView()).build();
    }

    private static Returns rawJson(String reason) {
        return Returns.builder(ReturnKind.RAW_JSON).reason(reason).build();
    }

    private static Returns rawText(String reason) {
        return Returns.builder(ReturnKind.RAW_TEXT).reason(reason).build();
    }

    private static Returns empty(String reason) {
        return Returns.builder(ReturnKind.EMPTY_STDOUT).reason(reason).build();
    }

    private static void addStrings(ArrayNode array, List<String> values) {
        values.forEach(array::add);
    }
}
