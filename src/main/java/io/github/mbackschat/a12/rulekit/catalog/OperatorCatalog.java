package io.github.mbackschat.a12.rulekit.catalog;

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
 * The bundled operator catalog (proposal §9), loaded from the classpath resource
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

    /**
     * The first catalog entry that documents this kernel error code in its {@code invalidExample} —
     * the catalog-join behind F4a, letting a kernel {@code MVK_*} diagnostic inherit that entry's
     * curated, N7-locked {@code fix}/{@code meaning} (no drift). When several operators share a code
     * (e.g. all ordering operators report {@code MVK_INVALID_TYPE_FOR_COMPARISON}), the first in
     * catalog order is representative; {@code lint.SpanResolver} pins the exact operator via
     * AST↔problem span-linking (see {@link #operatorsForError}) when the rule's used operators
     * disambiguate it.
     */
    public Optional<OperatorEntry> byInvalidError(String mvkCode) {
        return operatorsForError(mvkCode).stream().findFirst();
    }

    /**
     * <em>All</em> catalog entries that document this kernel error code in their {@code invalidExample}
     * — the candidate operators that can produce it. AST↔problem span-linking (F4a) intersects this
     * with the operators a rule actually uses to pin the exact one (`where.operator`).
     */
    public List<OperatorEntry> operatorsForError(String mvkCode) {
        return entries.stream()
                .filter(e -> e.invalidExample() != null && mvkCode.equals(e.invalidExample().error()))
                .toList();
    }
}
