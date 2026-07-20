package io.github.mbackschat.a12.dm.modelkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The authoring contract for {@code modelConfig.supportedCharacters}.
 *
 * <p>Each entry is exactly one of three forms: a single BMP character, an inclusive three-unit range
 * such as {@code "A-Z"}, or one atomic combined grapheme. A combined entry contains a Unicode extended
 * grapheme cluster and is at most three UTF-16 units long. An absent or empty list selects the kernel's
 * full-BMP default (surrogate code units, and therefore supplementary-plane characters, remain illegal).
 *
 * <p>{@link #firstProblem} mirrors the pinned kernel's definition gate. Accepted atomic graphemes are
 * preserved as list entries; their component characters are never flattened into singleton entries.
 */
public final class SupportedCharactersContract {

    /** Shared wording for the typed API and dmtool's generated help/manifest surface. */
    public static final String ENTRY_DESCRIPTION =
            "a singleton BMP character, an inclusive x-y range, or one atomic combined grapheme";

    /** Shared wording for the empty/absent model slot. */
    public static final String DEFAULT_DESCRIPTION =
            "an empty or absent list selects the full BMP (excluding surrogates), not supplementary-plane characters";

    /** The complete {@code config modify --supported-character} help text, derived into the manifest. */
    public static final String CLI_OPTION_DESCRIPTION =
            "restrict allowed characters (repeatable): " + ENTRY_DESCRIPTION
                    + ". Replaces the whole set; pass no option to leave it unchanged. "
                    + DEFAULT_DESCRIPTION + ". e.g. --supported-character A-Z";

    /** Corrective guidance used by every authoring path. */
    public static final String FIX =
            "use a singleton BMP character, an inclusive x-y range, or one atomic combined grapheme "
                    + "of at most three UTF-16 units; remove empty, surrogate, reversed-range, plain "
                    + "multi-character, overlong, or ambiguously overlapping entries";

    private static final int MAX_GRAPHEME_LENGTH = 3;
    private static final Pattern EXTENDED_GRAPHEME = Pattern.compile("\\X");

    private SupportedCharactersContract() {
    }

    /** One invalid definition and its zero-based position in the supplied list. */
    public record Problem(int index, String detail) {

        public Problem {
            Objects.requireNonNull(detail, "detail");
        }

        /** A precise author-facing description of the offending list entry. */
        public String message() {
            return "supportedCharacters entry at index " + index + " is invalid: " + detail;
        }
    }

    /**
     * Return the first kernel-definition violation, or empty when every entry is legal. An empty list is
     * legal and means the full-BMP default.
     */
    public static Optional<Problem> firstProblem(List<String> entries) {
        Objects.requireNonNull(entries, "entries");
        List<CharRange> ranges = new ArrayList<>();
        List<Grapheme> graphemes = new ArrayList<>();

        for (int index = 0; index < entries.size(); index++) {
            String entry = entries.get(index);
            if (entry == null) {
                return problem(index, "entry is null");
            }
            if (entry.isEmpty()) {
                return problem(index, "entry is empty");
            }
            if (entry.chars().anyMatch(SupportedCharactersContract::isSurrogate)) {
                return problem(index,
                        "entry contains a UTF-16 surrogate; supported characters are limited to the BMP");
            }

            switch (kindOf(entry)) {
                case SINGLETON -> ranges.add(new CharRange(entry.charAt(0), entry.charAt(0)));
                case RANGE -> {
                    char start = entry.charAt(0);
                    char end = entry.charAt(2);
                    if (start > end) {
                        return problem(index, "range start '" + start + "' is after end '" + end + "'");
                    }
                    ranges.add(new CharRange(start, end));
                }
                case GRAPHEME -> {
                    if (entry.length() > MAX_GRAPHEME_LENGTH) {
                        return problem(index, "entry is " + entry.length()
                                + " UTF-16 units; maximum is " + MAX_GRAPHEME_LENGTH);
                    }
                    List<String> clusters = graphemeClusters(entry);
                    if (clusters.size() >= entry.length()) {
                        return problem(index, "plain multi-character entry does not contain a combined grapheme");
                    }
                    graphemes.add(new Grapheme(index, entry, clusters));
                }
            }
        }

        return ambiguousOverlap(ranges, graphemes);
    }

    /** Validate and return an immutable copy, throwing a precise {@link IllegalArgumentException} on failure. */
    public static List<String> requireValid(List<String> entries) {
        Optional<Problem> problem = firstProblem(entries);
        if (problem.isPresent()) {
            throw new IllegalArgumentException(problem.get().message() + "; " + FIX);
        }
        return List.copyOf(entries);
    }

    private static Optional<Problem> ambiguousOverlap(List<CharRange> ranges, List<Grapheme> graphemes) {
        Set<String> definitions = graphemes.stream().map(Grapheme::value)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (Grapheme grapheme : graphemes) {
            if (grapheme.clusters().size() <= 1) {
                continue;
            }
            boolean prefixRepresentable = grapheme.clusters().subList(0, grapheme.clusters().size() - 1)
                    .stream().allMatch(cluster -> cluster.length() > 1
                            ? definitions.contains(cluster)
                            : ranges.stream().anyMatch(range -> range.contains(cluster.charAt(0))));
            if (!prefixRepresentable) {
                continue;
            }
            String suffix = grapheme.clusters().getLast();
            Optional<String> conflict = definitions.stream()
                    .filter(candidate -> candidate.length() != suffix.length())
                    .filter(candidate -> candidate.startsWith(suffix))
                    .filter(candidate -> !candidate.equals(grapheme.value()))
                    .findFirst();
            if (conflict.isPresent()) {
                return problem(grapheme.index(), "ambiguous overlap: suffix '" + suffix
                        + "' also starts atomic entry '" + conflict.get() + "'");
            }
        }
        return Optional.empty();
    }

    private static List<String> graphemeClusters(String entry) {
        Matcher matcher = EXTENDED_GRAPHEME.matcher(entry);
        List<String> clusters = new ArrayList<>();
        while (matcher.find()) {
            clusters.add(matcher.group());
        }
        return List.copyOf(clusters);
    }

    private static EntryKind kindOf(String entry) {
        if (entry.length() == 1) {
            return EntryKind.SINGLETON;
        }
        if (entry.length() == 3 && entry.charAt(1) == '-') {
            return EntryKind.RANGE;
        }
        return EntryKind.GRAPHEME;
    }

    private static boolean isSurrogate(int codeUnit) {
        return codeUnit >= Character.MIN_SURROGATE && codeUnit <= Character.MAX_SURROGATE;
    }

    private static Optional<Problem> problem(int index, String detail) {
        return Optional.of(new Problem(index, detail));
    }

    private enum EntryKind {
        SINGLETON,
        RANGE,
        GRAPHEME
    }

    private record CharRange(char start, char end) {
        boolean contains(char value) {
            return value >= start && value <= end;
        }
    }

    private record Grapheme(int index, String value, List<String> clusters) {
    }
}
