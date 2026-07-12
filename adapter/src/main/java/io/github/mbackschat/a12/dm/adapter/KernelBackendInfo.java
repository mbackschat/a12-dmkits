package io.github.mbackschat.a12.dm.adapter;

import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;

import io.github.mbackschat.a12.dm.core.spi.BackendInfo;

/**
 * The real-kernel {@link BackendInfo} (kernel-decoupling stage 3): reports the running kernel's identity for
 * the F11 skew advisory, reading the {@code Implementation-Version} off the kernel jar's manifest
 * (spike-confirmed in KERNEL-FINDINGS). Registered via {@code META-INF/services}; absent backend ⇒ no
 * {@code BackendInfo} ⇒ no skew advisory.
 */
public final class KernelBackendInfo implements BackendInfo {

    @Override
    public String version() {
        Package pkg = IDocumentModel.class.getPackage();
        return pkg == null ? null : pkg.getImplementationVersion();
    }

    @Override
    public String name() {
        return "a12-kernel";
    }
}
