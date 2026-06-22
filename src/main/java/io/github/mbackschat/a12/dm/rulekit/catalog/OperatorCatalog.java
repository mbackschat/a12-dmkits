package io.github.mbackschat.a12.dm.rulekit.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The bundled operator catalog (PROJECT-DESIGN §9), loaded from the classpath resource
 * {@code catalog/operators.json}. This is the language-neutral spine the AST, renderer, docs, and
 * LLM context all read from. Loading fails fast on a duplicate id so the catalog can't silently
 * shadow an entry.
 */
public final class OperatorCatalog {

    private static final String RESOURCE = "catalog/operators.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<OperatorEntry> entries;
    private final Map<String, OperatorEntry> byId;
    private final String verifiedAgainst;

    private OperatorCatalog(List<OperatorEntry> entries, String verifiedAgainst) {
        this.verifiedAgainst = verifiedAgainst;
        this.entries = List.copyOf(entries);
        Map<String, OperatorEntry> index = new LinkedHashMap<>();
        for (OperatorEntry entry : entries) {
            if (index.put(entry.id(), entry) != null) {
                throw new IllegalStateException("duplicate operator id in catalog: " + entry.id());
            }
        }
        this.byId = Map.copyOf(index);
    }

    /** Load the catalog bundled on the classpath. */
    public static OperatorCatalog load() {
        try (InputStream in = OperatorCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("operator catalog resource not found: " + RESOURCE);
            }
            JsonNode root = MAPPER.readTree(in);
            String verifiedAgainst = root.path("verifiedAgainst").asText(null);
            OperatorEntry[] parsed = MAPPER.treeToValue(root.path("operators"), OperatorEntry[].class);
            return new OperatorCatalog(List.of(parsed), verifiedAgainst);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read operator catalog " + RESOURCE, e);
        }
    }

    public List<OperatorEntry> entries() {
        return entries;
    }

    /**
     * The kernel version this catalog was verified against (N7 ran every {@code SUPPORTED} example
     * against it) — the F11 verification floor. The live kernel may be newer; {@code KernelVersion}
     * compares the two and surfaces a single advisory on skew, never an error.
     */
    public String verifiedAgainst() {
        return verifiedAgainst;
    }

    public int size() {
        return entries.size();
    }

    public Optional<OperatorEntry> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The operator whose EN <em>or</em> DE keyword equals {@code keyword} — the catalog is keyword-indexed,
     *  so a reader who has the rendered DSL token can look up what it means. */
    public Optional<OperatorEntry> byKeyword(String keyword) {
        return entries.stream()
                .filter(e -> e.keywords() != null
                        && (keyword.equals(e.keywords().en()) || keyword.equals(e.keywords().de())))
                .findFirst();
    }

    /** Every operator of a given {@link OperatorKind} (predicate / operator / function / aggregate / …) —
     *  for browsing a family rather than guessing an id. */
    public List<OperatorEntry> byKind(OperatorKind kind) {
        return entries.stream().filter(e -> e.kind() == kind).toList();
    }

    /** Every operator that returns {@code returns} (e.g. {@code "boolean"}, {@code "number"}) — "what can I
     *  put in this slot?" discovery. */
    public List<OperatorEntry> byReturns(String returns) {
        return entries.stream().filter(e -> returns.equals(e.returns())).toList();
    }

    /**
     * <em>All</em> catalog entries that document this kernel error code in their {@code invalidExample}
     * — the candidate operators that can produce it. The {@code DiagnosticEnricher} joins on this:
     * one documenter (or several that agree) yields that corrective; several that disagree yield the
     * <em>union</em> — never an arbitrary first pick (the removed {@code byInvalidError}/{@code findFirst}
     * mis-stamped every other construct: probe findings F06-2 / dec-places). AST↔problem span-linking
     * (F4a, {@code lint.SpanResolver}) further intersects this with the operators a rule actually uses
     * to pin the exact one (`where.operator`) once that lands on a read pass.
     */
    public List<OperatorEntry> operatorsForError(String mvkCode) {
        return entries.stream()
                .filter(e -> e.invalidExample() != null && mvkCode.equals(e.invalidExample().error()))
                .toList();
    }
}
