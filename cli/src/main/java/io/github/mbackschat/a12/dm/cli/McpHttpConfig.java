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
 */
record McpHttpConfig(String host, int port, Set<String> allowedOrigins, int maxRequestBytes) {

    /** Loopback: a hosted deployment overrides it deliberately, so the unsafe default is never the quiet one. */
    static final String DEFAULT_HOST = "127.0.0.1";

    /** 8 MiB — far above any real model, far below what a single request should be able to allocate. */
    static final int DEFAULT_MAX_REQUEST_BYTES = 8 * 1024 * 1024;

    McpHttpConfig {
        allowedOrigins = Set.copyOf(allowedOrigins);
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private String host = DEFAULT_HOST;
        private int port;
        private Set<String> allowedOrigins = Set.of();
        private int maxRequestBytes = DEFAULT_MAX_REQUEST_BYTES;

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

        McpHttpConfig build() {
            return new McpHttpConfig(host, port, allowedOrigins, maxRequestBytes);
        }
    }
}
