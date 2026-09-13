package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * No hand-written fixture may invent a repetition member A12 does not have.
 *
 * <p><b>Why this exists, measured 2026-09-13.</b> The portable checker's wildcard family read
 * {@code "repeatable": true} — a member no A12 model carries; the real one is {@code repeatability}, an
 * integer whose default is 1. It passed every test it had, because the fixtures beside it were hand-written
 * from the same invented member, so the implementation and its evidence agreed with each other and not with
 * A12. On a real model the family would have reported {@code MVK_INVALID_WILDCARD} for every CORRECTLY
 * starred group — the over-rejection direction this project forbids. It was caught only by wiring the family
 * into a fixture someone else had written.
 *
 * <p>The checker's law fixtures are drafted through {@link DocumentModelDraft}, which emits the real
 * DM-JSON; its unit fixtures are hand-written JSON. That asymmetry is what let the two drift, and it is why
 * a guard rather than a note: the spelling is not something a session should have to remember.
 *
 * <p><b>The denominator is DERIVED, not authored.</b> The accepted spelling comes from asking the emitter —
 * a drafted repeatable group is serialized and its repetition member read off the result — so a rename in
 * the kits moves this guard with it. The scan then requires every repetition-shaped member name in the
 * checker's sources to be exactly that one, which catches {@code repeatable}, {@code maxRepetitions} and any
 * other plausible twin without anyone listing them.
 */
class FixtureRepetitionMemberGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /** Any JSON member whose name mentions repetition, however spelled. */
    private static final Pattern REPETITION_MEMBER =
            Pattern.compile("\"([A-Za-z]*[Rr]epeat[A-Za-z]*|[A-Za-z]*[Rr]epetition[A-Za-z]*)\"\\s*:");

    /** The sources whose fixtures are hand-written DM-JSON rather than drafted. */
    private static final Set<String> SCANNED = Set.of("consistency/src/commonTest", "consistency/src/commonMain");

    @Test
    void everyRepetitionMemberIsSpelledTheWayTheEmitterSpellsIt() {
        String emitted = emittedRepetitionMember();
        Set<String> used = new TreeSet<>();
        SCANNED.forEach(subtree -> used.addAll(repetitionMembersUnder(subtree)));

        assertEquals(Set.of(emitted), used,
                "a repetition member here is spelled differently from what the kits EMIT (" + emitted
                        + "). A12 has no boolean `repeatable` and no `maxRepetitions`: a fixture using one "
                        + "declares a group the checker then reads as non-repeatable, so a family judging "
                        + "repetition agrees with the fixture and refuses every correctly starred group in "
                        + "a real model. Spell it as the emitter does.");
    }

    /**
     * …and the scan reaches something, so an empty result is a broken sweep rather than a clean estate.
     *
     * <p>The floor is deliberately far below the live count: it answers "did the scan happen", not "how many
     * fixtures declare repetition".
     */
    @Test
    void theScanReachesTheCheckersFixtures() {
        long files = SCANNED.stream()
                .flatMap(subtree -> CHECKOUT.tracked(subtree).stream())
                .filter(path -> path.toString().endsWith(".kt"))
                .count();
        assertTrue(files >= 40, "the scan found only " + files + " Kotlin sources under " + SCANNED);
    }

    /** The member name the kits actually write for a repeatable group, read off a drafted model. */
    private static String emittedRepetitionMember() {
        String json = DocumentModelDraft.named("probe").locale("en_US")
                .rootGroup("Root", r -> r.group("Rows", g -> g.repeatable(7).field("Note", FieldKind.STRING)))
                .build().toJson();
        try {
            JsonNode rows = new ObjectMapper().readTree(json)
                    .path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements").get(0).path("Group");
            Set<String> carrying = new TreeSet<>();
            rows.fieldNames().forEachRemaining(name -> {
                if (rows.path(name).isInt() && rows.path(name).asInt() == 7) {
                    carrying.add(name);
                }
            });
            assertEquals(1, carrying.size(),
                    "exactly one drafted member may carry the repetition count, got " + carrying);
            return carrying.iterator().next();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the drafted probe must parse", unreadable);
        }
    }

    private static Set<String> repetitionMembersUnder(String subtree) {
        Set<String> found = new TreeSet<>();
        CHECKOUT.tracked(subtree).stream().filter(path -> path.toString().endsWith(".kt")).forEach(path -> {
            Matcher matcher = REPETITION_MEMBER.matcher(read(path));
            while (matcher.find()) {
                found.add(matcher.group(1));
            }
        });
        return found;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }
}
