package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

/**
 * A model-editing verb ends through {@link BaseCommand#emitMutation}, not through its own copy of the tail.
 *
 * <p><b>The denominator is the live editing surface, not a writer spelling.</b> Every editing leaf advertises
 * {@code --dry-run}; the guard walks the real picocli tree and requires each such leaf to use {@link ModelWrite}
 * or carry a reasoned custom-tail disposition. The old guard searched for the literal {@code Files.writeString(},
 * so {@code Files.write(}, a buffered writer, or a new helper could all recreate the tail without entering its
 * denominator. Artifact writers have no {@code --dry-run} and are correctly outside this editing invariant.
 *
 * <p>The behaviour a duplicate tail would get wrong is covered separately by {@link MutationWriteFailureTest}
 * and {@link MutationEvidenceTest}. This source guard owns the structural invariant that an editing command has
 * exactly one of two dispositions: shared tail or reviewed custom tail.
 */
class MutationTailSingleSourceTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path COMMANDS = SOURCES.resolve("io/github/mbackschat/a12/dm/cli");
    private static final String SHARED_TAIL = "ModelWrite.of(";

    /** Editing routes whose output shape cannot use the one-model {@code emitMutation} ending. */
    private static final Map<String, String> CUSTOM_TAILS = Map.of(
            "apply", "one transaction envelope over a staged sequence, not a single edit",
            "group extract", "writes two artifacts — the slimmed base and the extracted sub-model",
            "patterns", "four routes, two of which are a verdict and a rejection rather than a write");

    @Test
    void everyLiveEditingVerbUsesTheSharedTailOrHasAReasonedDisposition() throws Exception {
        List<String> offenders = unclassifiedEditingVerbs(liveEditingVerbs());
        assertTrue(offenders.isEmpty(),
                () -> "these live --dry-run verbs do not end through BaseCommand.emitMutation and have no "
                        + "reasoned custom-tail disposition: " + offenders + ". Use ModelWrite, or add the "
                        + "route to CUSTOM_TAILS with the reason its output cannot use the shared ending");
    }

    @Test
    void theCustomTailLedgerIsHonest() throws Exception {
        Map<String, EditingVerb> live = new LinkedHashMap<>();
        for (EditingVerb verb : liveEditingVerbs()) {
            live.put(verb.name(), verb);
        }
        for (var entry : CUSTOM_TAILS.entrySet()) {
            EditingVerb verb = live.get(entry.getKey());
            assertTrue(verb != null,
                    () -> "CUSTOM_TAILS names a route absent from the live --dry-run surface: " + entry.getKey());
            assertFalse(entry.getValue().isBlank(), () -> entry.getKey() + " needs a reason");
            assertFalse(verb.usesSharedTail(),
                    () -> entry.getKey() + " now uses the shared tail — remove its stale custom disposition");
        }
    }

    @Test
    void everySharedTailUserIsARecognisedLiveEditingVerb() throws Exception {
        Set<String> liveSources = new LinkedHashSet<>();
        for (EditingVerb verb : liveEditingVerbs()) {
            liveSources.addAll(verb.sharedTailSourceKeys());
        }

        Set<String> sourceUsers = new LinkedHashSet<>();
        try (var paths = Files.walk(COMMANDS)) {
            for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
                if (Files.readString(path).contains(SHARED_TAIL)) {
                    sourceUsers.add(SOURCES.relativize(path).toString());
                }
            }
        }
        assertEquals(sourceUsers, liveSources,
                "a ModelWrite user outside the live --dry-run command surface is not an editing verb disposition");
        assertFalse(liveSources.isEmpty(), "the shared ending must actually carry the editing surface");
    }

    @Test
    void anotherWriteSpellingCannotEscapeTheEditingDenominator() {
        assertEquals(List.of("probe mutate"), unclassifiedEditingVerbs(List.of(
                new EditingVerb("probe mutate", Map.of("ProbeCommand.java", "Files.write(output, bytes);")))),
                "the denominator is --dry-run membership, so the writer implementation cannot hide a new tail");
    }

    private static List<String> unclassifiedEditingVerbs(List<EditingVerb> verbs) {
        return verbs.stream()
                .filter(verb -> !verb.usesSharedTail())
                .filter(verb -> !CUSTOM_TAILS.containsKey(verb.name()))
                .map(EditingVerb::name)
                .sorted()
                .toList();
    }

    private static List<EditingVerb> liveEditingVerbs() throws Exception {
        List<EditingVerb> verbs = new ArrayList<>();
        collectEditingLeaves(new CommandLine(new RootCommand()), "", new LinkedHashSet<>(), verbs);
        return verbs.stream().sorted((left, right) -> left.name().compareTo(right.name())).toList();
    }

    private static void collectEditingLeaves(
            CommandLine command,
            String parent,
            Set<CommandLine> visited,
            List<EditingVerb> verbs) throws Exception {
        if (!visited.add(command)) {
            return; // aliases can point at the same command object
        }
        String ownName = command.getCommandSpec().name();
        String name = command.getParent() == null ? "" : parent.isEmpty() ? ownName : parent + " " + ownName;
        Set<CommandLine> children = new LinkedHashSet<>(command.getSubcommands().values());
        if (!children.isEmpty()) {
            for (CommandLine child : children) {
                collectEditingLeaves(child, name, visited, verbs);
            }
            return;
        }
        if (!command.getCommandSpec().optionsMap().containsKey("--dry-run")) {
            return;
        }

        Map<String, String> implementationSources = new LinkedHashMap<>();
        for (Class<?> type = command.getCommand().getClass();
                type != null && type.getPackageName().startsWith("io.github.mbackschat.a12.dm.cli");
                type = type.getSuperclass()) {
            Path source = SOURCES.resolve(type.getName().replace('.', '/') + ".java");
            assertTrue(Files.exists(source),
                    () -> name + " is a live --dry-run verb whose implementation source cannot be located: " + source);
            implementationSources.put(SOURCES.relativize(source).toString(), Files.readString(source));
        }
        verbs.add(new EditingVerb(name, implementationSources));
    }

    private record EditingVerb(String name, Map<String, String> sources) {

        private boolean usesSharedTail() {
            return sources.values().stream().anyMatch(source -> source.contains(SHARED_TAIL));
        }

        private Set<String> sharedTailSourceKeys() {
            Set<String> keys = new LinkedHashSet<>();
            sources.forEach((key, source) -> {
                if (source.contains(SHARED_TAIL)) {
                    keys.add(key);
                }
            });
            return keys;
        }
    }
}
