package io.github.mbackschat.a12.dm.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.Severity;

import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;

/**
 * F11 runtime kernel-version tolerance. The catalog carries a verified floor; the live kernel may be
 * newer. On skew we surface a single advisory INFO — never an error, never a block — and when the
 * versions match (or the runtime version is unreadable) we stay silent (bias to no false alarm).
 */
class KernelVersionTest {

    @Test
    void aNewerRunningKernelYieldsOneAdvisoryInfo() {
        KernelVersion v = new KernelVersion(Optional.of("31.0.0"), "30.8.1");

        assertTrue(v.isSkewed());
        Diagnostic d = v.advisory().orElseThrow();
        assertEquals(Severity.INFO, d.severity(), "skew is advisory, never an error — bias to acceptance");
        assertEquals(DiagnosticSource.ENVIRONMENT, d.source());
        assertEquals(Optional.of("RK_KERNEL_VERSION_SKEW"), d.code());
        assertTrue(d.summary().contains("31.0.0") && d.summary().contains("30.8.1"), d.summary());
    }

    @Test
    void aMatchingKernelIsSilent() {
        KernelVersion v = new KernelVersion(Optional.of("30.8.1"), "30.8.1");
        assertFalse(v.isSkewed());
        assertTrue(v.advisory().isEmpty());
    }

    @Test
    void anUnreadableRuntimeVersionIsSilent() {
        KernelVersion v = new KernelVersion(Optional.empty(), "30.8.1");
        assertFalse(v.isSkewed(), "unknown runtime version must not raise a false alarm");
        assertTrue(v.advisory().isEmpty());
    }

    @Test
    void theRealRuntimeMatchesTheCatalogFloor() {
        // Sanity: in this build the kernel on the classpath IS the verified floor, so no skew fires.
        KernelVersion v = KernelVersion.current(OperatorCatalog.load().verifiedAgainst());
        assertEquals(Optional.of("30.8.1"), v.runtime(), "Package.getImplementationVersion() reads the kernel jar");
        assertFalse(v.isSkewed());
    }
}
