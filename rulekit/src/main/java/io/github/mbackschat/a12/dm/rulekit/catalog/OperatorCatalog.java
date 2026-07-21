package io.github.mbackschat.a12.dm.rulekit.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.core.ValueKind;

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
    private final Map<String, EmptyOperandDefault> emptyOperandDefaults;

    private OperatorCatalog(List<OperatorEntry> entries, String verifiedAgainst,
            Map<String, EmptyOperandDefault> emptyOperandDefaults) {
        this.verifiedAgainst = verifiedAgainst;
        this.entries = List.copyOf(entries);
        this.emptyOperandDefaults = Map.copyOf(emptyOperandDefaults);
        Map<String, OperatorEntry> index = new LinkedHashMap<>();
        for (OperatorEntry entry : entries) {
            if (index.put(entry.id(), entry) != null) {
                throw new IllegalStateException("duplicate operator id in catalog: " + entry.id());
            }
        }
        this.byId = Map.copyOf(index);
    }

    /**
     * Return the immutable catalog bundled on the classpath. The resource is parsed once per process: renderers,
     * diagnostics, and other short-lived consumers all share the same value.
     */
    public static OperatorCatalog load() {
        return LoadedCatalog.INSTANCE;
    }

    private static OperatorCatalog read() {
        try (InputStream in = OperatorCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("operator catalog resource not found: " + RESOURCE);
            }
            JsonNode root = MAPPER.readTree(in);
            String verifiedAgainst = root.path("verifiedAgainst").asText(null);
            OperatorEntry[] parsed = MAPPER.treeToValue(root.path("operators"), OperatorEntry[].class);
            Map<String, EmptyOperandDefault> defaults = MAPPER.convertValue(root.path("emptyOperandDefaults"),
                    new TypeReference<LinkedHashMap<String, EmptyOperandDefault>>() { });
            return new OperatorCatalog(List.of(parsed), verifiedAgainst,
                    defaults == null ? Map.of() : defaults);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read operator catalog " + RESOURCE, e);
        }
    }

    private static final class LoadedCatalog {
        private static final OperatorCatalog INSTANCE = read();

        private LoadedCatalog() {
        }
    }

    /**
     * The per-kind <b>empty-operand default</b> — what an empty (present-but-valueless) operand of a
     * value kind does in a comparison/value position when no operator-level facet overrides it
     * (KERNEL-SEMANTICS §2's type-asymmetry table, stated once; CATALOG-SPEC §4). Keys are the catalog
     * kind tokens ({@code number}, {@code string}, {@code enum}, {@code boolean}, {@code confirm},
     * {@code date}, {@code time}, {@code date-time}).
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record EmptyOperandDefault(EmptyBehavior behavior, String basis, String note,
            List<FacetProbe> probes) {
        public EmptyOperandDefault {
            probes = probes == null ? List.of() : List.copyOf(probes);
        }
    }

    /** The header {@code emptyOperandDefaults} table (kind token → default), in declaration order. */
    public Map<String, EmptyOperandDefault> emptyOperandDefaults() {
        return emptyOperandDefaults;
    }

    /**
     * The default {@link EmptyBehavior} for an operand of {@code kind} — the {@code emptyOperandDefaults}
     * lookup by typed {@link ValueKind} (a {@code DATE_FRAGMENT} rides the date row, IF57: a fragment is
     * completed to a plain date before evaluation). Empty for the kinds outside the table
     * ({@code DATE_RANGE} has no comparison/value position; {@code CUSTOM} validity is a delegated SPI).
     */
    public Optional<EmptyBehavior> defaultEmptyBehavior(ValueKind kind) {
        return Optional.ofNullable(kindToken(kind))
                .map(emptyOperandDefaults::get)
                .map(EmptyOperandDefault::behavior);
    }

    /**
     * The <b>consumer query</b> of the {@code emptyOperand} facet (CATALOG-SPEC §8): what does
     * {@code operatorId}'s operand at {@code operandIndex} do when it is empty and holds a value of
     * {@code kind}? Resolution order — the operator's own facet claim covering that operand (a
     * deviation), else the kind's header default. Empty when neither speaks (an unpopulated operator
     * over a kind outside the defaults table) — a consumer must then stay conservative, never guess.
     */
    public Optional<EmptyBehavior> emptyOperandBehavior(String operatorId, int operandIndex, ValueKind kind) {
        Optional<EmptyBehavior> claimed = byId(operatorId)
                .stream()
                .flatMap(e -> e.emptyOperandClaims().stream())
                .filter(c -> c.appliesTo(operandIndex))
                .findFirst()
                .flatMap(c -> Optional.ofNullable(kindToken(kind)).flatMap(c::behaviorFor));
        return claimed.or(() -> defaultEmptyBehavior(kind));
    }

    /** The catalog kind token for a {@link ValueKind} ({@code null} for the kinds outside the facet tables) —
     *  the shared vocabulary the facet claims, the correctives' {@code whenOperands}, and their consumers speak. */
    public static String kindToken(ValueKind kind) {
        return switch (kind) {
            case NUMBER -> "number";
            case STRING -> "string";
            case ENUMERATION -> "enum";
            case BOOLEAN -> "boolean";
            case CONFIRM -> "confirm";
            case DATE, DATE_FRAGMENT -> "date";
            case TIME -> "time";
            case DATE_TIME -> "date-time";
            case DATE_RANGE, CUSTOM, UNKNOWN -> null;
        };
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

    /**
     * The operand-matched corrective fix (CATALOG-SPEC §6, the D1 mechanism): {@code operatorId}'s corrective
     * for {@code code} whose {@code whenOperands} multiset matches {@code operandTokens} (the shared vocabulary
     * of {@link #kindToken}: {@code number}/{@code string}/{@code date}/…, {@code string-literal},
     * {@code number-literal}). Empty when the operator is unknown or no corrective matches — the caller keeps
     * the entry-level union fix as the fallback. This is the catalog half of the D1 mechanism, called by BOTH
     * the typed-AST {@link io.github.mbackschat.a12.dm.rulekit.lint.SpanResolver} and the CLI text-path
     * enrichment (G30, which classifies operands via the kernel-free interpreter parser + {@code ModelIndex}).
     */
    public Optional<String> correctiveFix(String operatorId, String code, List<String> operandTokens) {
        return byId(operatorId).stream()
                .flatMap(e -> e.correctives().stream())
                .filter(c -> code.equals(c.code()) && matchesUnordered(c.whenOperands(), operandTokens))
                .map(Corrective::fix)
                .findFirst();
    }

    private static boolean matchesUnordered(List<String> expected, List<String> actual) {
        return expected.size() == actual.size()
                && expected.stream().sorted().toList().equals(actual.stream().sorted().toList());
    }
}
