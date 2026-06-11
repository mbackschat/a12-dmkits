package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.model.notification.Severity;

/**
 * The outcome of a ground-truth check: {@code ok} iff the kernel reported no {@code ERROR}-severity
 * diagnostics. Lower-severity findings (warnings/info) are retained in {@link #diagnostics()}.
 */
public record ValidationResult(boolean ok, List<Diagnostic> diagnostics) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ValidationResult {
        diagnostics = List.copyOf(diagnostics);
    }

    public static ValidationResult of(List<Diagnostic> diagnostics) {
        boolean ok = diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR);
        return new ValidationResult(ok, diagnostics);
    }

    /**
     * The diagnostics as an agent-parseable JSON array (files-as-API, §14) — one object per
     * diagnostic with {@code severity·source·code·summary·where·fix·explain}, empty optionals
     * omitted so the shape stays terse.
     */
    public String toJson() {
        ArrayNode array = MAPPER.createArrayNode();
        for (Diagnostic d : diagnostics) {
            ObjectNode node = array.addObject();
            node.put("severity", d.severity().name());
            node.put("source", d.source().name());
            d.code().ifPresent(c -> node.put("code", c));
            node.put("summary", d.summary());
            ObjectNode where = node.putObject("where");
            if (d.where().rule() != null) {
                where.put("rule", d.where().rule());
            }
            d.where().path().ifPresent(p -> where.put("path", p));
            d.where().arg().ifPresent(a -> where.put("arg", a));
            d.where().operator().ifPresent(op -> where.put("operator", op));
            d.where().span().ifPresent(s -> where.putArray("span").add(s.start()).add(s.end()));
            d.fix().ifPresent(f -> node.put("fix", f));
            d.explain().ifPresent(e -> node.put("explain", e));
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(array);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to render diagnostics as JSON", e);
        }
    }
}
