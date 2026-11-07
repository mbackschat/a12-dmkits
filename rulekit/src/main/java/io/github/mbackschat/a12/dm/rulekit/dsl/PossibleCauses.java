package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;
import java.util.stream.Stream;

import io.github.mbackschat.a12.dm.core.PossibleCauseFilter;

/**
 * Which entities a fired rule may report as its possible causes: those of {@link #ifFilled} where they are filled,
 * those of {@link #ifNotFilled} where they are not. Each must be an entity the rule's condition references, which A12
 * checks ([KERNEL-FINDINGS KF682]); A12 stores the lists as relative paths, which {@link Rule#possibleCauseFilter()}
 * derives from the rule's group, so a handle is never spelled by hand.
 *
 * <p>Start from {@link #filled} or {@link #notFilled} and add the other list with {@link #andNotFilled} or
 * {@link #andFilled}. An empty list keeps nothing of its kind: {@code filled(premium)} reports no unfilled cause.
 */
public record PossibleCauses(List<ErrorEntity> ifFilled, List<ErrorEntity> ifNotFilled) {

    public PossibleCauses {
        ifFilled = List.copyOf(ifFilled);
        ifNotFilled = List.copyOf(ifNotFilled);
    }

    /** These entities, where filled, and no unfilled one. */
    public static PossibleCauses filled(ErrorEntity... entities) {
        return new PossibleCauses(List.of(entities), List.of());
    }

    /** These entities, where not filled, and no filled one. */
    public static PossibleCauses notFilled(ErrorEntity... entities) {
        return new PossibleCauses(List.of(), List.of(entities));
    }

    /** This set with {@code entities} added to the filled list. */
    public PossibleCauses andFilled(ErrorEntity... entities) {
        return new PossibleCauses(Stream.concat(ifFilled.stream(), Stream.of(entities)).toList(), ifNotFilled);
    }

    /** This set with {@code entities} added to the unfilled list. */
    public PossibleCauses andNotFilled(ErrorEntity... entities) {
        return new PossibleCauses(ifFilled, Stream.concat(ifNotFilled.stream(), Stream.of(entities)).toList());
    }

    /** The stored filter of a rule in {@code group}: each entity's one admitted entry. */
    PossibleCauseFilter filterFrom(GroupRef group) {
        return PossibleCauseFilter.ofEntities(group.path().value(), paths(ifFilled), paths(ifNotFilled));
    }

    private static List<String> paths(List<ErrorEntity> entities) {
        return entities.stream().map(entity -> entity.entityPath().value()).toList();
    }
}
