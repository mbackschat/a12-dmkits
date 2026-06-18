package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The workspace {@code users.yaml} reader (FEATURE-GAPS R4) — parse the seed users + their role memberships,
 * the membership edge of the RBAC triangle ({@code authorities[]} are role names, the other reference into
 * {@code roles.yaml}). Mirrors {@link RolesFile}: a pure kernel-free Jackson-tree read, tolerant of optional
 * fields. Supports both real conventions — a single {@code users.yaml} with a {@code users:} list (the SME),
 * and one flat per-user file (the project template's {@code users/} directory). Own e-commerce domain.
 */
class UsersFileTest {

    private static final String STOREFRONT_USERS = """
            users:
              - username: alice
                password: secret
                authorities: [shopper]
              - username: bob
                authorities: [merchant, shopper]
              - username: carol
                authorities: []
            """;

    @Test
    void parsesTheUsersListWithUsernamesAndAuthorities() {
        UsersFile users = UsersFile.parse(STOREFRONT_USERS);

        assertEquals(List.of("alice", "bob", "carol"), users.users().stream().map(UsersFile.User::username).toList());
        assertEquals(List.of("merchant", "shopper"), users.user("bob").orElseThrow().authorities());
        assertEquals(List.of(), users.user("carol").orElseThrow().authorities(), "missing/empty authorities → empty");
    }

    @Test
    void authoritiesIsTheUnionAndHoldsAsksWhoHasARole() {
        UsersFile users = UsersFile.parse(STOREFRONT_USERS);
        assertEquals(List.of("shopper", "merchant"), List.copyOf(users.authorities()), "union, in first-seen order");
        assertTrue(users.anyoneHolds("merchant"), "bob holds merchant");
        assertFalse(users.anyoneHolds("auditor"), "nobody holds auditor (the reachability/orphan basis)");
    }

    @Test
    void parsesOneFlatPerUserFileTheDirectoryConventionUses() {
        // the project-template `auth/users/admin.yaml` shape — a flat user, no `users:` wrapper
        UsersFile.User admin = UsersFile.parseUser("""
                username: admin
                password: a12
                authorities:
                  - systemAdmin
                """);
        assertEquals("admin", admin.username());
        assertEquals(List.of("systemAdmin"), admin.authorities());
    }

    @Test
    void aNonMappingDocumentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> UsersFile.parse("- just\n- a\n- list\n"));
        assertTrue(UsersFile.parse("{}").users().isEmpty(), "a document without a `users:` key has no users");
    }
}
