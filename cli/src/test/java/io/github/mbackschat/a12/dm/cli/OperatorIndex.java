package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Renders the operator → kernel-doc-entry index from the live catalog and the two kernel docs.
 *
 * <p>The whole page is derived; nothing here is curated, which is the point — see
 * {@link OperatorIndexGuardTest} for why a hand-maintained index would rot faster than the thing it indexes.
 *
 * @param entries every indexable entry, in document order
 */
record OperatorIndex(List<OperatorIndex.Entry> entries) {

    /**
     * One addressable entry in a kernel doc: a KERNEL-FINDINGS record, or a KERNEL-SEMANTICS section.
     *
     * <p>{@code text} is the WHOLE entry, its heading included. Matching used to start after the heading, and
     * that silently cost 21 (operator, entry) pairs on 2026-09-14 — {@code Having} alone lost six records
     * whose heading names it and whose body writes only the construct behind it. A heading is part of the
     * entry a reader lands on, so a code span in it is a citation like any other.
     */
    record Entry(String doc, String address, String label, String text) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** An operator name as this estate writes it: inside a code span. Prose "True"/"Sum" is not a citation. */
    private static final Pattern CODE_SPAN = Pattern.compile("`+([^`]*)`+");

    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    /** A KERNEL-FINDINGS record heading, whose trailing {@code <a id="kfN">} is its permanent address. */
    private static final Pattern KF_HEADING = Pattern.compile("(?m)^#{2,3} .*$");

    private static final Pattern KF_ANCHOR = Pattern.compile("<a id=\"(kf\\d+)\">");

    /** A canonical KERNEL-SEMANTICS section: {@code ## 7. Strings and patterns}. */
    private static final Pattern KS_HEADING = Pattern.compile("(?m)^## (\\d+)\\. +(.*)$");

    static OperatorIndex from(Path docs) throws IOException {
        List<Entry> entries = new ArrayList<>();
        entries.addAll(findingsEntries(Files.readString(docs.resolve("KERNEL-FINDINGS.md"))));
        entries.addAll(semanticsEntries(Files.readString(docs.resolve("KERNEL-SEMANTICS.md"))));
        return new OperatorIndex(entries);
    }

    /**
     * Every catalog operator id, sorted — the index's row keys.
     *
     * <p><b>The id is the only spelling matched, and both halves of that are measured.</b> An operator these
     * docs write only as a <i>symbol</i> cannot be indexed at all: {@code GreaterThanOrEqual} is {@code >=} and
     * {@code LessThanOrEqual} is {@code <=}, and a symbol is indistinguishable from ordinary comparison syntax
     * inside a code span — {@code >} appears in <b>233</b> spans across the two docs and {@code <} in
     * <b>164</b> — overwhelmingly comparison syntax, blockquote markers and regex lookbehinds inside sample
     * code; only ONE is an {@code IF<n>}-style placeholder, so an earlier version of this note named the
     * wrong majority — while even
     * the tighter {@code >=} (42 spans) catches Java source like {@code count >= n}. Those two are reported as
     * unindexed rather than matched. <b>Localized keywords are deliberately not matched either</b>: measured
     * 2026-08-16, <i>no</i> operator in either doc is reachable only through one, so a keyword arm adds no ROW.
     * It would add <b>entries</b> to rows that already exist — 8 of them name an operator only in German
     * ({@code FeldAngegeben}, {@code Gueltig}, {@code DatumBereich}) — at the cost of new false positives, since
     * {@code Gueltig}/{@code Ungueltig} are shared by two operator pairs. Row coverage is what this page is for. English is the canonical doc language, which is why. Re-check that before
     * adding it back.
     */
    static List<String> catalogOperatorIds(Path catalog) throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        collectIds(MAPPER.readTree(catalog.toFile()), ids);
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(String::compareTo);
        return sorted;
    }

    private static void collectIds(JsonNode node, Set<String> into) {
        if (node.isObject()) {
            JsonNode id = node.get("id");
            if (id != null && id.isTextual()) {
                into.add(id.asText());
            }
            node.fields().forEachRemaining(field -> collectIds(field.getValue(), into));
        } else if (node.isArray()) {
            node.forEach(child -> collectIds(child, into));
        }
    }

    /** The entries that name {@code operator} inside a code span, in document order. */
    List<Entry> mentioning(String operator) {
        return entries.stream().filter(entry -> namesInCode(entry.text(), operator)).toList();
    }

    private static boolean namesInCode(String markdown, String operator) {
        Matcher span = CODE_SPAN.matcher(markdown);
        while (span.find()) {
            Matcher word = WORD.matcher(span.group(1));
            while (word.find()) {
                if (word.group().equals(operator)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Package-private so the heading rule can be locked on fabricated markdown rather than on the real docs. */
    static List<Entry> findingsEntries(String markdown) {
        List<Entry> entries = new ArrayList<>();
        Matcher heading = KF_HEADING.matcher(markdown);
        List<int[]> spans = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (heading.find()) {
            spans.add(new int[] {heading.start(), heading.end()});
            titles.add(heading.group());
        }
        for (int i = 0; i < spans.size(); i++) {
            String title = titles.get(i);
            Matcher anchor = KF_ANCHOR.matcher(title);
            if (!anchor.find()) {
                continue;   // every record carries one; DocAnchorResolveGuardTest is what enforces that
            }
            int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : markdown.length();
            entries.add(new Entry("KERNEL-FINDINGS.md", anchor.group(1), label(title),
                    markdown.substring(spans.get(i)[0], end)));
        }
        return entries;
    }

    private static List<Entry> semanticsEntries(String markdown) {
        List<Entry> entries = new ArrayList<>();
        Matcher heading = KS_HEADING.matcher(markdown);
        List<int[]> spans = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (heading.find()) {
            spans.add(new int[] {heading.start(), heading.end()});
            numbers.add(heading.group(1));
            titles.add(heading.group(2));
        }
        for (int i = 0; i < spans.size(); i++) {
            int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : markdown.length();
            entries.add(new Entry("KERNEL-SEMANTICS.md", slug(numbers.get(i) + ". " + titles.get(i)),
                    "§" + numbers.get(i), markdown.substring(spans.get(i)[0], end)));
        }
        return entries;
    }

    /** A KF record's short label: its §-tag when it carries one, else its id. */
    private static String label(String heading) {
        Matcher tag = Pattern.compile("^#{2,3} §(\\d+)").matcher(heading);
        Matcher anchor = KF_ANCHOR.matcher(heading);
        String id = anchor.find() ? anchor.group(1).toUpperCase() : "?";
        return tag.find() ? id + " (§" + tag.group(1) + ")" : id;
    }

    /** GitHub's heading-slug rule, enough of it — the same one DocAnchorResolveGuardTest applies. */
    private static String slug(String headingText) {
        String text = headingText.replaceAll("<[^>]*>", "").strip();
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128) {
                out.append(c);
            } else if (c == ' ') {
                out.append('-');
            } else if (c == '-' || c == '_') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The whole page, byte-for-byte as it must sit on disk. */
    String render(List<String> operatorIds) {
        Map<String, List<Entry>> byOperator = new TreeMap<>();
        for (String operator : operatorIds) {
            List<Entry> found = mentioning(operator);
            if (!found.isEmpty()) {
                byOperator.put(operator, found);
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("""
                # Operator index — which kernel-doc entry discusses which operator

                **Generated. Do not edit by hand** — regenerate with `./build.sh :cli:dumpOperatorIndex`, which is
                what `OperatorIndexGuardTest` compares against. It is derived from the operator catalog and the two
                kernel docs, so it cannot drift from them.

                **Why it exists.** The retrieval manual's first step is a grep over headings, and it does not work
                for operators here: the headings in these docs are written as *claims*, not *subjects*, so most
                operators appear only in bodies. An entry is listed below when it names the operator **inside a code
                span**, anywhere in the entry INCLUDING its heading, which is how this estate writes an operator
                name — prose that happens to contain the English
                words "sum" or "true" is not a citation and is not indexed. An operator these docs write only as a
                symbol (`>=`, `<=`) is listed as unindexed below rather than matched, because a symbol cannot be
                told apart from ordinary comparison syntax. One further shape escapes indexing: where a family is
                written collapsed (`DiffersWithToleranceRange1/2/5/10`), only the spelled-out member matches, so
                the others read as unindexed even though the entry discusses them.

                Addresses are permanent: a KERNEL-FINDINGS record is cited by its `KF<n>` anchor and a
                KERNEL-SEMANTICS section by its slug, neither of which a retitle can move.

                | operator | entries |
                |---|---|
                """);
        byOperator.forEach((operator, found) -> {
            List<String> links = found.stream()
                    .map(entry -> "[" + entry.label() + "](" + entry.doc() + "#" + entry.address() + ")")
                    .toList();
            out.append("| `").append(operator).append("` | ").append(String.join(" · ", links)).append(" |\n");
        });

        List<String> unmentioned = operatorIds.stream().filter(id -> !byOperator.containsKey(id)).toList();
        out.append("\nNo entry in either kernel doc names these ").append(unmentioned.size())
                .append(" catalog operators in a code span: ")
                .append(String.join(", ", unmentioned.stream().map(id -> "`" + id + "`").toList()))
                .append(".\n");
        return out.toString();
    }
}
