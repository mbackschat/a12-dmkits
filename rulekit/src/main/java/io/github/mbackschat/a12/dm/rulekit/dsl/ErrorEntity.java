package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.HasEntityPath;

/**
 * What a {@link Rule} reports on: one field or one group, A12's two kinds of error entity (KERNEL-FINDINGS KF601
 * admits a group, KF603 locates the message at its pointer).
 *
 * <p>Sealed so a consumer that treats the two differently is told by the compiler where it has to: the deferred
 * reference check does, because a field inside a group target does not reference the target.
 */
public sealed interface ErrorEntity extends HasEntityPath permits FieldRef, GroupRef {
}
