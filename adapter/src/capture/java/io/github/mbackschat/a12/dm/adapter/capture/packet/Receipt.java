package io.github.mbackschat.a12.dm.adapter.capture.packet;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.schema.SchemaIds;

/**
 * {@code RECEIPT.json} ({@code capture-receipt-v1}, proposal §2): the CLOSED, sorted artifact inventory of
 * a packet or report directory. It inventories every file including {@code PACKET.json}, excludes ITSELF
 * (no self-hash cycle), and records relative path, role, byte count, and SHA-256. The receipt's own SHA-256
 * is printed for out-of-band handoff — integrity relative to the separately trusted handoff value, not
 * authentication of historical execution. Symlinks and unclassifiable files fail the build (closed
 * inventory).
 */
public record Receipt(List<Artifact> artifacts) {

    public static final String FILE_NAME = "RECEIPT.json";

    public Receipt {
        artifacts = List.copyOf(artifacts);
    }

    public record Artifact(String path, ArtifactRole role, long bytes, String sha256) {
    }

    /** Inventory {@code dir} (recursive, sorted by path in UTF-16 order, {@code RECEIPT.json} excluded). */
    public static Receipt over(Path dir) {
        List<Artifact> artifacts = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.sorted().toList()) {
                if (Files.isSymbolicLink(file)) {
                    throw new IllegalStateException("receipt refuses symlink: " + dir.relativize(file));
                }
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String relative = dir.relativize(file).toString().replace('\\', '/');
                if (relative.equals(FILE_NAME)) {
                    continue;
                }
                ArtifactRole role = ArtifactRole.classify(relative).orElseThrow(() -> new IllegalStateException(
                        "receipt refuses unclassifiable artifact \"" + relative + "\" — the inventory is closed"));
                byte[] bytes = Files.readAllBytes(file);
                artifacts.add(new Artifact(relative, role, bytes.length, Digests.sha256(bytes)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to inventory " + dir, e);
        }
        artifacts.sort(Comparator.comparing(Artifact::path));
        return new Receipt(artifacts);
    }

    /** Write this receipt as {@code dir/RECEIPT.json} (canonical bytes); returns the receipt's own SHA-256
     *  (the out-of-band handoff digest). */
    public String write(Path dir) {
        byte[] bytes = Json.canonicalBytes(toJson());
        try {
            Files.write(dir.resolve(FILE_NAME), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write " + FILE_NAME, e);
        }
        return Digests.sha256(bytes);
    }

    public Json toJson() {
        Json.Arr items = Json.Arr.of();
        for (Artifact a : artifacts) {
            items.add(Json.Obj.of()
                    .put("path", a.path())
                    .put("role", a.role().wire())
                    .put("bytes", a.bytes())
                    .put("sha256", a.sha256()));
        }
        return Json.Obj.of()
                .put("schema", SchemaIds.RECEIPT)
                .put("artifacts", items);
    }

    /** Parse a committed {@code RECEIPT.json} (strict, closed). */
    public static Receipt fromJson(Json root) {
        if (!(root instanceof Json.Obj obj) || obj.members().size() != 2
                || !(obj.members().get("schema") instanceof Json.Str schema)
                || !SchemaIds.RECEIPT.equals(schema.value())
                || !(obj.members().get("artifacts") instanceof Json.Arr items)) {
            throw new IllegalArgumentException("not a " + SchemaIds.RECEIPT + " receipt");
        }
        List<Artifact> artifacts = new ArrayList<>();
        for (Json item : items.items()) {
            if (!(item instanceof Json.Obj a) || a.members().size() != 4
                    || !(a.members().get("path") instanceof Json.Str path)
                    || !(a.members().get("role") instanceof Json.Str role)
                    || !(a.members().get("bytes") instanceof Json.Int bytes)
                    || !(a.members().get("sha256") instanceof Json.Str sha)) {
                throw new IllegalArgumentException("malformed receipt artifact entry");
            }
            artifacts.add(new Artifact(path.value(),
                    ArtifactRole.ofWire(role.value()).orElseThrow(() ->
                            new IllegalArgumentException("unknown artifact role: " + role.value())),
                    bytes.value(), sha.value()));
        }
        return new Receipt(artifacts);
    }
}
