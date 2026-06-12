package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Comments carried as a <b>side-channel</b> beside the immutable AST (the "carrier (c)" design): a map of
 * {@code ;;} line comments keyed by <em>node identity</em>, so the {@link Condition} hierarchy stays pure
 * semantics while the renderer can still emit (authored or read-preserved) comments at a node's position.
 * Identity-keyed (not value-keyed) so two structurally-equal branches keep distinct comments. Immutable —
 * {@link #before} returns a copy.
 *
 * <p>Mirrors the kernel grammar, where a comment attaches {@code before}/{@code after} a condition branch
 * ({@code teilBedingung}) or operation — both carried here. A trailing ({@code after}) comment is rendered
 * newline-terminated so a following operator cannot be swallowed by the {@code ;;} line comment.
 */
public final class Comments {

    private final Map<Condition, String> before;
    private final Map<Condition, String> after;

    private Comments(Map<Condition, String> before, Map<Condition, String> after) {
        // each map is replaced wholesale on mutation (never mutated in place), so sharing a reference
        // across copies is safe — this is a persistent map built by copy-on-write.
        this.before = before;
        this.after = after;
    }

    /** An empty comment side-channel. */
    public static Comments none() {
        return new Comments(new IdentityHashMap<>(), new IdentityHashMap<>());
    }

    private static final java.util.regex.Pattern LEADING_COMMENT =
            java.util.regex.Pattern.compile("^\\s*;;[ \\t]?([^\\r\\n]*)");

    /**
     * The leading {@code ;;} comment text of a raw condition, if it begins with one — concrete-syntax
     * sourcing for the read→modify→write-back preservation path (the loss the AST otherwise drops).
     */
    public static Optional<String> leadingCommentOf(String conditionText) {
        java.util.regex.Matcher m = LEADING_COMMENT.matcher(conditionText);
        return m.find() ? Optional.of(m.group(1).strip()) : Optional.empty();
    }

    /** A copy with {@code text} attached as a leading {@code ;;} comment on {@code node}. */
    public Comments before(Condition node, String text) {
        Map<Condition, String> next = new IdentityHashMap<>(before);
        next.put(node, text);
        return new Comments(next, after);
    }

    /** A copy with {@code text} attached as a trailing {@code ;;} comment on {@code node}. */
    public Comments after(Condition node, String text) {
        Map<Condition, String> next = new IdentityHashMap<>(after);
        next.put(node, text);
        return new Comments(before, next);
    }

    /** The leading comment on {@code node}, if any. */
    Optional<String> beforeOf(Condition node) {
        return Optional.ofNullable(before.get(node));
    }

    /** The trailing comment on {@code node}, if any. */
    Optional<String> afterOf(Condition node) {
        return Optional.ofNullable(after.get(node));
    }

    /** Whether no comments are attached (the renderer can then take its plain canonical path). */
    public boolean isEmpty() {
        return before.isEmpty() && after.isEmpty();
    }
}
