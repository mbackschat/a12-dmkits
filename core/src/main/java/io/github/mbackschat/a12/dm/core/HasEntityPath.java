package io.github.mbackschat.a12.dm.core;

/**
 * Implemented by anything that <b>names a model entity</b> — a field ref, a group ref, a resolved info record —
 * so that "does this lie inside that" can be asked of the two <em>things</em> rather than of two strings pulled
 * out of them.
 *
 * <p><b>Why this exists rather than callers using {@link EntityPath} directly.</b> The call sites that ask this
 * question mostly already hold typed refs, and were spelling
 * {@code EntityPaths.isStrictlyInside(errorField.path(), group.path())} — extracting two strings purely in order
 * to compare them, which is the moment a string-prefix operation becomes available to reach for. With the
 * relation on the ref, {@code errorField.isStrictlyInside(group)} never produces a string at all, and the
 * historical defects ({@code CG16}'s segment-boundary crossing, {@code CG17}'s excluded subtree root) are
 * unspellable at that site. The two names carry the same distinction they carry on {@code EntityPath}: whether a
 * path counts as inside itself.
 *
 * <p>Deliberately <b>additive</b>. The implementing records keep {@code String path()} as their component, so
 * this breaks no public signature; it removes the extraction, not the representation. Holding the parsed value
 * as the component is a separate, breaking change (DG37c) and a call the maintainer makes, not this interface.
 */
public interface HasEntityPath {

    /** The entity's absolute model path, in its canonical string form. */
    String path();

    /** The same path as segments — parsed on demand, since implementors store the string form. */
    default EntityPath entityPath() {
        return EntityPath.of(path());
    }

    /** Whether this entity is {@code ancestor} itself, or lies below it. */
    default boolean isInside(HasEntityPath ancestor) {
        return entityPath().isInside(ancestor.entityPath());
    }

    /** Whether this entity lies strictly below {@code ancestor}, which is therefore not one of its own. */
    default boolean isStrictlyInside(HasEntityPath ancestor) {
        return entityPath().isStrictlyInside(ancestor.entityPath());
    }
}
