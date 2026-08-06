package io.github.mbackschat.a12.dm.cli;

import java.util.List;
import java.util.Set;

/**
 * How the HTTP binding is exposed (MCP-SPEC "hosted"). A builder rather than a positional constructor: the
 * same-typed numeric fields next to each other are exactly the swap-prone shape the house rule names.
 *
 * @param host            the interface to bind — loopback by default, because the specification SHOULDs
 *                        binding only localhost when the server runs beside its caller
 * @param port            the listening port; {@code 0} asks the OS for an ephemeral one
 * @param allowedOrigins  browser origins permitted to call. A request carrying <b>any other</b> {@code Origin}
 *                        is refused 403 — the specification's required DNS-rebinding defense. Empty (the
 *                        default) means no browser origin is allowed; a non-browser client sends no
 *                        {@code Origin} and is unaffected
 * @param maxRequestBytes the request-body ceiling, refused with a corrective rather than read into memory
 * @param workers         how many calls may run at once. Deliberately small: memory scales with concurrency,
 *                        and evaluation has been measured at 519 MiB on a stress fixture against ~43 MiB for
 *                        authoring, so a host sized for authoring must not admit four evaluations at once
 * @param queueDepth      how many may wait. Bounded on purpose — this revision removed stream resumability,
 *                        so a timed-out request is re-issued as a NEW one and an unbounded queue turns a slow
 *                        patch into a retry storm. Past the bound a caller is told to retry, immediately
 */
record McpHttpConfig(String host, int port, Set<String> allowedOrigins, int maxRequestBytes, int workers,
                     int queueDepth) {

    /** Loopback: a hosted deployment overrides it deliberately, so the unsafe default is never the quiet one. */
    static final String DEFAULT_HOST = "127.0.0.1";

    /** 8 MiB — far above any real model, far below what a single request should be able to allocate. */
    static final int DEFAULT_MAX_REQUEST_BYTES = 8 * 1024 * 1024;

    /** Capped at 4 however many cores are visible: the binding constraint is heap per in-flight call. */
    static final int DEFAULT_WORKERS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));

    /** Shallow on purpose: a deep queue only converts a busy server into a slow one, then into a retry storm. */
    static final int DEFAULT_QUEUE_DEPTH = 16;

    McpHttpConfig {
        allowedOrigins = Set.copyOf(allowedOrigins);
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be at least 1");
        }
        if (queueDepth < 1) {
            throw new IllegalArgumentException("queueDepth must be at least 1");
        }
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private String host = DEFAULT_HOST;
        private int port;
        private Set<String> allowedOrigins = Set.of();
        private int maxRequestBytes = DEFAULT_MAX_REQUEST_BYTES;
        private int workers = DEFAULT_WORKERS;
        private int queueDepth = DEFAULT_QUEUE_DEPTH;

        private Builder() {
        }

        /** A null or blank value leaves the default in place — the house convention for an unset aspect. */
        Builder host(String value) {
            if (value != null && !value.isBlank()) {
                host = value;
            }
            return this;
        }

        Builder port(int value) {
            port = value;
            return this;
        }

        Builder allowedOrigins(List<String> values) {
            if (values != null && !values.isEmpty()) {
                allowedOrigins = Set.copyOf(values);
            }
            return this;
        }

        Builder maxRequestBytes(int value) {
            if (value > 0) {
                maxRequestBytes = value;
            }
            return this;
        }

        Builder workers(int value) {
            if (value > 0) {
                workers = value;
            }
            return this;
        }

        Builder queueDepth(int value) {
            if (value > 0) {
                queueDepth = value;
            }
            return this;
        }

        McpHttpConfig build() {
            return new McpHttpConfig(host, port, allowedOrigins, maxRequestBytes, workers, queueDepth);
        }
    }
}
