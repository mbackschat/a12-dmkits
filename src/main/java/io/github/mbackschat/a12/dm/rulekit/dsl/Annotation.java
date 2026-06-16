package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * One element-level name/value annotation pair (the kernel's {@code annotations}) — the read currency of
 * the navigation surface, shared by {@link FieldInfo#annotations()} and {@link GroupInfo#annotations()}.
 * The write peer is {@code EditSession.setAnnotation} / {@code GroupEditor.setAnnotation}.
 */
public record Annotation(String name, String value) {}
