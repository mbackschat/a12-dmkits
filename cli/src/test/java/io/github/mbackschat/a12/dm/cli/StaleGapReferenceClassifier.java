package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies interpreter-gap references inside logical source comments.
 *
 * <p>A logical comment is one block comment or a run of adjacent {@code //} comments. Keeping the complete
 * comment makes wrapped present-tense claims visible. Each {@code IGn} is then classified from the nearest
 * open/historical marker in its own punctuation-delimited clause, so one reference cannot exempt or condemn
 * another reference on the same line.
 */
final class StaleGapReferenceClassifier {

    private static final Pattern IG = Pattern.compile("\\bIG(\\d+)\\b");
    private static final Pattern OPEN = Pattern.compile(
            "(?<![A-Za-z0-9_])(?:pending|await(?:s|ed|ing)?|not[\\s-]*yet|future\\s+work|blocked\\s+on|todo"
                    + "|still\\s+(?:open|pending)|open\\s+gap|do(?:es)?\\s+not\\s*\\(\\s*yet\\s*\\))(?![A-Za-z0-9_])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HISTORICAL = Pattern.compile(
            "\\b(?:no\\s+longer(?:\\s+(?:open|pending|blocked))?|once|was|graduated(?:\\s+from)?"
                    + "|formerly|retired|closed|closes?)\\b|\\bIG\\d+\\s*(?:->|→)\\s*IF\\d+\\b",
            Pattern.CASE_INSENSITIVE);

    private StaleGapReferenceClassifier() {
    }

    static List<Integer> deletedOpenReferences(String source, Set<Integer> liveGaps) {
        return deletedOpenReferenceHits(source, liveGaps).stream().map(Hit::gap).toList();
    }

    static List<Hit> deletedOpenReferenceHits(String source, Set<Integer> liveGaps) {
        List<Hit> hits = new ArrayList<>();
        for (LogicalComment comment : logicalComments(source)) {
            classify(comment, liveGaps, hits);
        }
        return hits;
    }

    private static void classify(LogicalComment comment, Set<Integer> liveGaps, List<Hit> hits) {
        Matcher references = IG.matcher(comment.text());
        while (references.find()) {
            int gap = Integer.parseInt(references.group(1));
            if (liveGaps.contains(gap)) {
                continue;
            }
            int clauseStart = previousClauseBoundary(comment.text(), references.start());
            int clauseEnd = nextClauseBoundary(comment.text(), references.end());
            String clause = comment.text().substring(clauseStart, clauseEnd);
            int referenceStart = references.start() - clauseStart;
            int referenceEnd = references.end() - clauseStart;
            Marker nearest = nearestMarker(clause, referenceStart, referenceEnd);
            if (nearest != null && nearest.kind() == MarkerKind.OPEN) {
                int line = comment.startLine() + countNewlines(comment.text(), references.start());
                hits.add(new Hit(gap, line, clause.replaceAll("\\s+", " ").trim()));
            }
        }
    }

    private static Marker nearestMarker(String clause, int referenceStart, int referenceEnd) {
        List<Marker> markers = new ArrayList<>();
        addMarkers(markers, clause, OPEN, MarkerKind.OPEN);
        addMarkers(markers, clause, HISTORICAL, MarkerKind.HISTORICAL);
        if (countMatches(IG, clause) == 1 && markers.stream().anyMatch(marker -> marker.kind() == MarkerKind.HISTORICAL)) {
            return markers.stream().filter(marker -> marker.kind() == MarkerKind.HISTORICAL).findFirst().orElseThrow();
        }
        return markers.stream()
                .min(Comparator.comparingInt((Marker marker) ->
                                distance(marker.start(), marker.end(), referenceStart, referenceEnd))
                        .thenComparing(marker -> marker.kind() == MarkerKind.HISTORICAL ? 0 : 1))
                .orElse(null);
    }

    private static int countMatches(Pattern pattern, String text) {
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static void addMarkers(List<Marker> markers, String clause, Pattern pattern, MarkerKind kind) {
        Matcher matcher = pattern.matcher(clause);
        while (matcher.find()) {
            markers.add(new Marker(matcher.start(), matcher.end(), kind));
        }
    }

    private static int distance(int markerStart, int markerEnd, int referenceStart, int referenceEnd) {
        if (markerEnd < referenceStart) {
            return referenceStart - markerEnd;
        }
        if (referenceEnd < markerStart) {
            return markerStart - referenceEnd;
        }
        return 0;
    }

    private static int previousClauseBoundary(String text, int before) {
        for (int i = before - 1; i >= 0; i--) {
            if (isClauseBoundary(text.charAt(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    private static int nextClauseBoundary(String text, int after) {
        for (int i = after; i < text.length(); i++) {
            if (isClauseBoundary(text.charAt(i))) {
                return i;
            }
        }
        return text.length();
    }

    private static boolean isClauseBoundary(char c) {
        return c == ';' || c == '.' || c == '!' || c == '?';
    }

    private static int countNewlines(String text, int before) {
        int lines = 0;
        for (int i = 0; i < before; i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static List<LogicalComment> logicalComments(String source) {
        List<LogicalComment> comments = new ArrayList<>();
        String[] lines = source.split("\\R", -1);
        StringBuilder lineComment = null;
        int lineCommentStart = 0;
        StringBuilder blockComment = null;
        int blockCommentStart = 0;

        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            int lineNumber = index + 1;
            if (blockComment != null) {
                int end = line.indexOf("*/");
                if (end >= 0) {
                    blockComment.append(line, 0, end);
                    comments.add(new LogicalComment(blockComment.toString(), blockCommentStart));
                    blockComment = null;
                } else {
                    blockComment.append(line).append('\n');
                }
                continue;
            }

            int blockStart = line.indexOf("/*");
            int slashStart = line.indexOf("//");
            if (blockStart >= 0 && (slashStart < 0 || blockStart < slashStart)) {
                if (lineComment != null) {
                    comments.add(new LogicalComment(lineComment.toString(), lineCommentStart));
                    lineComment = null;
                }
                int end = line.indexOf("*/", blockStart + 2);
                if (end >= 0) {
                    comments.add(new LogicalComment(line.substring(blockStart + 2, end), lineNumber));
                } else {
                    blockComment = new StringBuilder(line.substring(blockStart + 2)).append('\n');
                    blockCommentStart = lineNumber;
                }
            } else if (slashStart >= 0) {
                if (lineComment == null) {
                    lineComment = new StringBuilder();
                    lineCommentStart = lineNumber;
                }
                lineComment.append(line.substring(slashStart + 2)).append('\n');
            } else if (lineComment != null) {
                comments.add(new LogicalComment(lineComment.toString(), lineCommentStart));
                lineComment = null;
            }
        }
        if (lineComment != null) {
            comments.add(new LogicalComment(lineComment.toString(), lineCommentStart));
        }
        if (blockComment != null) {
            comments.add(new LogicalComment(blockComment.toString(), blockCommentStart));
        }
        return comments;
    }

    record Hit(int gap, int line, String excerpt) {
    }

    private record LogicalComment(String text, int startLine) {
    }

    private enum MarkerKind { OPEN, HISTORICAL }

    private record Marker(int start, int end, MarkerKind kind) {
    }
}
