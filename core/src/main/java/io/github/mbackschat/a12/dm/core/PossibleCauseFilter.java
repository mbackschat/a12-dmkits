package io.github.mbackschat.a12.dm.core;

import java.util.List;

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
}
