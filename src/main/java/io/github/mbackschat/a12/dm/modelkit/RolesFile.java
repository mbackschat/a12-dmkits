package io.github.mbackschat.a12.dm.modelkit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * The workspace {@code roles.yaml} access-control config (FEATURE-GAPS R3) — the file a model's {@code roles}
 * header annotation binds to. A workspace declares its roles once, at {@code <workspace-root>/auth/roles.yaml}
 * (the SME convention; a sibling of {@code models/}), as a {@code roles:} list of {@code {name, description?,
 * accessRights[]}}. This is the typed, queryable read of that file — the basis for the undefined-role lint
 * ({@link WorkspaceRoles}): a model that gates to a role this file doesn't {@link #defines declare} is a
 * dangling cross-file reference even the SME doesn't catch.
 *
 * <p>Parsed as a Jackson tree (no reflective POJO binding — the same tree-walking style as {@link
 * ModelDocument}'s DM-JSON read), so it stays kernel-free and reflection-light. Tolerant of an absent
 * {@code description}/{@code accessRights} (both optional in real workspaces); a document that isn't a
 * {@code roles:} mapping — or a {@code roles:} entry with no {@code name:} (e.g. a bare-string list) — is
 * rejected rather than silently read as empty.
 */
public record RolesFile(List<Role> roles) {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    public RolesFile {
        roles = List.copyOf(roles);
    }

    /** One declared role: its {@code name} (the identifier the annotation binds to), description, and access rights. */
    public record Role(String name, String description, List<String> accessRights) {
        public Role {
            accessRights = List.copyOf(accessRights);
        }
    }

    /**
     * Parse {@code roles.yaml} content. Tolerates a missing {@code description} (reads as {@code ""}) or
     * {@code accessRights} (reads as empty); a roleless / empty document yields an empty role set.
     *
     * @throws IllegalArgumentException if the content isn't a YAML mapping (a roles file is a {@code roles:}
     *         document, not a scalar or a bare sequence), if a {@code roles:} entry has no {@code name:} (a
     *         bare-string list like {@code - admin} is malformed), or if it can't be parsed as YAML
     */
    public static RolesFile parse(String yaml) {
        JsonNode root;
        try {
            root = YAML.readTree(yaml);
        } catch (Exception e) {
            throw new IllegalArgumentException("not a parseable roles.yaml: " + e.getMessage(), e);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            return new RolesFile(List.of()); // an empty file declares no roles
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("roles.yaml must be a mapping with a `roles:` list, not "
                    + root.getNodeType().toString().toLowerCase());
        }
        List<Role> roles = new ArrayList<>();
        int index = 0;
        for (JsonNode r : root.path("roles")) {
            index++;
            String name = r.path("name").asText("");
            if (name.isBlank()) {
                // A nameless entry can't be referenced. Silently skipping it (the old behavior) meant a
                // bare-string list — `- admin` instead of the schema's `- name: admin`, a common YAML idiom —
                // parsed to an EMPTY role set, so the lint then checked nothing while reporting success
                // (probe 05, F4). Reject it loudly, like a non-mapping document: fail-fast, never a silent drop.
                throw new IllegalArgumentException("roles.yaml entry #" + index + " has no `name:` — each `roles:`"
                        + " entry must be a mapping like `- name: admin` (a bare string `- admin` is not a role)");
            }
            List<String> accessRights = new ArrayList<>();
            for (JsonNode a : r.path("accessRights")) {
                accessRights.add(a.asText());
            }
            roles.add(new Role(name, r.path("description").asText(""), List.copyOf(accessRights)));
        }
        return new RolesFile(List.copyOf(roles));
    }

    /** True iff a role named {@code roleName} is declared — the predicate the undefined-role lint is built on. */
    public boolean defines(String roleName) {
        return roles.stream().anyMatch(r -> r.name().equals(roleName));
    }

    /** The declared role of that name, if any. */
    public Optional<Role> role(String roleName) {
        return roles.stream().filter(r -> r.name().equals(roleName)).findFirst();
    }

    /** The declared role names, in file order (deduped). */
    public Set<String> names() {
        Set<String> out = new LinkedHashSet<>();
        roles.forEach(r -> out.add(r.name()));
        return out;
    }
}
