package io.github.mbackschat.a12.dm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;

/**
 * <b>Source with its comments blanked, so a rule applies to CODE only.</b> Without stripping, a doc comment that
 * illustrates the pattern a guard forbids is indistinguishable from a real occurrence, and a guard then has to
 * delete its own illustration to stay green.
 *
 * <p><b>Why one implementation.</b> Three separate ones had accumulated. Two matched {@code /*} through {@code *}{@code /}
 * with a non-greedy regex and dropped any line starting with {@code //}; the third handled line comments only. All
 * three read those delimiters INSIDE string literals, so a literal containing one silently blanked real code or
 * survived as apparent code. Kotlin block comments additionally nest, which a first-close regex cannot express —
 * applying Java's rule to Kotlin once blanked 1,179 lines across 10 files, any write site among them invisible.
 *
 * <p>Character positions are preserved, because a caller that reports an offset into the stripped text must be
 * able to point at the original.
 */
public final class SourceText {

    private static final int PLAIN = 0;
    private static final int RAW = 1;

    private SourceText() {
    }

    /** Comments blanked, with the nesting rule taken from the file's language. */
    public static String uncommented(Path source) throws IOException {
        try {
            return uncommented(Files.readString(source), nestsBlockComments(source));
        } catch (IllegalStateException unmodelled) {
            throw new IllegalStateException(source + ": " + unmodelled.getMessage(), unmodelled);
        }
    }

    /** Kotlin nests block comments; Java, Groovy and JavaScript close on the first delimiter. */
    public static boolean nestsBlockComments(Path source) {
        return source.getFileName().toString().endsWith(".kt");
    }

    /**
     * The stripped text, plus whether the scan ended OUTSIDE every comment and literal. A non-neutral end means
     * the lexer and the file disagree — the signature of the incident this class exists to prevent, where 10 Java
     * files ended mid-comment and 1,179 lines of real code were blanked.
     */
    record Scan(String text, String endedIn, int openedAt) {

        boolean endedNeutral() {
            return endedIn.isEmpty();
        }
    }

    /**
     * Comments blanked in a FRAGMENT — a substring of a larger file, or a snippet built in a test. Imbalance is
     * expected here (a cut can fall inside a comment or literal), so the scan is best-effort and never refuses.
     * Use {@link #uncommented(Path)} for a whole source, where an unterminated scan is a lexer defect.
     */
    public static String uncommentedFragment(String fragment, boolean nestsBlockComments) {
        return scan(fragment, nestsBlockComments).text();
    }

    /**
     * Comments blanked.
     *
     * @throws IllegalStateException if the scan ends inside a comment or literal. That means the source uses a
     *     construct this lexer does not model, and the text it returned would be partly blanked CODE — a census
     *     over it would find nothing in what it never saw. Refusing is the only safe answer: it is loud, and it
     *     names the construct so the lexer can be taught it.
     */
    public static String uncommented(String source, boolean nestsBlockComments) {
        Scan scan = scan(source, nestsBlockComments);
        if (!scan.endedNeutral()) {
            throw new IllegalStateException("the scan ended in " + scan.endedIn() + " opened at offset "
                    + scan.openedAt() + "; this lexer models line and block comments, strings with escapes, raw "
                    + "strings, char literals, backtick identifiers and Kotlin string templates, and nothing else");
        }
        return scan.text();
    }

    static Scan scan(String source, boolean nestsBlockComments) {
        var out = new StringBuilder(source.length());
        int openedAt = -1;
        int blockDepth = 0;
        boolean lineComment = false;
        boolean inString = false;
        boolean inRawString = false;
        boolean inChar = false;
        boolean inBacktick = false;
        // A Kotlin `${...}` template returns to CODE, which may open another string; Java has no such
        // construct, so there `${` inside a text block is literal text. Only the nesting needs modelling:
        // each entry remembers the string kind to resume and how deep its braces currently are.
        var templates = new ArrayDeque<int[]>();
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            String rest2 = i + 2 <= source.length() ? source.substring(i, i + 2) : "";
            boolean rest3IsTripleQuote = i + 3 <= source.length() && source.startsWith("\"\"\"", i);
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    out.append(c);
                } else {
                    out.append(' ');
                }
            } else if (blockDepth > 0) {
                if (nestsBlockComments && "/*".equals(rest2)) {
                    blockDepth++;
                    out.append("  ");
                    i++;
                } else if ("*/".equals(rest2)) {
                    blockDepth--;
                    out.append("  ");
                    i++;
                } else {
                    out.append(c == '\n' ? c : ' ');
                }
            } else if (inBacktick) {
                // A Kotlin backtick identifier is a literal, and test names here routinely contain an apostrophe.
                out.append(c);
                if (c == '`') {
                    inBacktick = false;
                }
            } else if (nestsBlockComments && inRawString && "${".equals(rest2)) {
                templates.push(new int[] {RAW, 1});
                inRawString = false;
                out.append("${");
                i++;
            } else if (inRawString) {
                // A raw string CLOSES on the LAST three of a quote run, not the first: `\"\"\"x\"\"\"\"` holds the
                // content `x\"`. Taking the first three left a stray quote that opened a phantom string, and every
                // later construct in the file was then read in the wrong state.
                int run = 0;
                while (i + run < source.length() && source.charAt(i + run) == '"') {
                    run++;
                }
                if (run >= 3) {
                    out.append("\"".repeat(run));
                    i += run - 1;
                    inRawString = false;
                } else if (run > 0) {
                    out.append("\"".repeat(run));
                    i += run - 1;
                } else {
                    out.append(c);
                }
            } else if (nestsBlockComments && inString && "${".equals(rest2)) {
                templates.push(new int[] {PLAIN, 1});
                inString = false;
                out.append("${");
                i++;
            } else if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < source.length()) {
                    out.append(source.charAt(i + 1));
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (inChar) {
                out.append(c);
                if (c == '\\' && i + 1 < source.length()) {
                    out.append(source.charAt(i + 1));
                    i++;
                } else if (c == '\'') {
                    inChar = false;
                }
            } else if (!templates.isEmpty() && (c == '{' || c == '}')) {
                int[] top = templates.peek();
                top[1] += c == '{' ? 1 : -1;
                out.append(c);
                if (top[1] == 0) {
                    templates.pop();
                    if (top[0] == RAW) {
                        inRawString = true;
                    } else {
                        inString = true;
                    }
                }
            } else if ("//".equals(rest2)) {
                lineComment = true;
                openedAt = i;
                out.append("  ");
                i++;
            } else if ("/*".equals(rest2)) {
                blockDepth = 1;
                openedAt = i;
                out.append("  ");
                i++;
            } else if (rest3IsTripleQuote) {
                inRawString = true;
                openedAt = i;
                out.append("\"\"\"");
                i += 2;
            } else if (c == '"') {
                inString = true;
                openedAt = i;
                out.append(c);
            } else if (c == '`') {
                inBacktick = true;
                openedAt = i;
                out.append(c);
            } else if (c == '\'') {
                inChar = true;
                openedAt = i;
                out.append(c);
            } else {
                out.append(c);
            }
        }
        String endedIn = blockDepth > 0 ? "a block comment (depth " + blockDepth + ")"
                : inRawString ? "a raw string"
                : inString ? "a string"
                : inChar ? "a char literal"
                : inBacktick ? "a backtick identifier"
                : !templates.isEmpty() ? "a string template"
                : "";
        return new Scan(out.toString(), endedIn, endedIn.isEmpty() ? -1 : openedAt);
    }
}
