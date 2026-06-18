package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The workspace {@code roles.yaml} reader (FEATURE-GAPS R3) — parse the access-control config a model's
 * {@code roles} header annotation binds to: a {@code roles:} list of {@code {name, description?,
 * accessRights[]}}. A pure, kernel-free Jackson-tree read (the same style as {@link ModelDocument}), tolerant
 * of an absent {@code description}/{@code accessRights} (both optional in the wild) but rejecting a file that
 * isn't a {@code roles:} document. The verified schema mirrors the A12 SME workspaces (own e-commerce domain
 * per the license-safe rule — the kernel set uses {@code modeler}/{@code tester}/{@code reviewer}).
 */
class RolesFileTest {

    private static final String STOREFRONT_ROLES = """
            roles:
              - name: shopper
                description: a customer placing orders
                accessRights:
                  - DOCUMENT_CREATE
                  - DOCUMENT_UPDATE
                  - QUERY
              - name: merchant
                description: a seller managing the catalog
                accessRights:
                  - DOCUMENT_CREATE
                  - DOCUMENT_UPDATE
                  - DOCUMENT_DELETE
                  - MODEL_MANAGE
              - name: auditor
                accessRights:
                  - QUERY
            """;

    @Test
    void parsesTheRoleListWithNamesDescriptionsAndAccessRights() {
        RolesFile roles = RolesFile.parse(STOREFRONT_ROLES);

        assertEquals(List.of("shopper", "merchant", "auditor"), List.copyOf(roles.names()),
                "names are read in declared order");
        RolesFile.Role merchant = roles.role("merchant").orElseThrow();
        assertEquals("a seller managing the catalog", merchant.description());
        assertEquals(List.of("DOCUMENT_CREATE", "DOCUMENT_UPDATE", "DOCUMENT_DELETE", "MODEL_MANAGE"),
                merchant.accessRights());
    }

    @Test
    void definesAsksWhetherARoleIsDeclared() {
        RolesFile roles = RolesFile.parse(STOREFRONT_ROLES);
        assertTrue(roles.defines("shopper"));
        assertTrue(roles.defines("auditor"));
        assertFalse(roles.defines("admin"), "an undeclared role is not defined (the undefined-role lint's basis)");
    }

    @Test
    void toleratesAMissingDescriptionAndMissingAccessRights() {
        RolesFile roles = RolesFile.parse(STOREFRONT_ROLES);
        RolesFile.Role auditor = roles.role("auditor").orElseThrow();
        assertEquals("", auditor.description(), "an absent description reads as empty, not null");
        assertEquals(List.of("QUERY"), auditor.accessRights());

        // a role with neither description nor accessRights still parses
        RolesFile bare = RolesFile.parse("roles:\n  - name: guest\n");
        assertEquals(List.of("QUERY"), roles.role("auditor").orElseThrow().accessRights());
        assertTrue(bare.defines("guest"));
        assertEquals(List.of(), bare.role("guest").orElseThrow().accessRights());
    }

    @Test
    void anEmptyOrRolelessDocumentIsAnEmptyRoleSet() {
        assertTrue(RolesFile.parse("roles: []").names().isEmpty());
        assertTrue(RolesFile.parse("{}").names().isEmpty(), "a document without a `roles:` key defines no roles");
    }

    @Test
    void aNonMappingDocumentIsRejected() {
        // a YAML scalar / sequence at the root is not a roles file — fail fast, don't silently read nothing
        assertThrows(IllegalArgumentException.class, () -> RolesFile.parse("- just\n- a\n- list\n"));
        assertThrows(IllegalArgumentException.class, () -> RolesFile.parse(": : not yaml : :"));
    }
}
