package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * The published envelope's top-level key set is pinned, so changing it is <b>deliberate and triggers a consumer
 * sweep</b>.
 *
 * <p><b>What this is for, and what it is not.</b> It is not a schema test — {@code SchemaVerbTest} already checks
 * that every verb's real output keys are declared. It is a <b>tripwire</b>: the envelope frame is the one thing
 * whose change invalidates consumers the build cannot see. Nesting the verb payloads under {@code data} broke four
 * {@code jq} filters in {@code examples/} and a prompt in the eval harness that tells judging agents what
 * {@code batch} returns; all of them stayed green, because a captured error re-runs identically and nothing
 * executes a prompt. The Java call sites were swept mechanically and the rest were swept from memory.
 *
 * <p>So the frame change now fails the build with the sweep instruction attached. That is the difference between a
 * rule someone has to remember at the right moment and one the build raises at exactly that moment — and the
 * moment is identifiable, because it is precisely when this assertion breaks.
 *
 * <p>Updating it is one line, and that is the point: the cost is the sweep, not the edit.
 */
class ResultEnvelopeFrameTripwireTest {

    private static final ObjectMapper M = new ObjectMapper();

    /**
     * The published frame, as of 2026-08-01. Change this <b>together with</b> the consumer sweep, never before it.
     */
    private static final Set<String> FRAME = new LinkedHashSet<>(Set.of(
            "target", "op", "outcome", "ok", "summary", "data", "diagnostics",
            "valid", "verification", "changed", "written", "output",
            "engine", "engineVersion", "toolVersion"));

    @Test
    void theEnvelopeFrameIsUnchangedOrTheConsumersWereSwept() throws Exception {
        Set<String> published = new LinkedHashSet<>();
        try (InputStream in = getClass().getResourceAsStream("/schema/result.schema.json")) {
            JsonNode props = M.readTree(in).path("properties");
            props.fieldNames().forEachRemaining(published::add);
        }

        assertEquals(FRAME, published,
                """
                The published result-envelope frame changed. That is allowed — but it invalidates consumers no \
                compiler and no existing guard can see, so do the sweep before updating FRAME above:

                  ./scripts/contract-consumers.sh --detail

                lists every committed file that reads dmtool output. examples/ and the verb descriptions are \
                guarded (verify-demos.sh + DemoOutputIsRealTest + VerbDemonstrationTest); eval/, probe/, skill*/ \
                and docs/ prose are NOT — read those by hand. Last time this changed, four demo jq filters went \
                stale (two capturing a jq error as expected output) and the eval harness spent a phase telling \
                its judging agents the wrong output shape.""");
    }
}
