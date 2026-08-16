package io.github.mbackschat.a12.dm.cli;

import java.util.Set;

/**
 * <b>Directories that are never this repository's content, for a guard that walks the whole tree.</b>
 *
 * <p><b>Why this is shared rather than repeated.</b> Each whole-repo walker used to carry its own skip set, and
 * they drifted: {@code .claude} was added to one of them on 2026-08-16 after a cold review found that guard
 * descending into {@code .claude/worktrees/}, and the other two kept descending. That is not a hypothetical —
 * this project's own review workflow creates a full checkout at {@code .claude/worktrees/<agent>/} while a review
 * runs, so a guard that walks into it counts the repository <b>twice</b>. It cost a real false red: with one
 * review agent live, {@code QuotedTitleCitationRatchetTest} reported 18 quoted-title citations against a ceiling
 * of 9 when the true repo-wide count was 10 and the edit under test had changed it by zero.
 *
 * <p>The double-count is the mild failure. The dangerous one is the opposite direction: a vacuity floor or a
 * fully-used assertion can be satisfied <b>entirely by the worktree copy</b> while the primary scan is broken,
 * which is precisely the hole those floors exist to close.
 *
 * <p>A walker that resolves a specific subdirectory ({@code docs/}, {@code examples/}, a module's {@code src/})
 * is unaffected and needs nothing from here — {@code .claude} is not under any of them.
 */
final class RepoWalk {

    private RepoWalk() {
    }

    /**
     * Never repository content: build output, VCS and tool state, dependency trees, and this project's own agent
     * worktrees. Add to this set rather than to a caller's private copy.
     */
    static final Set<String> SKIPPED_DIRECTORIES =
            Set.of("build", ".git", ".gradle", "node_modules", ".claude");
}
