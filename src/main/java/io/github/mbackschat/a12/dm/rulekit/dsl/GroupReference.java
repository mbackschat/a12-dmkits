package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * Anything usable where a group reference is expected — the pure {@link GroupRef} itself, or a
 * session-bound {@code GroupEditor} (in {@code dm.edit}) that downgrades to one. This lets
 * {@link BoundModel#rule(GroupReference)} accept either <em>without</em> rulekit depending on the
 * {@code dm.edit} layer: the editor implements this rulekit-owned type; rulekit never imports the
 * editor. The immutable AST still stores only the pure {@link GroupRef} (functional core / effects
 * at the edge).
 */
public interface GroupReference {

    /** The pure group ref this resolves to — identity for a {@link GroupRef}, a downgrade for an editor. */
    GroupRef ref();
}
