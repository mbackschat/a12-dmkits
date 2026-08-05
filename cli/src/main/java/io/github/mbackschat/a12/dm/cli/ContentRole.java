package io.github.mbackschat.a12.dm.cli;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Which way content flows through a parameter that names something on the filesystem (CG8).
 *
 * <p><b>Why this exists.</b> The manifest published {@code kind:"FILE"} on 12 of 320 parameters and the tag
 * disagreed with itself across structurally identical ones — {@code --output} occurs 45 times and carried it on
 * 4 — while saying nothing about direction: the same kind covered {@code --schema} (read) and {@code --output}
 * (written). A consumer could not tell which parameters carry content, or which way, and fell back on reading
 * each description. That tag is now gone; {@code contentRole} replaces it on all 82 path-bearing parameters.
 *
 * <p><b>Why classification is derived from the label.</b> The {@code paramLabel} is already consistent
 * ({@code <file>} ×47 and {@code <dir>} ×13 in the live tree, extension-bearing labels elsewhere), already
 * consumer-facing in {@code --help}, and mechanically inspectable — so file-ness is <b>derived</b> from it and
 * the role from the flag, in this one place, with nothing hand-listed per parameter.
 *
 * <p><i>Corrected 2026-08-05, after a cold review falsified two earlier justifications for this design. They
 * had claimed (a) that the field type could not carry file-ness because "every path parameter is declared
 * {@code String}" — false: {@code RootCommand.workspace} and {@code ModelDiffCommand}'s two workspace options
 * are {@code List<Path>}, and those three are exactly the {@code SCOPE_DIRECTORY} parameters; and (b) that
 * hoisting the duplicate {@code --output} declarations would lose verb-specific prose — false: of the 45
 * occurrences only 10 descriptions are distinct and 36 are byte-identical, and {@code
 * ReferenceRewritingRefactorCommand} already hoists four of them. Both figures came from a source-declaration
 * grep that under-matched, generalized from a six-declaration sample. The label lever above stands on its own
 * measurement; those two rejections do not support it, and a type-based or partly-hoisted design is a live
 * option for a later slice rather than something ruled out.</i>
 *
 * <p>The anti-drift property comes from the guard, not from the annotation that is deliberately absent:
 * {@code ParamContentRoleTest} walks the complete live command tree and fails when a file-shaped parameter
 * cannot be classified. A new one therefore cannot ship unpublished.
 */
enum ContentRole {

    /** The tool reads this path and consumes what it finds — a spec, a document instance, a JS impl directory. */
    INBOUND_CONTENT("content the tool reads from this path"),

    /** The tool writes here. Supplying it is what diverts a write off its default destination. */
    OUTBOUND_DESTINATION("a destination the tool writes to"),

    /**
     * A directory searched to resolve a model's references. It deliberately does <b>not</b> claim nothing in it
     * is written: {@code group extract} writes the new sub-model into the first {@code -w} root
     * ({@code GroupExtractCommand} → {@code workspace.roots(model).get(0).resolve(...)}), so a "never written"
     * guarantee here was false. Whether a given invocation writes is a <em>per-verb</em> fact and lives in
     * {@code operationalProfiles} ({@link CommandContracts}); a role describes the parameter, not the verb.
     */
    SCOPE_DIRECTORY("a directory searched to resolve a model's references"),

    /**
     * The model the verb operates on. Also does not claim a write: {@code -m} is inherited by every leaf,
     * including the 34 that are read-only in every profile, so "rewritten in place" was false for those.
     * Read {@code operationalProfiles} for what a specific invocation does to it.
     */
    SUBJECT_MODEL("the model the verb operates on");

    private final String meaning;

    ContentRole(String meaning) {
        this.meaning = meaning;
    }

    String meaning() {
        return meaning;
    }

    /** A label naming a concrete file: {@code <file>}, {@code <ops.json>}, {@code <roles.yaml>}, {@code <baseFile>}. */
    private static final Pattern NAMED_FILE = Pattern.compile("<([A-Za-z0-9_.-]+\\.(?:json|yaml|yml)|\\w*[Ff]ile)>");

    /**
     * Flags the tool writes to. {@code --observations} is here because it is a destination that is <b>not</b>
     * named like one — {@code BatchObservationCapture} does {@code Files.writeString(destination, …)} — and the
     * fallback below would otherwise call it inbound. That miss is why {@code ParamContentRoleTest}
     * cross-checks each role against the parameter's own description: the flag name alone cannot decide this.
     */
    private static final Set<String> DESTINATION_FLAGS = Set.of("--output", "-o", "--out-dir", "--observations");

    /** Workspace roots: searched to resolve a model's includes, never written. */
    private static final Set<String> SCOPE_FLAGS = Set.of("-w", "--workspace", "--base-workspace", "--head-workspace");

    private static final Set<String> SUBJECT_FLAGS = Set.of("-m", "--model");

    /**
     * Whether this parameter names something on the filesystem — the <b>independent</b> half of the guard, read
     * from the label rather than from any classification, so it can catch a parameter the rules below miss.
     *
     * <p>{@code <path>} is deliberately excluded: measured across its 8 uses ({@code --parent}, {@code --group},
     * {@code --root}) it is a <em>model</em> name-path, never a filesystem path. Same for {@code <schema>} (a
     * schema name), {@code <artifact>} (an artifact kind), {@code <source>}, {@code <reference>} and
     * {@code <json>} (inline JSON).
     */
    static boolean fileShaped(String paramLabel) {
        if (paramLabel == null) {
            return false;
        }
        return paramLabel.equals("<file>") || paramLabel.equals("<dir>") || NAMED_FILE.matcher(paramLabel).matches();
    }

    /**
     * The role of a file-shaped parameter, or empty when it is not file-shaped at all. An empty result on a
     * file-shaped parameter is impossible by construction here (the final branch is total) — the guard still
     * checks it, because a future label form could change what {@code fileShaped} admits.
     */
    static Optional<ContentRole> of(ArgSpec param) {
        if (!fileShaped(param.paramLabel())) {
            return Optional.empty();
        }
        List<String> names = param instanceof OptionSpec option ? List.of(option.names()) : List.of();
        if (names.stream().anyMatch(DESTINATION_FLAGS::contains)) {
            return Optional.of(OUTBOUND_DESTINATION);
        }
        if (names.stream().anyMatch(SUBJECT_FLAGS::contains)) {
            return Optional.of(SUBJECT_MODEL);
        }
        if (names.stream().anyMatch(SCOPE_FLAGS::contains)) {
            return Optional.of(SCOPE_DIRECTORY);
        }
        // A bare `<dir>` positional is the workspace a `workspace` verb scans; a `<dir>` OPTION that is not a
        // destination or a workspace root is inbound content (the `--custom-*-js` impl directories).
        if (names.isEmpty() && "<dir>".equals(param.paramLabel())) {
            return Optional.of(SCOPE_DIRECTORY);
        }
        return Optional.of(INBOUND_CONTENT);
    }
}
