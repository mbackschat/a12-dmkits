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
 * <p><b>Why this exists.</b> The manifest published {@code kind:"FILE"} on 12 of 317 parameters and the tag
 * disagreed with itself across structurally identical ones — {@code --output} occurs 45 times and carried it on
 * 4 — while saying nothing about direction: the same kind covered {@code --schema} (read) and {@code --output}
 * (written). A consumer could not tell which parameters carry content, or which way, and fell back on reading
 * each description.
 *
 * <p><b>Why classification lives here rather than on each declaration.</b> Two candidate levers were measured
 * and rejected. The field <em>type</em> cannot carry it: every path parameter in the tool is declared
 * {@code String} and converted at the point of use, so typing them {@code Path} would be a wide refactor that
 * also moves conversion errors into picocli — a behaviour change on a published surface. Hoisting the 45
 * duplicate {@code --output} declarations into one shared mixin would lose information: each carries verb
 * specific prose ("write the synthesized DM-JSON model here instead of stdout"), and a sample of six
 * declarations had six distinct descriptions. What <em>is</em> reliable is the {@code paramLabel} — already
 * consistent ({@code <file>} ×43, {@code <dir>} ×11, extension-bearing labels elsewhere), already consumer
 * facing in {@code --help}, and mechanically inspectable. So file-ness is <b>derived</b> from the label and the
 * role from the flag, in this one place; nothing is hand-listed per parameter.
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

    /** A directory searched to resolve references; nothing in it is written. */
    SCOPE_DIRECTORY("a directory searched to resolve references, never written"),

    /** The model under operation: read, and written back in place unless a destination is supplied. */
    SUBJECT_MODEL("the model operated on — read, and rewritten in place unless a destination is given");

    private final String meaning;

    ContentRole(String meaning) {
        this.meaning = meaning;
    }

    String meaning() {
        return meaning;
    }

    /** A label naming a concrete file: {@code <file>}, {@code <ops.json>}, {@code <roles.yaml>}, {@code <baseFile>}. */
    private static final Pattern NAMED_FILE = Pattern.compile("<([A-Za-z0-9_.-]+\\.(?:json|yaml|yml)|\\w*[Ff]ile)>");

    private static final Set<String> DESTINATION_FLAGS = Set.of("--output", "-o", "--out-dir");

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
