package io.github.mbackschat.a12.dm.modelkit;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.mbackschat.a12.dm.core.ConfigFlag;

/**
 * A model POJO's primitive {@code boolean} member, read as A12's reader binds it ({@link ConfigFlag}; KERNEL-FINDINGS
 * KF569) rather than by {@code JsonNode.asBoolean}, which misses the padded and capitalized spellings A12 takes as
 * set — {@code " TRUE"} read as the default on every field-type member until IF404.
 *
 * <p>Not for {@code excludeRules}/{@code excludeComputations}: A12 reads those through {@code JsonNode.booleanValue()}
 * instead (KF3), which {@code AuthoredStateReader} mirrors.
 */
final class A12Booleans {

    private A12Booleans() {}

    static ConfigFlag of(JsonNode member) {
        return ConfigFlag.ofParsedMember(member.isMissingNode() ? null : member.toString(), member.isFloatingPointNumber());
    }

    /** Whether the member is set; an unreadable spelling is not, since A12 never loads that model to say. */
    static boolean isSet(JsonNode member) {
        return of(member) == ConfigFlag.ON;
    }
}
