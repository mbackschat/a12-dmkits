package io.github.mbackschat.a12.dm.adapter;

import java.util.Map;

import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.spi.ConditionReadBack;

/**
 * The real-kernel {@link ConditionReadBack} (kernel-decoupling stage 3): the authoritative, canonical
 * read-back — it delegates to {@link KernelAdapter}, which parses each stored condition through the kernel
 * and maps it to rulekit's typed AST (or {@code Opaque}). Registered via {@code META-INF/services} so a
 * published consumer (schemakit's Tier-A projection) reads conditions back through the port without a
 * kernel type or a hard {@code :adapter} dependency.
 */
public final class KernelConditionReadBack implements ConditionReadBack {

    @Override
    public Map<String, ReadResult> readAll(String modelJson, ModelResolver resolver) {
        return KernelAdapter.ofJson(modelJson, resolver).readAll();
    }
}
