package io.github.mbackschat.a12.dm.rulekit.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * The read-back port's resolution, which has THREE outcomes: none registered, exactly one, or several with no
 * basis for choosing. Classification is a total function over what discovery found, which is what lets every
 * outcome — including the ambiguous one — be asserted as a value here rather than through a thrown exception
 * and a classpath that would have to be rebuilt to produce it.
 *
 * <p>Ambiguity is the outcome worth holding: {@code ServiceLoader} order is classpath order, so picking one
 * silently binds a caller to whichever jar came first, and the providers do not agree — the kernel-backed one
 * converts the whole grammar where a partial one answers {@code Opaque}, which reads like a model problem
 * rather than a wiring one.
 */
class ReadBackendResolutionTest {

    /** A stand-in provider: the classification never calls it, and identity is what the assertions compare. */
    private record StubBackend(String name) implements ConditionReadBack {

        @Override
        public Map<String, ReadResult> readAll(String modelJson, ModelResolver resolver) {
            throw new UnsupportedOperationException("classification must never invoke a provider");
        }
    }

    private static final ConditionReadBack FIRST = new StubBackend("first");
    private static final ConditionReadBack SECOND = new StubBackend("second");

    @AfterEach
    void clearAnyInjection() {
        ReadBackends.reset();
    }

    @Test
    void nothingRegisteredIsNone() {
        assertInstanceOf(ReadBackendResolution.None.class, ReadBackends.classify(List.of()));
    }

    @Test
    void oneProviderIsSoleAndCarriesIt() {
        var sole = assertInstanceOf(ReadBackendResolution.Sole.class, ReadBackends.classify(List.of(FIRST)));
        assertSame(FIRST, sole.provider());
    }

    @Test
    void twoProvidersAreSeveralRatherThanAnArbitraryPick() {
        var several = assertInstanceOf(ReadBackendResolution.Several.class,
                ReadBackends.classify(List.of(FIRST, SECOND)));
        assertEquals(List.of(FIRST, SECOND), several.providers());
    }

    /** The classification owns its list: a caller mutating what it passed cannot rewrite the outcome. */
    @Test
    void severalCopiesTheProvidersItWasGiven() {
        List<ConditionReadBack> mutable = new ArrayList<>(List.of(FIRST, SECOND));
        var several = (ReadBackendResolution.Several) ReadBackends.classify(mutable);
        mutable.clear();
        assertEquals(List.of(FIRST, SECOND), several.providers());
    }

    @Test
    void publicResolutionValuesCannotContradictTheirNamedState() {
        assertThrows(NullPointerException.class, () -> new ReadBackendResolution.Sole(null));
        assertThrows(IllegalArgumentException.class,
                () -> new ReadBackendResolution.Several(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ReadBackendResolution.Several(List.of(FIRST)));
        assertThrows(NullPointerException.class,
                () -> new ReadBackendResolution.Several(null));
    }

    @Test
    void requireServesTheSoleProvider() {
        assertSame(FIRST, ReadBackends.requireFrom(new ReadBackendResolution.Sole(FIRST)));
    }

    @Test
    void requireRefusesWhenNoneIsRegistered() {
        BackendRequiredException e = assertThrows(BackendRequiredException.class,
                () -> ReadBackends.requireFrom(new ReadBackendResolution.None()));
        assertTrue(e.getMessage().contains("none is on the classpath"), e.getMessage());
    }

    /**
     * The does-fire case for the ambiguity gate, and the reason {@code current()} is total: the state can be
     * injected, so the refusal is provable without a second provider jar on this lane's classpath.
     */
    @Test
    void requireRefusesWhenSeveralAreRegistered() {
        AmbiguousBackendException e = assertThrows(AmbiguousBackendException.class,
                () -> ReadBackends.requireFrom(new ReadBackendResolution.Several(List.of(FIRST, SECOND))));
        assertTrue(e.getMessage().contains("cannot choose between them"), e.getMessage());
        assertTrue(e.getMessage().contains(StubBackend.class.getName()), e.getMessage());
        assertTrue(e.getMessage().contains("ReadBackends.prefer"), e.getMessage());
    }

    /**
     * {@code prefer} names one of the REGISTERED providers, so it selects from what discovery instantiated
     * rather than loading a class by name — nothing here is reflective, which is what keeps it usable from a
     * native image. This lane carries exactly one provider, so the one it finds is the one it must return.
     */
    @Test
    void preferSelectsTheNamedProviderAndBindsIt() {
        ConditionReadBack onlyOne = ReadBackends.require();
        // Start from an injected ABSENCE, so a prefer that selects without binding leaves None behind and
        // require() throws. Without this the lane's single provider answers either way and the test cannot
        // tell a working bind from no bind at all.
        ReadBackends.override(null);
        ReadBackends.prefer(onlyOne.getClass());
        assertSame(onlyOne, ReadBackends.require(), "prefer must BIND the instance discovery already built");
    }

    /** Naming a provider that is not registered is a wiring error, and the message says what IS registered. */
    @Test
    void preferRefusesATypeThatIsNotRegistered() {
        String registered = ReadBackends.require().getClass().getName();
        BackendRequiredException e = assertThrows(BackendRequiredException.class,
                () -> ReadBackends.prefer(StubBackend.class));
        assertTrue(e.getMessage().contains(StubBackend.class.getName()), e.getMessage());
        // What IS registered, not just the type that was asked for — the javadoc promises the former, and
        // asserting the latter passes even when the message names nothing at all.
        assertTrue(e.getMessage().contains(registered),
                () -> "the message must name the registered provider, got: " + e.getMessage());
    }

    /**
     * {@code current()} is documented total, and the way to break that is concurrency rather than input: an
     * injected choice held as a value beside an "is set" flag cannot be written atomically, so a reader
     * interleaved with {@code reset()} could see the flag set and the value already cleared. This hammers that
     * interleaving and asserts the property the javadoc claims — never null, never a throw.
     */
    @Test
    void currentStaysTotalWhileAnotherThreadResets() throws InterruptedException {
        int iterations = 200_000;
        var failures = new java.util.concurrent.atomic.AtomicReference<String>();
        var start = new java.util.concurrent.CountDownLatch(1);
        Thread writer = new Thread(() -> {
            await(start);
            for (int i = 0; i < iterations; i++) {
                ReadBackends.override(FIRST);
                ReadBackends.reset();
            }
        });
        Thread reader = new Thread(() -> {
            await(start);
            for (int i = 0; i < iterations; i++) {
                try {
                    if (ReadBackends.current() == null) {
                        failures.compareAndSet(null, "current() returned null");
                    }
                } catch (RuntimeException e) {
                    failures.compareAndSet(null, "current() threw " + e);
                }
            }
        });
        writer.start();
        reader.start();
        start.countDown();
        writer.join();
        reader.join();
        assertNull(failures.get(), failures.get());
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A handler written against the supertype keeps catching the ambiguous case. */
    @Test
    void ambiguityIsCaughtByABackendRequiredHandler() {
        assertThrows(BackendRequiredException.class,
                () -> ReadBackends.requireFrom(new ReadBackendResolution.Several(List.of(FIRST, SECOND))));
    }
}
