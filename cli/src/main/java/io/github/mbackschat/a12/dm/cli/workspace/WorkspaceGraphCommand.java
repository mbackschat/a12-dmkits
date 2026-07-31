package io.github.mbackschat.a12.dm.cli.workspace;

import io.github.mbackschat.a12.dm.cli.BaseCommand;
import io.github.mbackschat.a12.dm.cli.ConsistencyEvidence;
import io.github.mbackschat.a12.dm.cli.ResultEnvelope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.Workspace;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code dmtool workspace graph <dir>} — the model-<b>inheritance</b> graph of a folder: subtype→supertype
 * edges from the super/subtype convention ({@code superTypes}/{@code subTypes} header annotations), the
 * hierarchy {@code workspace list}'s flat per-model view can't show at a glance. A read op: the payload rides
 * {@code data} ({@code nodes} + {@code edges}, each edge {@code resolved} iff both ends are scanned models).
 * Deliberately inheritance-only — the <em>composition</em> relations (includes / type-def imports) are a
 * different relationship kind (depends-on, not is-a) and are already surfaced, resolved, per-model by
 * {@code workspace list} / {@code model info}. Kernel-free. {@code --format tree|dot} render the same edges
 * as a human hierarchy / a Graphviz digraph.
 */
@Command(name = "graph", mixinStandardHelpOptions = true,
        description = "Graph a directory's model-inheritance hierarchy (subtype→supertype, from the "
                + "super/subtype convention) into `data`. --format tree|dot for a human/Graphviz view.")
final class WorkspaceGraphCommand extends BaseCommand {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Parameters(index = "0", arity = "0..1", paramLabel = "<dir>",
            description = "the workspace directory to scan for DM-JSON document models (defaults to -w/--workspace, then $DMTOOL_WORKSPACE)")
    String dir;

    @Option(names = {"--recursive", "-r"},
            description = "descend into sub-directories (default: the immediate directory only, like `ls`)")
    boolean recursive;

    @Option(names = "--format", paramLabel = "json|tree|dot",
            description = "output format: json (default, the result envelope), tree (indented hierarchy), or "
                    + "dot (a Graphviz digraph)")
    String format = "json";

    @Override
    public Integer call() {
        dir = workspaceDir(dir).orElse(null);
        if (dir == null) {
            return fail("pass a workspace directory positionally (dmtool workspace <op> <dir>), or set -w/--workspace (or $DMTOOL_WORKSPACE)");
        }
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            return fail("not a directory: " + dir);
        }
        if (!List.of("json", "tree", "dot").contains(format.toLowerCase(java.util.Locale.ROOT))) {
            return fail("unknown --format '" + format + "' (json|tree|dot)");
        }
        List<Workspace.Model> models;
        try {
            models = Workspace.scan(root, recursive);
        } catch (RuntimeException e) {
            return fail("scan failed: " + e.getMessage());
        }
        List<Workspace.Edge> edges = Workspace.inheritanceEdges(models);

        switch (format.toLowerCase(java.util.Locale.ROOT)) {
            case "tree" -> {
                out().print(tree(models, edges));
                return 0;
            }
            case "dot" -> {
                out().print(dot(models, edges));
                return 0;
            }
            default -> {
                ObjectNode data = MAPPER.createObjectNode();
                data.put("root", dir);
                data.put("recursive", recursive);
                ArrayNode nodes = data.putArray("nodes");
                for (Workspace.Model m : models) {
                    nodes.addObject().put("id", m.id()).put("path", m.path())
                            .put("modelType", m.modelType()).put("abstract", m.isAbstract());
                }
                ArrayNode edgeArr = data.putArray("edges");
                for (Workspace.Edge e : edges) {
                    edgeArr.addObject().put("subtype", e.subtype()).put("supertype", e.supertype())
                            .put("resolved", e.resolved());
                }
                // a directory scan + header parse; no model is loaded through the consistency gate
                ResultEnvelope env = ResultEnvelope.queryResult("workspace", "graph",
                        models.size() + " model(s), " + edges.size() + " inheritance edge(s) under " + dir,
                        data, ConsistencyEvidence.none());
                out().print(env.toJsonString());
                return env.exitCode();
            }
        }
    }

    /** subtype→supertype edges as an indented forest: each hierarchy root, then its subtypes (cycle-guarded). */
    private String tree(List<Workspace.Model> models, List<Workspace.Edge> edges) {
        if (edges.isEmpty()) {
            return "(no inheritance relationships among " + models.size() + " model(s))\n";
        }
        Set<String> abstractIds = new LinkedHashSet<>();
        Set<String> nodeIds = new LinkedHashSet<>();
        for (Workspace.Model m : models) {
            nodeIds.add(m.id());
            if (m.isAbstract()) {
                abstractIds.add(m.id());
            }
        }
        Map<String, Set<String>> subtypesOf = new LinkedHashMap<>();
        Set<String> allSubtypes = new LinkedHashSet<>();
        for (Workspace.Edge e : edges) {
            subtypesOf.computeIfAbsent(e.supertype(), k -> new TreeSet<>()).add(e.subtype());
            allSubtypes.add(e.subtype());
        }
        Set<String> roots = new TreeSet<>(subtypesOf.keySet()); // supertypes …
        roots.removeAll(allSubtypes);                            // … that are not themselves a subtype
        StringBuilder sb = new StringBuilder();
        for (String root : roots) {
            appendTree(sb, root, subtypesOf, nodeIds, abstractIds, 0, new LinkedHashSet<>());
        }
        return sb.toString();
    }

    private void appendTree(StringBuilder sb, String id, Map<String, Set<String>> subtypesOf,
            Set<String> nodeIds, Set<String> abstractIds, int depth, Set<String> path) {
        sb.append("  ".repeat(depth)).append(depth == 0 ? "" : "- ").append(id);
        if (abstractIds.contains(id)) {
            sb.append(" (abstract)");
        }
        if (!nodeIds.contains(id)) {
            sb.append(" (unresolved)"); // a supertype declared but absent from the scan
        }
        if (!path.add(id)) {
            sb.append(" (cycle)\n"); // already on this branch — stop, don't recurse
            return;
        }
        sb.append('\n');
        for (String sub : subtypesOf.getOrDefault(id, Set.of())) {
            appendTree(sb, sub, subtypesOf, nodeIds, abstractIds, depth + 1, new LinkedHashSet<>(path));
        }
    }

    /** A Graphviz digraph (is-a edges point up): abstract nodes dashed, unresolved edges dashed+grey. */
    private String dot(List<Workspace.Model> models, List<Workspace.Edge> edges) {
        StringBuilder sb = new StringBuilder("digraph workspace {\n  rankdir=BT;\n  node [shape=box];\n");
        for (Workspace.Model m : models) {
            if (m.isAbstract()) {
                sb.append("  \"").append(m.id()).append("\" [style=\"rounded,dashed\"];\n");
            }
        }
        for (Workspace.Edge e : edges) {
            sb.append("  \"").append(e.subtype()).append("\" -> \"").append(e.supertype()).append('"');
            if (!e.resolved()) {
                sb.append(" [style=dashed, color=grey]");
            }
            sb.append(";\n");
        }
        return sb.append("}\n").toString();
    }
}
