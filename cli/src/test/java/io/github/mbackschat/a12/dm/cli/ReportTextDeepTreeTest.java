package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Locks tree-text rendering to the size of the emitted hierarchy, including its logic lines. */
class ReportTextDeepTreeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEPTH = 128;

    @Test
    void deepSiblingCombBuildsEachPrefixOnceWithoutRescanningDescendants() {
        ObjectNode report = deepSiblingComb();
        var work = new EnumMap<ReportText.TreeLayoutStep, Integer>(ReportText.TreeLayoutStep.class);

        String rendered = new ReportText(step -> work.merge(step, 1, Integer::sum)).render(report);

        int nodes = report.path("nodes").size();
        assertEquals(nodes, work.get(ReportText.TreeLayoutStep.SIBLING_CLASSIFICATION),
                "each flat node is classified once; descendants must never be rescanned");
        assertEquals(nodes - 1, work.get(ReportText.TreeLayoutStep.PREFIX_BUILD),
                "each non-root prefix is built once and reused by its logic lines");

        String continuation = "│  ".repeat(DEPTH - 1);
        assertTrue(rendered.contains(continuation + "├─ Branch" + DEPTH),
                "distant siblings keep every ancestor continuation visible");
        assertTrue(rendered.contains(continuation + "│  └─ rule Check" + DEPTH),
                "logic lines reuse the node prefix and continue past a non-last node");
        assertTrue(rendered.contains(continuation + "└─ Sibling" + DEPTH),
                "the closing sibling uses the same ancestor prefix and a last-child branch");
    }

    private static ObjectNode deepSiblingComb() {
        ObjectNode report = MAPPER.createObjectNode();
        ArrayNode nodes = report.putArray("nodes");
        addNode(nodes, "/", null, 0, "model", null);

        String[] branches = new String[DEPTH + 1];
        branches[0] = "/";
        for (int depth = 1; depth <= DEPTH; depth++) {
            String parent = branches[depth - 1];
            String path = parent.equals("/") ? "/Branch" + depth : parent + "/Branch" + depth;
            branches[depth] = path;
            addNode(nodes, path, parent, depth, "group", "Check" + depth);
        }
        for (int depth = DEPTH; depth >= 1; depth--) {
            String parent = branches[depth - 1];
            String path = parent.equals("/") ? "/Sibling" + depth : parent + "/Sibling" + depth;
            addNode(nodes, path, parent, depth, "field", null);
        }
        report.putObject("summary");
        return report;
    }

    private static void addNode(ArrayNode nodes, String path, String parent, int depth, String kind,
            String ruleName) {
        ObjectNode node = nodes.addObject();
        node.put("path", path);
        if (parent == null) {
            node.putNull("parent");
        } else {
            node.put("parent", parent);
        }
        node.put("depth", depth);
        node.put("kind", kind);
        if (kind.equals("field")) {
            node.put("type", "STRING");
        }
        if (ruleName != null) {
            ObjectNode rule = node.putArray("rules").addObject();
            rule.put("name", path + "/" + ruleName);
            rule.put("dsl", "FieldNotFilled(Value)");
        }
    }
}
