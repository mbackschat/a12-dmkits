package io.github.mbackschat.a12.dm.adapter.capture.packet;

import java.nio.file.Path;
import java.util.List;

import io.github.mbackschat.a12.dm.adapter.capture.runner.kernel.KernelArtifacts;

/**
 * The COMPLETE capture-relevant identity (proposal §3; the external audit's Finding 5), resolved BEFORE
 * execution and AGAIN after every runner completes — {@code PacketWriter} refuses finalization when the
 * two differ, so no apparently-clean packet can be produced across a mid-run source, kernel-closure, or
 * runtime change. The explicit justified closure:
 *
 * <ul>
 *   <li>{@link #source}: the a12-dmkits revision + working-tree state + classified tree digest — this
 *       binds the CAPTURE implementation closure and the INTERPRETER closure alike (both are in-repo
 *       source, compiled from exactly this tree).</li>
 *   <li>{@link #kernelArtifacts}: every resolved semantics-affecting artifact on the executing classpath —
 *       the kernel groups ({@code kernel-*}, {@code base-model-*}, {@code mmtypings-*},
 *       {@code mmvalidators-*}) and the Groovy runtime the dynamic route executes through — each with its
 *       exact bytes hashed. One coordinate is never assumed to cover the whole execution.</li>
 *   <li>{@link #runtime}: the JVM identity (specification + exact runtime version, vendor, VM name) —
 *       which also binds the in-memory {@code javac} the java-static route compiles with (the JDK's own
 *       compiler, part of the same runtime).</li>
 * </ul>
 *
 * <p>Third-party libraries outside this closure (JSON parsing, test scaffolding) shape transport, not
 * observed semantics; they ride the in-repo build pins covered by {@link #source}.
 */
public record ClosureIdentity(
        SourceState source,
        List<KernelArtifacts.Identity> kernelArtifacts,
        RuntimeIdentity runtime) {

    public ClosureIdentity {
        kernelArtifacts = List.copyOf(kernelArtifacts);
    }

    /** The probe seam — the real one resolves git + classpath + JVM; tests inject fixed (or mid-run
     *  changing) identities to cover the refusal branches deterministically. */
    public interface Probe {
        ClosureIdentity resolve();
    }

    /** The real probe over {@code repoRoot} and this JVM. */
    public static Probe probe(Path repoRoot) {
        SourceState.Probe git = SourceState.gitProbe(repoRoot);
        return () -> new ClosureIdentity(git.state(), KernelArtifacts.resolveAll(), RuntimeIdentity.current());
    }

    /** The semantics-affecting JVM identity (machine-independent — never a hostname or path). */
    public record RuntimeIdentity(String javaSpecificationVersion, String javaVendor,
            String javaRuntimeVersion, String vmName) {

        public static RuntimeIdentity current() {
            return new RuntimeIdentity(
                    System.getProperty("java.specification.version"),
                    System.getProperty("java.vendor"),
                    System.getProperty("java.runtime.version"),
                    System.getProperty("java.vm.name"));
        }
    }
}
