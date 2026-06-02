package io.github.mbackschat.a12.rulekit.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    private OperatorCatalog(List<OperatorEntry> entries) {
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
            OperatorEntry[] parsed = MAPPER.readValue(in, OperatorEntry[].class);
            return new OperatorCatalog(List.of(parsed));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read operator catalog " + RESOURCE, e);
        }
    }

    public List<OperatorEntry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public Optional<OperatorEntry> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }
}
