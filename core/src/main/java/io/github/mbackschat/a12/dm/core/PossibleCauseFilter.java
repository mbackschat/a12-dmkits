package io.github.mbackschat.a12.dm.core;

import java.util.List;

import io.github.mbackschat.a12.dm.predicates.PossibleCausePredicatesKt;

/**
 * A rule's {@code filterForPossiblyCausingEntities} as A12 stores it: the paths, relative to the rule's group, of the
 * entities whose filled ({@link #entitiesIfFilled}) or unfilled ({@link #entitiesIfNotFilled}) instances A12 may
 * report as possibly causing the rule to fire. Present with both lists empty, it is still a filter, one that lets no
 * entity through. A12 refuses an entry that is not the relative path of an entity the condition references, and a
 * list that repeats an entry ([KERNEL-FINDINGS KF682]); the spelling it admits is
 * {@code PossibleCausePredicatesKt.possibleCauseEntry}.
 *
 * @param entitiesIfFilled    A12's {@code entitiesEnabledAsPossibleCauseIfFilled}, each entry's {@code relativePath}
 * @param entitiesIfNotFilled A12's {@code entitiesEnabledAsPossibleCauseIfNotFilled}, likewise
 */
public record PossibleCauseFilter(List<String> entitiesIfFilled, List<String> entitiesIfNotFilled) {

    public PossibleCauseFilter {
        entitiesIfFilled = List.copyOf(entitiesIfFilled);
        entitiesIfNotFilled = List.copyOf(entitiesIfNotFilled);
    }

    /** The filter of a rule in {@code groupPath} naming these entities, each an absolute path, by its one admitted entry. */
    public static PossibleCauseFilter ofEntities(String groupPath, List<String> ifFilled, List<String> ifNotFilled) {
        return new PossibleCauseFilter(entries(groupPath, ifFilled), entries(groupPath, ifNotFilled));
    }

    /** The absolute paths of {@link #entitiesIfFilled}, for a rule in {@code groupPath}. */
    public List<String> entityPathsIfFilled(String groupPath) {
        return resolved(groupPath, entitiesIfFilled);
    }

    /** The absolute paths of {@link #entitiesIfNotFilled}, for a rule in {@code groupPath}. */
    public List<String> entityPathsIfNotFilled(String groupPath) {
        return resolved(groupPath, entitiesIfNotFilled);
    }

    private static List<String> entries(String groupPath, List<String> entityPaths) {
        return entityPaths.stream()
                .map(entity -> PossibleCausePredicatesKt.possibleCauseEntry(groupPath, entity)).toList();
    }

    // Against the GROUP, not the rule element: an entry is relative to the rule's group (KF682), so "./" is the group.
    private static List<String> resolved(String groupPath, List<String> entries) {
        EntityPath group = EntityPath.of(groupPath);
        return entries.stream().map(entry -> group.resolve(entry).value()).toList();
    }
}
