package io.github.mbackschat.a12.dm.adapter;

import java.util.List;

import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.Notification;
import io.github.mbackschat.a12.dm.core.spi.ConsistencyOracle;

/**
 * The real-kernel {@link ConsistencyOracle} (kernel-decoupling stage 3): the R3 seam's one implementation.
 * Deserialize → expand(resolver) → the kernel's {@code checkConsistency}, returning every notification
 * mapped to our own {@link Notification} vocabulary. Registered via {@code META-INF/services} so a consumer
 * with this backend jar on the classpath (dmtool, JVM and native) gets the confirmed gate, and one without
 * it (a Maven-Central kit consumer) degrades to structural-only.
 *
 * <p>This mirrors exactly what the kits used to do inline (the {@code deserialize → expand → checkConsistency}
 * of the old {@code ModelLoader}/{@code RuleValidator}), so routing the gate through the port is
 * behaviour-neutral for dmtool.
 */
public final class KernelConsistencyOracle implements ConsistencyOracle {

    @Override
    public List<Notification> checkConsistency(String dmJson, ModelResolver resolver) {
        return KernelModelPreparation.expand(dmJson, resolver).notifications();
    }
}
