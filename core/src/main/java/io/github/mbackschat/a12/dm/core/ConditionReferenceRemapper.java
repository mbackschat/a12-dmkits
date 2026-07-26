package io.github.mbackschat.a12.dm.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collects and rewrites entity-reference spans in one condition/computation fragment. This is deliberately a
 * small lexer for the reference-bearing surface, not a slash search: quoted string literals and comments are
 * opaque, while reference atoms are recognized in bracket/function arguments and after grammar separators in
 * the executable's configured source language, such as {@code In}, {@code For}/{@code Fuer},
 * {@code Having}/{@code Mit}, and {@code @From}/{@code @Von}.
 */
final class ConditionReferenceRemapper {

    private static final Set<String> ENGLISH_REFERENCE_SEPARATORS = Set.of(
            "And", "and", "AND", "Or", "or", "OR",
            "In", "in", "IN", "For", "Having",
            "PatternMatched", "PatternViolated",
            "DiffersWithToleranceRange1", "DiffersWithToleranceRange2",
            "DiffersWithToleranceRange5", "DiffersWithToleranceRange10");
    private static final Set<String> GERMAN_REFERENCE_SEPARATORS = Set.of(
            "Und", "und", "UND", "Oder", "oder", "ODER",
            "In", "in", "IN", "Fuer", "Mit",
            "WieMuster", "NichtWieMuster",
            "UngleichMitToleranz1", "UngleichMitToleranz2",
            "UngleichMitToleranz5", "UngleichMitToleranz10");
    private static final Set<String> ENGLISH_NON_REFERENCE_ATOMS = Set.of(
            "True", "False", "Today", "Now", "BaseYear", "FirstDay", "LastDay", "RuleGroup");
    private static final Set<String> GERMAN_NON_REFERENCE_ATOMS = Set.of(
            "Wahr", "Falsch", "Heute", "Jetzt", "Referenzjahr", "ErsterTag", "LetzterTag", "RegelKontext");
    private static final LanguageProfile ENGLISH = new LanguageProfile(
            ENGLISH_REFERENCE_SEPARATORS, ENGLISH_NON_REFERENCE_ATOMS, "CustomCondition", "From");
    private static final LanguageProfile GERMAN = new LanguageProfile(
            GERMAN_REFERENCE_SEPARATORS, GERMAN_NON_REFERENCE_ATOMS, "ApplikationsBedingung", "Von");

    private ConditionReferenceRemapper() {
    }

    record Result(String text, boolean hadResidual) {
    }

    record ReferenceContext(
            Map<String, String> prefixMap,
            String declaringGroup,
            Set<String> entityPaths,
            Map<String, List<String>> fieldsByShortName,
            boolean shortNamesAllowed,
            String conditionLanguageCode) {
    }

    static Result remapAbsolutePaths(String source, Map<String, String> prefixMap) {
        return remapAbsolutePaths(source, prefixMap, "en_US");
    }

    static Result remapAbsolutePaths(String source, Map<String, String> prefixMap, String conditionLanguageCode) {
        return new Scanner(source,
                new ReferenceContext(prefixMap, "", Set.of(), Map.of(), false, conditionLanguageCode)).remap();
    }

    static Result remapReferences(String source, ReferenceContext context) {
        return new Scanner(source, context).remap();
    }

    static LanguageProfile languageProfile(String conditionLanguageCode) {
        return "de_DE".equals(conditionLanguageCode) ? GERMAN : ENGLISH;
    }

    private static final class Scanner {
        private final String source;
        private final ReferenceContext context;
        private final LanguageProfile language;
        private final StringBuilder output;
        private int position;
        private boolean expectsAtom = true;
        private boolean skipNextIdentifier;
        private boolean changed;
        private boolean residual;

        private Scanner(String source, ReferenceContext context) {
            this.source = source;
            this.context = context;
            this.language = languageProfile(context.conditionLanguageCode());
            this.output = new StringBuilder(source.length());
        }

        private Result remap() {
            while (position < source.length()) {
                char c = source.charAt(position);
                if (c == '"') {
                    copyDoubleQuoted();
                    expectsAtom = false;
                } else if (c == ';' && peek(1) == ';') {
                    copyComment();
                } else if (Character.isWhitespace(c)) {
                    output.append(c);
                    position++;
                } else if (c == '[' || c == '(' || c == '{' || c == ',') {
                    output.append(c);
                    position++;
                    expectsAtom = true;
                } else if (c == ']' || c == ')' || c == '}') {
                    output.append(c);
                    position++;
                    expectsAtom = false;
                } else if (c == '@') {
                    expectsAtom = copyAtKeyword();
                } else if (c == '-' && peek(1) == '>') {
                    output.append("->");
                    position += 2;
                    skipNextIdentifier = true;
                    expectsAtom = true;
                } else if (c == '$' && expectsAtom) {
                    output.append(c);
                    position++;
                    PathToken path = readPath(position);
                    if (path != null) {
                        appendPath(path);
                        expectsAtom = false;
                    }
                } else if (c == '/' && expectsAtom) {
                    PathToken path = readPath(position);
                    if (path != null) {
                        appendPath(path);
                        expectsAtom = false;
                    } else {
                        copyOperator();
                    }
                } else if ((c == '.' && peek(1) == '.') || c == '\'') {
                    PathToken path = expectsAtom ? readPath(position) : null;
                    if (path != null) {
                        appendPath(path);
                        expectsAtom = false;
                    } else {
                        output.append(c);
                        position++;
                    }
                } else if (isIdentifierStart(c)) {
                    copyIdentifierOrPath();
                } else if (Character.isDigit(c)) {
                    copyNumber();
                    expectsAtom = false;
                } else {
                    copyOperator();
                }
            }
            return new Result(changed ? output.toString() : source, residual);
        }

        private void copyIdentifierOrPath() {
            int wordEnd = identifierEnd(position);
            String word = source.substring(position, wordEnd);
            int next = skipWhitespace(wordEnd);
            if (skipNextIdentifier) {
                output.append(source, position, wordEnd);
                position = wordEnd;
                skipNextIdentifier = false;
                expectsAtom = false;
                return;
            }
            if (language.customCondition().equals(word)) {
                output.append(word);
                position = wordEnd;
                skipNextIdentifier = true;
                expectsAtom = true;
                return;
            }
            if (language.referenceSeparators().contains(word)) {
                output.append(word);
                position = wordEnd;
                expectsAtom = true;
                return;
            }
            if (language.nonReferenceAtoms().contains(word)) {
                output.append(word);
                position = wordEnd;
                expectsAtom = false;
                return;
            }
            if (next < source.length() && source.charAt(next) == '(') {
                output.append(source, position, wordEnd);
                position = wordEnd;
                expectsAtom = true;
                return;
            }
            PathToken path = expectsAtom ? readPath(position) : null;
            if (path != null) {
                appendPath(path);
                expectsAtom = false;
            } else {
                output.append(source, position, wordEnd);
                position = wordEnd;
                expectsAtom = false;
            }
        }

        private void appendPath(PathToken path) {
            if (!path.absolute() && !path.bareShortName()) {
                output.append(source, path.start(), path.end());
                position = path.end();
                return;
            }
            if (!path.absolute()) {
                String local = context.declaringGroup() + "/" + path.firstSegment();
                List<String> matches = context.fieldsByShortName().getOrDefault(path.firstSegment(), List.of());
                if (context.entityPaths().contains(local) || !context.shortNamesAllowed() || matches.size() != 1) {
                    output.append(source, path.start(), path.end());
                } else {
                    String mappedTarget = remapAbsolute(matches.get(0), path);
                    if (mappedTarget == null) {
                        output.append(source, path.start(), path.end());
                    } else {
                        output.append(mappedTarget);
                        changed = true;
                    }
                }
                position = path.end();
                return;
            }
            String mapped = context.prefixMap().get("/" + path.firstSegment());
            if (mapped == null) {
                output.append(source, path.start(), path.end());
                residual = true;
            } else {
                output.append(preserveQuotedMovedSegment(mapped, path));
                output.append(source, path.firstSegmentEnd(), path.end());
                changed = true;
            }
            position = path.end();
        }

        private String remapAbsolute(String target, PathToken sourceToken) {
            int secondSlash = target.indexOf('/', 1);
            String root = secondSlash < 0 ? target : target.substring(0, secondSlash);
            String mapped = context.prefixMap().get(root);
            if (mapped == null) {
                return null;
            }
            String suffix = secondSlash < 0 ? "" : target.substring(secondSlash);
            String moved = mapped + suffix;
            return sourceToken.quotedFirst()
                    ? quoteLastSegment(moved, source.substring(sourceToken.firstSegmentStart(),
                            sourceToken.firstSegmentEnd()))
                    : moved;
        }

        private String preserveQuotedMovedSegment(String mapped, PathToken sourceToken) {
            if (!sourceToken.quotedFirst() || !mapped.endsWith("/" + sourceToken.firstSegment())) {
                return mapped;
            }
            return quoteLastSegment(mapped,
                    source.substring(sourceToken.firstSegmentStart(), sourceToken.firstSegmentEnd()));
        }

        private String quoteLastSegment(String path, String authoredSegment) {
            int slash = path.lastIndexOf('/');
            return path.substring(0, slash + 1) + authoredSegment;
        }

        private PathToken readPath(int start) {
            int cursor = start;
            boolean absolute = false;
            if (source.charAt(cursor) == '/') {
                absolute = true;
                cursor++;
                cursor = skipWhitespace(cursor);
            } else if (source.charAt(cursor) == '.' && charAt(cursor + 1) == '.') {
                do {
                    cursor += 2;
                    cursor = skipWhitespace(cursor);
                    if (charAt(cursor) == '/' && charAt(skipWhitespace(cursor + 1)) == '.') {
                        cursor++;
                        cursor = skipWhitespace(cursor);
                    } else {
                        break;
                    }
                } while (charAt(cursor) == '.' && charAt(cursor + 1) == '.');
                if (charAt(cursor) == '/') {
                    cursor++;
                    cursor = skipWhitespace(cursor);
                } else {
                    int turningPointEnd = identifierOrQuotedEnd(cursor);
                    int end = turningPointEnd < 0 ? cursor : turningPointEnd;
                    return new PathToken(start, end, false, "", cursor, end, false, false);
                }
            }

            Segment first = readSegment(cursor);
            if (first == null) {
                return null;
            }
            int firstStart = cursor;
            cursor = first.end();
            int afterWildcard = consumeWildcard(cursor);
            boolean starred = afterWildcard != cursor;
            cursor = afterWildcard;
            boolean multipleSegments = false;
            while (true) {
                int slash = skipWhitespace(cursor);
                if (charAt(slash) != '/') {
                    break;
                }
                int segmentStart = skipWhitespace(slash + 1);
                Segment segment = readSegment(segmentStart);
                if (segment == null) {
                    break;
                }
                multipleSegments = true;
                cursor = consumeWildcard(segment.end());
            }
            boolean bareShortName = !absolute && !multipleSegments && !starred
                    && !(source.charAt(start) == '.' && peekFrom(start, 1) == '.');
            return new PathToken(start, cursor, absolute, first.value(), firstStart, first.end(),
                    first.quoted(), bareShortName);
        }

        private int consumeWildcard(int cursor) {
            int star = skipWhitespace(cursor);
            return charAt(star) == '*' ? star + 1 : cursor;
        }

        private Segment readSegment(int start) {
            if (charAt(start) == '\'') {
                int end = source.indexOf('\'', start + 1);
                if (end < 0 || end == start + 1) {
                    return null;
                }
                return new Segment(source.substring(start + 1, end), end + 1, true);
            }
            if (!isIdentifierStart(charAt(start))) {
                return null;
            }
            int end = identifierEnd(start);
            return new Segment(source.substring(start, end), end, false);
        }

        private int identifierOrQuotedEnd(int start) {
            Segment segment = readSegment(start);
            return segment == null ? -1 : segment.end();
        }

        private void copyDoubleQuoted() {
            int start = position++;
            boolean escaped = false;
            while (position < source.length()) {
                char c = source.charAt(position++);
                if (c == '"' && !escaped) {
                    break;
                }
                escaped = c == '\\' && !escaped;
                if (c != '\\') {
                    escaped = false;
                }
            }
            output.append(source, start, position);
        }

        private void copyComment() {
            int start = position;
            position += 2;
            while (position < source.length() && source.charAt(position) != '\n'
                    && source.charAt(position) != '\r') {
                position++;
            }
            output.append(source, start, position);
        }

        private boolean copyAtKeyword() {
            output.append('@');
            position++;
            int whitespaceEnd = skipWhitespace(position);
            output.append(source, position, whitespaceEnd);
            position = whitespaceEnd;
            if (position >= source.length() || !isIdentifierStart(source.charAt(position))) {
                return false;
            }
            int end = identifierEnd(position);
            String keyword = source.substring(position, end);
            output.append(source, position, end);
            position = end;
            return language.atFrom().equals(keyword);
        }

        private void copyNumber() {
            int start = position;
            while (position < source.length()) {
                char c = source.charAt(position);
                if (!Character.isDigit(c) && c != '.') {
                    break;
                }
                position++;
            }
            output.append(source, start, position);
        }

        private void copyOperator() {
            char c = source.charAt(position++);
            output.append(c);
            if ("+-*/^<>=!".indexOf(c) >= 0) {
                expectsAtom = true;
            }
        }

        private int identifierEnd(int start) {
            int end = start;
            while (isIdentifierPart(charAt(end))) {
                end++;
            }
            return end;
        }

        private int skipWhitespace(int start) {
            int cursor = start;
            while (Character.isWhitespace(charAt(cursor))) {
                cursor++;
            }
            return cursor;
        }

        private char peek(int offset) {
            return charAt(position + offset);
        }

        private char peekFrom(int start, int offset) {
            return charAt(start + offset);
        }

        private char charAt(int index) {
            return index >= 0 && index < source.length() ? source.charAt(index) : '\0';
        }
    }

    private record PathToken(
            int start,
            int end,
            boolean absolute,
            String firstSegment,
            int firstSegmentStart,
            int firstSegmentEnd,
            boolean quotedFirst,
            boolean bareShortName) {
    }

    private record Segment(String value, int end, boolean quoted) {
    }

    record LanguageProfile(
            Set<String> referenceSeparators,
            Set<String> nonReferenceAtoms,
            String customCondition,
            String atFrom) {
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
