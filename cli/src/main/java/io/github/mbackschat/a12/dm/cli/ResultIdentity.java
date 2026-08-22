package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParseResult;

/**
 * The indivisible command identity carried by a {@link ResultEnvelope}.
 *
 * <p>Ordinary two-token verbs obtain it from their live picocli leaf through
 * {@link BaseCommand#resultIdentity()}; exceptional one-token/multi-mode routes use the named constants below.
 * There is deliberately no raw {@code (String target, String op)} factory: moving those two adjacent strings into
 * another public value-object constructor would preserve the silent-swap defect this type closes.
 */
public final class ResultIdentity {

    static final ResultIdentity WHERE_USED_READ = new ResultIdentity("where-used", "read");
    static final ResultIdentity META_READ = new ResultIdentity("meta", "read");
    static final ResultIdentity META_MODIFY = new ResultIdentity("meta", "modify");
    static final ResultIdentity PATTERNS_READ = new ResultIdentity("patterns", "read");
    static final ResultIdentity PATTERNS_SCAFFOLD = new ResultIdentity("patterns", "scaffold");
    static final ResultIdentity APPLY_RUN = new ResultIdentity("apply", "run");
    static final ResultIdentity EXPORT_RENDER = new ResultIdentity("export", "render");
    static final ResultIdentity BATCH_RUN = new ResultIdentity("batch", "run");
    static final ResultIdentity MODEL_VALIDATE = new ResultIdentity("model", "validate");
    static final ResultIdentity UNPARSED_FALLBACK = new ResultIdentity("model", "");

    private final String target;
    private final String op;

    private ResultIdentity(String target, String op) {
        this.target = target;
        this.op = op;
    }

    /** Derive an ordinary two-token verb identity from its live command-tree leaf. */
    static ResultIdentity from(CommandSpec leaf) {
        List<String> path = new ArrayList<>();
        for (CommandSpec current = leaf; current != null && current.parent() != null; current = current.parent()) {
            path.add(current.name());
        }
        Collections.reverse(path);
        if (path.size() != 2) {
            throw new IllegalArgumentException("a result-bearing command leaf must have target/op depth 2; `"
                    + leaf.qualifiedName() + "` has depth " + path.size()
                    + " and must use a named exceptional identity");
        }
        return new ResultIdentity(path.get(0), path.get(1));
    }

    /**
     * Derive the identity available at picocli's exception seam, preserving its established one-token
     * {@code op:""} behaviour. The caller uses {@link #UNPARSED_FALLBACK} when no parse result exists.
     */
    static ResultIdentity from(ParseResult parsed) {
        List<String> path = new ArrayList<>();
        for (ParseResult current = parsed.subcommand(); current != null; current = current.subcommand()) {
            path.add(current.commandSpec().name());
        }
        return switch (path.size()) {
            case 0 -> UNPARSED_FALLBACK;
            case 1 -> new ResultIdentity(path.get(0), "");
            default -> new ResultIdentity(path.get(0), path.get(1));
        };
    }

    /**
     * A compiler-closed typed source for the apply command's validated and rejected op selectors. Package-private
     * because only that command needs it; the permits list is the denominator, so a new source cannot appear
     * without changing this declaration.
     */
    sealed interface Source permits ApplyCommand.Op, ApplyCommand.ParsedSelector {
        String resultTarget();
        String resultOp();
    }

    static ResultIdentity from(Source source) {
        return new ResultIdentity(source.resultTarget(), source.resultOp());
    }

    public String target() {
        return target;
    }

    public String op() {
        return op;
    }

    @Override
    public String toString() {
        return op.isEmpty() ? target : target + " " + op;
    }
}
