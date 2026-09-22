package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

/**
 * A model-editing verb ends through {@link BaseCommand#emitMutation}, not through its own copy of the tail.
 *
 * <p><b>The denominator is the live editing surface, not a writer spelling.</b> Every editing leaf advertises
 * {@code --dry-run}; the guard walks the real picocli tree and reconciles it with the independently authored
 * preview demonstrations. Each leaf must structurally return {@code emitMutation(ModelWrite.of(...))} in the
 * parsed Java syntax tree or carry a reasoned custom-tail disposition. Comments, strings, and unused
 * {@code ModelWrite} constructions are not syntax-tree dispositions. Artifact writers have no preview
 * demonstration and are correctly outside this editing invariant.
 *
 * <p>The behaviour a duplicate tail would get wrong is covered separately by {@link MutationWriteFailureTest}
 * and {@link MutationEvidenceTest}. This source guard owns the structural invariant that an editing command has
 * exactly one of two dispositions: shared tail or reviewed custom tail.
 */
class MutationTailSingleSourceTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Path COMMANDS = SOURCES.resolve("io/github/mbackschat/a12/dm/cli");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Editing routes whose output shape cannot use the one-model {@code emitMutation} ending. */
    private static final Map<String, String> CUSTOM_TAILS = Map.of(
            "apply", "one transaction envelope over a staged sequence, not a single edit",
            "group extract", "writes two artifacts — the slimmed base and the extracted sub-model",
            "patterns", "four routes, two of which are a verdict and a rejection rather than a write");

    /** Editing routes whose one-token leaf also has read modes. Rich-input editors derive from SchemaCommand. */
    private static final Map<String, String> NON_PREVIEW_DEMONSTRATION_EDITORS = Map.of(
            "meta", "the same one-token leaf carries read and modify modes");

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
                if (structurallyReturnsSharedTail(path.toString(), Files.readString(path))) {
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

    @Test
    void aCommentCannotPretendToUseTheSharedTail() {
        EditingVerb verb = new EditingVerb("probe mutate", Map.of(
                "ProbeCommand.java", "class Probe { void run() { /* ModelWrite.of(fake) */ } }"));
        assertFalse(verb.usesSharedTail(), "a source comment is not a structural disposition");
    }

    @Test
    void anUnusedModelWriteCannotPretendToReachTheSharedTail() {
        EditingVerb verb = new EditingVerb("probe mutate", Map.of(
                "ProbeCommand.java", "class Probe { void run() { ModelWrite.of(fake); writePrivately(); } }"));
        assertFalse(verb.usesSharedTail(), "constructing an unused ModelWrite does not reach emitMutation");
    }

    @Test
    void anUnreachableSharedTailCannotHideTheRealPrivateWriter() {
        EditingVerb verb = new EditingVerb("probe mutate", Map.of(
                "ProbeCommand.java", "class Probe { Integer call() { writePrivately(); "
                        + "if (false) return emitMutation(ModelWrite.of(fake)); return 0; } }"));
        assertFalse(verb.usesSharedTail(), "an unreachable shared-tail return is not the command's terminal path");
    }

    @Test
    void aSharedTailInAnUnusedHelperCannotHideTheRealPrivateWriter() {
        EditingVerb verb = new EditingVerb("probe mutate", Map.of(
                "ProbeCommand.java", "class Probe { Integer call() { writePrivately(); return 0; } "
                        + "Integer unused() { return emitMutation(ModelWrite.of(fake)); } }"));
        assertFalse(verb.usesSharedTail(), "a helper the command never invokes is not its mutation tail");
    }

    @Test
    void richInputEditorsAreDerivedFromTheSchemaRegistry() {
        assertTrue(schemaDemonstratedEditors().containsAll(Set.of(
                "rule add", "computation add", "computation modify", "field add", "field modify",
                "typedef add", "typedef modify")),
                "every rich-input editor must enter the authored denominator without a hand-copied name list");
        assertFalse(schemaDemonstratedEditors().contains("profile synthesize"),
                "a schema-described artifact producer is not a model editor");
    }

    @Test
    void theLiveDryRunSurfaceEqualsTheIndependentlyAuthoredEditingSurface() throws Exception {
        Set<String> live = new LinkedHashSet<>();
        for (EditingVerb verb : liveEditingVerbs()) {
            live.add(verb.name());
        }

        Set<String> authored = new LinkedHashSet<>(NON_PREVIEW_DEMONSTRATION_EDITORS.keySet());
        authored.addAll(schemaDemonstratedEditors());
        authored.addAll(CUSTOM_TAILS.keySet());
        try (InputStream input = MutationTailSingleSourceTest.class.getResourceAsStream("/verb-examples.json")) {
            assertTrue(input != null, "verb-examples.json must remain a packaged resource");
            JsonNode examples = MAPPER.readTree(input);
            examples.fields().forEachRemaining(entry -> {
                if (entry.getValue().isObject() && "preview".equals(entry.getValue().path("expect").asText())) {
                    authored.add(entry.getKey());
                }
            });
        }

        NON_PREVIEW_DEMONSTRATION_EDITORS.forEach((verb, reason) -> {
            assertTrue(live.contains(verb), () -> verb + " is a stale editing-surface disposition");
            assertFalse(reason.isBlank(), () -> verb + " needs a reason for not using a preview demonstration");
        });
        assertTrue(editingSurfaceMismatch(authored, live).isEmpty(),
                () -> "the live --dry-run routes and the independently authored editing demonstrations disagree: "
                        + editingSurfaceMismatch(authored, live));
    }

    @Test
    void anAuthoredEditorWithoutDryRunFailsTheDenominatorReconciliation() {
        assertEquals(List.of("missing --dry-run: probe mutate"),
                editingSurfaceMismatch(Set.of("probe mutate"), Set.of()),
                "the authored side must catch an editing command that forgot the live option used as denominator");
    }

    private static List<String> unclassifiedEditingVerbs(List<EditingVerb> verbs) {
        return verbs.stream()
                .filter(verb -> !verb.usesSharedTail())
                .filter(verb -> !CUSTOM_TAILS.containsKey(verb.name()))
                .map(EditingVerb::name)
                .sorted()
                .toList();
    }

    private static List<String> editingSurfaceMismatch(Set<String> authored, Set<String> live) {
        List<String> mismatch = new ArrayList<>();
        authored.stream().filter(verb -> !live.contains(verb)).sorted()
                .forEach(verb -> mismatch.add("missing --dry-run: " + verb));
        live.stream().filter(verb -> !authored.contains(verb)).sorted()
                .forEach(verb -> mismatch.add("not authored as editing: " + verb));
        return mismatch;
    }

    /**
     * The rich-input model EDITORS, derived rather than listed — minus the two kinds of rich-input verb that
     * edit nothing themselves. An artifact producer writes somewhere else entirely, and a DISPATCHER's input
     * is other verbs' argv: {@code batch} owns no edit and correctly carries no {@code --dry-run}, since each
     * child brings its own. ({@code apply} stays in, because its op-records ARE the edit and it previews them
     * as one transaction — see its {@code CUSTOM_TAILS} row.)
     */
    private static Set<String> schemaDemonstratedEditors() {
        Set<String> editors = new LinkedHashSet<>(SchemaCommand.INPUT_OPS.keySet());
        editors.removeAll(SchemaCommand.ARTIFACT_OPS.keySet());
        editors.remove("batch");
        return editors;
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
            return sources.entrySet().stream()
                    .anyMatch(entry -> structurallyReturnsSharedTail(entry.getKey(), entry.getValue()));
        }

        private Set<String> sharedTailSourceKeys() {
            Set<String> keys = new LinkedHashSet<>();
            sources.forEach((key, source) -> {
                if (structurallyReturnsSharedTail(key, source)) {
                    keys.add(key);
                }
            });
            return keys;
        }
    }

    private static boolean structurallyReturnsSharedTail(String fileName, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("MutationTailSingleSourceTest requires a JDK compiler");
        }
        var file = new SimpleJavaFileObject(URI.create("string:///" + Path.of(fileName).getFileName()),
                javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        JavacTask task = (JavacTask) compiler.getTask(null, null, null, List.of("-proc:none"), null, List.of(file));
        try {
            for (var unit : task.parse()) {
                if (Boolean.TRUE.equals(new CommandTerminalReturnScanner().scan(unit, null))) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new IllegalStateException("cannot parse " + fileName, e);
        }
    }

    private static final class CommandTerminalReturnScanner extends TreeScanner<Boolean, Void> {
        @Override public Boolean visitMethod(MethodTree node, Void unused) {
            if ("call".contentEquals(node.getName()) && node.getBody() != null
                    && !node.getBody().getStatements().isEmpty()
                    && node.getBody().getStatements().getLast() instanceof ReturnTree terminal
                    && terminal.getExpression() instanceof MethodInvocationTree invocation
                    && "emitMutation".equals(methodName(invocation.getMethodSelect()))
                    && invocation.getArguments().size() == 1
                    && Boolean.TRUE.equals(new ModelWriteFactoryScanner().scan(invocation.getArguments().get(0), null))) {
                return true;
            }
            return false;
        }

        @Override public Boolean reduce(Boolean left, Boolean right) {
            return Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right);
        }
    }

    private static final class ModelWriteFactoryScanner extends TreeScanner<Boolean, Void> {
        @Override public Boolean visitMethodInvocation(MethodInvocationTree node, Void unused) {
            if (node.getMethodSelect() instanceof MemberSelectTree member
                    && "of".contentEquals(member.getIdentifier())
                    && member.getExpression() instanceof IdentifierTree owner
                    && "ModelWrite".contentEquals(owner.getName())) {
                return true;
            }
            return super.visitMethodInvocation(node, unused);
        }

        @Override public Boolean reduce(Boolean left, Boolean right) {
            return Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right);
        }
    }

    private static String methodName(Tree method) {
        if (method instanceof IdentifierTree identifier) {
            return identifier.getName().toString();
        }
        if (method instanceof MemberSelectTree member) {
            return member.getIdentifier().toString();
        }
        return "";
    }
}
