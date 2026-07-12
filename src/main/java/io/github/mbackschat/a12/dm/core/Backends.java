package io.github.mbackschat.a12.dm.core;

import java.util.Optional;
import java.util.ServiceLoader;

import io.github.mbackschat.a12.dm.core.spi.BackendInfo;
import io.github.mbackschat.a12.dm.core.spi.ConsistencyOracle;

/**
 * The single resolution point for the kernel-backend SPI ports (KERNEL-DECOUPLING-PROPOSAL §2b). The kits
 * are kernel-free; the real kernel lives behind narrow ports that the one backend ({@code :adapter})
 * implements and registers via {@link ServiceLoader} (a {@code META-INF/services} provider). A consumer
 * with the backend jar on the classpath (dmtool, JVM and native) gets the ports; a Maven-Central consumer
 * without it gets {@link Optional#empty()} and the degraded-mode contract (§2c).
 *
 * <p>Discovery is cached (a provider set is stable for a run); tests inject a fake with {@link #override}
 * and clear it with {@link #reset}. The rulekit read-back port lives one layer up (it speaks rulekit's AST)
 * and is resolved by {@code io.github.mbackschat.a12.dm.rulekit.spi.ConditionReadBack} itself — this class
 * owns only the two <em>core</em> ports it can name.
 */
public final class Backends {

    private static volatile ConsistencyOracle oracleOverride;
    private static volatile boolean oracleOverridden;
    private static volatile Optional<ConsistencyOracle> oracleCache;

    private static volatile BackendInfo infoOverride;
    private static volatile boolean infoOverridden;
    private static volatile Optional<BackendInfo> infoCache;

    private Backends() {
    }

    /** The consistency-gate backend, or empty when none is on the classpath (degraded mode). */
    public static Optional<ConsistencyOracle> consistencyOracle() {
        if (oracleOverridden) {
            return Optional.ofNullable(oracleOverride);
        }
        Optional<ConsistencyOracle> cached = oracleCache;
        if (cached == null) {
            cached = ServiceLoader.load(ConsistencyOracle.class).findFirst();
            oracleCache = cached;
        }
        return cached;
    }

    /** The backend identity (F11), or empty when no backend is present. */
    public static Optional<BackendInfo> backendInfo() {
        if (infoOverridden) {
            return Optional.ofNullable(infoOverride);
        }
        Optional<BackendInfo> cached = infoCache;
        if (cached == null) {
            cached = ServiceLoader.load(BackendInfo.class).findFirst();
            infoCache = cached;
        }
        return cached;
    }

    /** True iff a consistency-gate backend is present — the run can be {@link Verification#KERNEL_CONFIRMED}. */
    public static boolean hasBackend() {
        return consistencyOracle().isPresent();
    }

    /**
     * The verification mode this environment yields: {@link Verification#KERNEL_CONFIRMED} when a backend is
     * present (dmtool always), else {@link Verification#STRUCTURAL_ONLY}. The single truth every gate stamps
     * onto its result, so "kernel-green" can never be read without a backend behind it.
     */
    public static Verification verification() {
        return hasBackend() ? Verification.KERNEL_CONFIRMED : Verification.STRUCTURAL_ONLY;
    }

    /**
     * Inject backend ports for a test (either may be {@code null} to simulate its absence). Overrides win
     * over {@link ServiceLoader} discovery until {@link #reset}. Use to exercise both the confirmed and the
     * degraded posture without manipulating the classpath.
     */
    public static void override(ConsistencyOracle oracle, BackendInfo info) {
        oracleOverride = oracle;
        oracleOverridden = true;
        infoOverride = info;
        infoOverridden = true;
    }

    /** Clear any {@link #override} and the discovery cache, so the next lookup re-runs {@link ServiceLoader}. */
    public static void reset() {
        oracleOverride = null;
        oracleOverridden = false;
        oracleCache = null;
        infoOverride = null;
        infoOverridden = false;
        infoCache = null;
    }
}
