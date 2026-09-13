package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * When a model's computations depend on each other in a CIRCLE, and which reads build that circle.
 *
 * <p>A12 sorts the calculated fields topologically and refuses a model whose dependency graph has a cycle
 * ({@code MVK_ERROR_CALC_CYCLE}). The law is entirely about DECLARATIONS — which field each computation
 * writes, and which fields its members read — so it is decidable without a document, which is what makes it
 * reachable for a Kernel-free checker.
 *
 * <p><b>The one-computation case is a different code, and that is the row an implementation must not miss.</b>
 * A computation reading its own target is a cycle of length one in any graph an implementation would build,
 * and A12 answers it with {@code MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD} instead. Reporting the cycle class
 * there would name a verdict A12 never reached.
 *
 * <p>The remaining rows each move exactly one thing: which MEMBER carries the read, whether the field read is
 * computed at all, and whether the two ends sit in the same group.
 *
 * <p>Own domain (a courier depot).
 */
class ComputationDependencyCycleLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CYCLE = "MVK_ERROR_CALC_CYCLE";

    private static final String SELF_READ = "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD";

    /**
     * A depot whose numeric fields all carry the same declared scale.
     *
     * <p>Deliberate: a derived-scale mismatch is reported AHEAD of the self-read class ([KF181]), so a row
     * that also moved the scale would be measuring the wrong gate.
     */
    private static final String DEPOT = DocumentModelDraft.named("courier-depot").locale("en_US")
            .rootGroup("Depot", d -> d
                    .field("Weight", FieldKind.NUMBER)
                    .field("Volume", FieldKind.NUMBER)
                    .field("Base", FieldKind.NUMBER)
                    // Never computed by any row: the control for "a read is an edge only to a COMPUTED field".
                    .field("Declared", FieldKind.NUMBER)
                    .group("Crates", c -> c.repeatable(10)
                            .field("Cost", FieldKind.NUMBER)
                            .field("Units", FieldKind.NUMBER)))
            .build().toJson();

    @Test
    void whichReadsBuildTheDependencyCircleA12Refuses() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an acyclic chain — the control", verdict(
                calc("/Depot", "W", "../Weight", "[Base] + 1"),
                calc("/Depot", "V", "../Volume", "[Weight] * 2")));
        actual.put("two computations reading each other", verdict(
                calc("/Depot", "W", "../Weight", "[Volume] + 1"),
                calc("/Depot", "V", "../Volume", "[Weight] * 2")));
        actual.put("a three-step circle", verdict(
                calc("/Depot", "W", "../Weight", "[Volume] + 1"),
                calc("/Depot", "V", "../Volume", "[Base] * 2"),
                calc("/Depot", "B", "../Base", "[Weight] - 1")));
        actual.put("one computation reading its own target", verdict(
                calc("/Depot", "W", "../Weight", "[Weight] + 1")));
        actual.put("the back edge carried by a PRECONDITION, not an operation", verdict(
                guarded("/Depot", "W", "../Weight", "FieldFilled(Volume)", "[Base] + 1"),
                calc("/Depot", "V", "../Volume", "[Weight] * 2")));
        actual.put("the back edge through a field nothing computes — the control", verdict(
                calc("/Depot", "W", "../Weight", "[Declared] + 1"),
                calc("/Depot", "V", "../Volume", "[Weight] * 2")));
        actual.put("a circle whose two ends sit in different groups", verdict(
                calc("/Depot", "W", "../Weight", "Sum(Crates*/Cost)"),
                calc("/Depot/Crates", "C", "../Cost", "[/Depot/Weight] + 1")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an acyclic chain — the control", KernelLaws.ADMITTED);
        expected.put("two computations reading each other", CYCLE);
        expected.put("a three-step circle", CYCLE);
        // NOT the cycle class: a one-node circle is the self-read law, reported ahead of this one.
        expected.put("one computation reading its own target", SELF_READ);
        // A PRECONDITION read is an edge like any other, so a checker reading only `operation` would admit
        // a model A12 refuses. Measured rather than assumed from the operation rows.
        expected.put("the back edge carried by a PRECONDITION, not an operation", CYCLE);
        // No computation writes `Declared`, so reading it is an edge to nothing and closes no circle.
        expected.put("the back edge through a field nothing computes — the control", KernelLaws.ADMITTED);
        // The graph is over RESOLVED fields, not over declaration sites: an aggregate over a repeatable
        // group's field and an absolute read back out of it close the circle exactly as two sibling reads do.
        expected.put("a circle whose two ends sit in different groups", CYCLE);
        assertEquals(expected, actual, "which reads build the dependency circle A12 refuses");
    }

    private record Calc(String group, String name, String target, String precondition, String operation) { }

    private static Calc calc(String group, String name, String target, String operation) {
        return new Calc(group, name, target, null, operation);
    }

    private static Calc guarded(String group, String name, String target, String precondition, String operation) {
        return new Calc(group, name, target, precondition, operation);
    }

    /** The depot carrying [calcs], each declared in the group its own row names. */
    private static String verdict(Calc... calcs) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(DEPOT);
            int id = 0;
            for (Calc calc : calcs) {
                ObjectNode node = (ObjectNode) elementsOf(root, calc.group()).addObject();
                node.put("type", "Computation").put("id", "c" + id++).put("name", calc.name());
                ObjectNode body = node.putObject("Computation");
                body.put("computedFieldRelPath", calc.target());
                ObjectNode alternative = body.putArray("computationAlternatives").addObject();
                if (calc.precondition() != null) {
                    alternative.put("precondition", calc.precondition());
                }
                alternative.put("operation", calc.operation());
                body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "x");
            }
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    /** The `elements` array of [groupPath], refusing rather than writing the computation somewhere else. */
    private static ArrayNode elementsOf(ObjectNode root, String groupPath) {
        ArrayNode elements = (ArrayNode) root.path("content").path("modelRoot")
                .path("rootGroups").get(0).path("Group").path("elements");
        List<String> segments = List.of(groupPath.split("/"));
        for (String segment : segments.subList(2, segments.size())) {
            ArrayNode next = null;
            for (var element : elements) {
                if (segment.equals(element.path("name").asText())) {
                    next = (ArrayNode) element.path("Group").path("elements");
                }
            }
            if (next == null) {
                throw new IllegalStateException("the fixture has no group " + groupPath);
            }
            elements = next;
        }
        return elements;
    }
}
