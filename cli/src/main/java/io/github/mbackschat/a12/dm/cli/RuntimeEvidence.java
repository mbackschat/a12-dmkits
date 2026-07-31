package io.github.mbackschat.a12.dm.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Which implementation produced a <b>semantic observation</b>, and which build projected it
 * (RESULT-CONTRACT-PROPOSAL §3). Carried by the runtime verbs — {@code model eval}, {@code model compute},
 * {@code rule eval} — whose output is an evaluation result rather than a verdict about a model.
 *
 * <p><b>Three keys, all required, none inferable from another.</b> {@code engine} names the evaluator;
 * {@code engineVersion} pins the build of that evaluator; {@code toolVersion} pins the {@code dmtool} that
 * projected and serialized the observation. They are equal today (one repository, one release), which is exactly
 * why all three are emitted rather than one derived from the others: evaluation semantics can move in the engine
 * while pointer spelling, summaries, or envelope shape move independently in the tool, so a single version cannot
 * identify what produced a retained result.
 *
 * <p>{@code verification} is a <em>different</em> axis and never merged with these — it records how a consistency
 * <em>verdict</em> was arbitrated. An interpreter run yields no verdict, so it has no {@code verification}; that
 * is also why {@link io.github.mbackschat.a12.dm.core.Verification} must never gain an
 * {@code INTERPRETER_OBSERVED} value.
 */
public record RuntimeEvidence(Engine engine, String engineVersion, String toolVersion) {

    /** The implementations that can produce a semantic observation. */
    public enum Engine {
        /** The real A12 kernel — the consistency/MVK authority. */
        A12_KERNEL,
        /** The kernel-free {@code :interpreter} evaluator, which every runtime verb uses. */
        DM_INTERPRETER
    }

    public RuntimeEvidence {
        if (engine == null || engineVersion == null || toolVersion == null) {
            throw new IllegalArgumentException("runtime evidence needs all three of engine/engineVersion/toolVersion");
        }
    }

    /**
     * The evidence for an observation produced by the bundled {@code :interpreter}. The engine ships in this
     * repository's release, so its version is this release's version — read from the same baked resource
     * {@code --version} uses, never hard-coded.
     */
    public static RuntimeEvidence interpreter() {
        String release = VersionInfo.current().rulekit();
        return new RuntimeEvidence(Engine.DM_INTERPRETER, release, release);
    }

    /**
     * The evidence for an observation produced by the real kernel: the engine version is the kernel <b>actually on
     * the classpath</b> (never the built-against pin — a newer kernel is the F11 tolerance posture, and it is the
     * runtime one that computed the answer).
     */
    public static RuntimeEvidence kernel() {
        VersionInfo version = VersionInfo.current();
        return new RuntimeEvidence(Engine.A12_KERNEL, version.kernelRuntime(), version.rulekit());
    }

    /** Write the three flat envelope members (§3 pins them flat, deliberately not nested under an object). */
    void writeTo(ObjectNode root) {
        root.put("engine", engine.name());
        root.put("engineVersion", engineVersion);
        root.put("toolVersion", toolVersion);
    }
}
