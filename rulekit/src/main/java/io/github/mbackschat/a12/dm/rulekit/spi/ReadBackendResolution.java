package io.github.mbackschat.a12.dm.rulekit.spi;

import java.util.List;
import java.util.Objects;

/**
 * What {@link java.util.ServiceLoader} discovery found for the read-back port: nothing, exactly one provider,
 * or several with no basis for choosing between them.
 *
 * <p><b>Why not {@link java.util.Optional}.</b> It holds two of those states, so a resolver returning one has
 * to express "several" by some other route, and the only route left is a throw. That makes an ambiguous
 * classpath fail louder than an empty one, which is backwards — an empty classpath is a <em>supported</em>
 * state the kits are designed for (KERNEL-DECOUPLING-PROPOSAL §2c), while several providers is a packaging
 * error nobody chose. Holding all three states means no entry point has to invent that asymmetry.
 *
 * <p>Vocabulary only, no policy: {@link ReadBackends#current()} hands all three back, and
 * {@link ReadBackends#require()} decides which of them it can serve.
 */
public sealed interface ReadBackendResolution {

    /** No provider is registered — read-back is unavailable, and that is a supported posture. */
    record None() implements ReadBackendResolution { }

    /** Exactly one provider: the only state a caller can act on without being told which to use. */
    record Sole(ConditionReadBack provider) implements ReadBackendResolution {

        public Sole {
            Objects.requireNonNull(provider, "provider");
        }
    }

    /** Several providers. Which one is wanted is the caller's to say — see {@link ReadBackends#prefer}. */
    record Several(List<ConditionReadBack> providers) implements ReadBackendResolution {

        public Several {
            Objects.requireNonNull(providers, "providers");
            providers = List.copyOf(providers);
            if (providers.size() < 2) {
                throw new IllegalArgumentException("Several needs at least two providers");
            }
        }
    }
}
