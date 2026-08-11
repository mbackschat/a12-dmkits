package io.github.mbackschat.a12.dm.adapter.perf

/**
 * Whether a **self-referential** timing bar may fail here.
 *
 * A self-referential bar judges this run's numbers against a constant ([PerfBounds.SUB_QUADRATIC_MAX]) or against
 * a number recorded earlier (the anchor). Both are properties of the machine as much as of the code, so where they
 * run decides what a breach means. The **cross-engine** bars are the opposite and are deliberately NOT governed
 * here: the interpreter and the kernel are measured in the same run on the same vCPUs, so contention moves both
 * and their ratio survives it. That is why "the interpreter beats both kernel strategies, at both scales, with the
 * lowest per-unit cost" gates everywhere while everything below is conditional.
 *
 * **The two kinds of self-referential bar do NOT share a precondition, and collapsing them switches guards off.**
 *
 * - A **growth ratio** is measured on the current-thread CPU clock, which already filters a sibling Gradle fork
 *   preempting the JVM — that was the 2026-08-03 fix. So the isolated lane is not a precondition for it:
 *   requiring one would make `CharsetPerfTest` and `CustomTypePerfTest` [UNJUDGED] wherever they run, including on
 *   a maintainer's own machine, which after this split is the only place they can still fail — a hosted push sets
 *   `CI` and therefore flags them like everything else. Only the machine matters: [forGrowthFloor].
 * - An **absolute time** against a stored number has no clock that filters anything out of it, so it needs the
 *   isolated single-fork lane as well as a dedicated machine: [forAbsoluteAnchor].
 *
 * **What the shared-machine axis is for, measured rather than assumed.** `perfGateTest` is single-fork and
 * non-cacheable, so it satisfies the lane axis on a hosted runner too — and the estate still red-lined there: the
 * same commit passed one night and failed the next two on `MSC-multi-star-correlation` at a median 9.11x against
 * the 9.0 floor, and across three hosted runs the 18 growth observations spanned 2.48x-7.98x, leaving the closest
 * lane at 89 % of its floor. `CharsetPerfTest` has the same history at 9.37x and 10.22x, all of them false. A
 * hosted runner is a VM on a shared physical host: neighbours evict cache lines and consume memory bandwidth,
 * which is spent in user-mode cycles, so the CPU clock does not filter it and no finer clock would. A red that
 * cannot be distinguished from a real regression, on a lane nobody watches interactively, is a false positive by
 * construction — and the standing requirement is that the nightly never produces one.
 *
 * **A flag is not silence.** The breach prints a greppable line, and the gate's medians land in the committed
 * record with their status, so the trend is visible in `scripts/perf-history.sh` rather than inferred from a build
 * that happened to be green. What a flag gives up is the automatic stop; what it buys is a red that means
 * something.
 */
enum class TimingEnforcement {
    /** A breach fails the build: the measurement is valid AND the machine is ours. */
    GATED,

    /** A breach is reported and recorded, and the build continues — valid measurement, shared hardware. */
    FLAGGED,

    /** Nothing is claimed: the measurement cannot support a verdict here at all. */
    UNJUDGED,

    ;

    companion object {

        /**
         * A growth ratio on the CPU clock. Valid under parallel forks, so only the machine decides.
         */
        fun forGrowthFloor(): TimingEnforcement = forGrowthFloor(sharedMachine = onSharedMachine())

        /** The rule itself, on scripted inputs — checked without a clock, as `AnchorBudget` already is. */
        fun forGrowthFloor(sharedMachine: Boolean): TimingEnforcement = if (sharedMachine) FLAGGED else GATED

        /**
         * An absolute median against this runner class's committed number. Needs the isolated lane too: no clock
         * filters a parallel-fork slice out of an absolute time.
         */
        fun forAbsoluteAnchor(): TimingEnforcement = forAbsoluteAnchor(
            isolatedLane = System.getProperty(PerfAnchors.LANE_PROPERTY) == "true",
            sharedMachine = onSharedMachine(),
        )

        /** The rule itself, on scripted inputs. */
        fun forAbsoluteAnchor(isolatedLane: Boolean, sharedMachine: Boolean): TimingEnforcement = when {
            !isolatedLane -> UNJUDGED
            sharedMachine -> FLAGGED
            else -> GATED
        }

        /**
         * A hosted runner sets `CI`. This is the same discriminator [PerfEnvironment] already appends to its key,
         * so the record's `linux-amd64-ci` rows and this decision cannot disagree about which machine class a run
         * belongs to.
         */
        private fun onSharedMachine(): Boolean = !System.getenv("CI").isNullOrBlank()
    }
}
