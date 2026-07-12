package io.github.mbackschat.a12.dm.rulekit.catalog;

/**
 * What a catalog {@code gotcha} is, so the F4a gotcha/INFO channel can emit the author-relevant ones
 * and stay silent on the rest (the "gotcha-relevance curation" the first channel increment flagged).
 *
 * <ul>
 *   <li>{@link #SURPRISE} — a genuine semantic surprise: correct-but-unexpected behavior an author
 *       (or LLM) would likely get wrong (leap-day arithmetic, {@code Sum} counts unset as 0,
 *       {@code GroupFilled} = "row exists"). The heart of the channel.</li>
 *   <li>{@link #AUTHORING} — a useful usage/capability note or boundary caveat (an enclosing
 *       {@code GroupFilled} satisfies the error-field rule; confirm an edge against the BA guide).
 *       Also surfaced — it helps the author.</li>
 *   <li>{@link #RENDERING} — trivia rulekit's renderer already handles or that never reaches the
 *       author (the comparison symbol is language-independent; parentheses when mixing And/Or). The
 *       channel <em>suppresses</em> these.</li>
 * </ul>
 */
public enum GotchaKind {
    SURPRISE,
    AUTHORING,
    RENDERING
}
