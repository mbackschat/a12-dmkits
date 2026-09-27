package io.github.mbackschat.a12.dm.consistency

/** KF356/R5-PE: only a resolved segment reaches the H-model suffix check. */
internal fun TrackedFactBase.hModelPathSuffixFindings(): List<Finding> = declarations().flatMap { element ->
    pathContributions(element).filter { it.family == CheckFamily.H_MODEL_PATH_SUFFIX }
        .map { it.contribution.finding }
}
