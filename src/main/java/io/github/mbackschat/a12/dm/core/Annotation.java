package io.github.mbackschat.a12.dm.core;

/**
 * An element-level annotation — a {@code name}/{@code value} pair the model author attaches to a field or
 * group ({@code annotations: [{name, value}]} in DM-JSON). The kernel-free peer of the kernel's
 * {@code com.mgmtp.a12.model.header.Annotation}; {@link ModelIndex#annotationsOf} reads it off the expanded
 * model so a consumer (rulekit's {@code dsl.Annotation}) never touches a kernel type.
 */
public record Annotation(String name, String value) {
}
