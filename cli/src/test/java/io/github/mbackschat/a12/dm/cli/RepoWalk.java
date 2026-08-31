package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>The repository's own files, for a guard that scans all of them.</b> The set is what <b>git tracks</b>, not
 * what happens to be on disk.
 *
 * <p><b>Why git decides.</b> A guard that walks the working directory takes every tool's output as its input.
 * This one enumerated exclusions instead — {@code build}, {@code .git}, {@code node_modules}, {@code .claude} —
 * and the list only ever grew, because the set of things that write into a checkout is open-ended: an IDE's
 * compiled output and its copies of non-Java sources, a coverage run, an editor's swap file, this project's own
 * review worktrees. Each addition arrived the same way, as a guard failing on a file nobody wrote. Tracked
 * files are the exact, closed set, and they are also what CI asserts over, so a local run and a CI run finally
 * scan the same thing.
 *
 * <p>Measured, on the checkout that motivated this: an Eclipse-derived language server recreated
 * {@code <module>/bin/} for all eight modules within 180 seconds of an idle tree, having been deleted, with no
 * edit to provoke it. Those directories carry copies of every non-Java source in a source folder, so a
 * {@code .kt}-scanning guard counted the repository twice and a citation ratchet failed on an edit that had
 * changed its count by zero. No exclusion list closes that class; asking git does.
 *
 * <p>A walker that resolves a specific subdirectory ({@code docs/}, a module's {@code src/}) is unaffected and
 * needs nothing from here.
 */
final class RepoWalk {

    private static volatile List<Path> tracked;

    private RepoWalk() {
    }

    /**
     * Every file git tracks, as absolute paths under {@code root}. Cached — the set is stable for a run.
     *
     * @throws IllegalStateException if git cannot answer, which is a broken environment rather than a finding:
     *     a guard that silently scanned nothing would pass vacuously.
     */
    static List<Path> trackedFiles(Path root) {
        List<Path> cached = tracked;
        if (cached == null) {
            cached = List.copyOf(readTrackedFiles(root));
            tracked = cached;
        }
        return cached;
    }

    private static List<Path> readTrackedFiles(Path root) {
        try {
            Process git = new ProcessBuilder("git", "-C", root.toString(), "ls-files", "-z")
                    .redirectErrorStream(true)
                    .start();
            String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = git.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("git ls-files failed (" + exit + ") in " + root + ": " + out);
            }
            List<Path> files = new ArrayList<>();
            for (String name : out.split("\0")) {
                if (!name.isEmpty()) {
                    Path p = root.resolve(name);
                    // A tracked path can be absent from the worktree (a sparse checkout, a pending delete).
                    if (Files.isRegularFile(p)) {
                        files.add(p);
                    }
                }
            }
            if (files.isEmpty()) {
                throw new IllegalStateException("git tracks no files under " + root + " — the scan would be vacuous");
            }
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException("could not ask git which files " + root + " tracks", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted asking git which files " + root + " tracks", e);
        }
    }
}
