package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.HasEntityPath;

/**
 * What a {@link Rule} reports on: one field or one group, A12's two kinds of error entity (KERNEL-FINDINGS KF601
 * admits a group, KF603 locates the message at its pointer).
 *
 * <p>Sealed so a consumer that treats the two differently is told by the compiler where it has to. Whether a
 * condition references one is the same law for both ({@code ConditionReferences#covers}); the deferred check
 * still names which kind it found unreferenced.
 */
public sealed interface ErrorEntity extends HasEntityPath permits FieldRef, GroupRef {
}
