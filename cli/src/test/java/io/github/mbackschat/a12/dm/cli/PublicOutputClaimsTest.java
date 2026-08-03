package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Unqualified output-universality claims must not contradict the manifest's effective output profiles. */
class PublicOutputClaimsTest {

    private static final Pattern BLANKET_ENVELOPE = Pattern.compile(
            "(?i)\\b(every|all)\\s+(dmtool\\s+)?(result|verb|command|invocation|route)s?\\b"
                    + "[^\\n]{0,120}\\b(return(s|ed)?|emit(s|ted)?|print(s|ed)?|ride(s)?)\\b"
                    + "[^\\n]{0,100}\\b(envelope|json)\\b");
    private static final Pattern QUALIFIED = Pattern.compile(
            "(?i)(\\bnot\\s+(every|all)\\b|envelope-producing|effective[^\\n]{0,60}RESULT_ENVELOPE)");
    private static final Pattern JSON_IN_OUT = Pattern.compile(
            "(?i)(json[- ]?in\\s*([,/]|and)\\s*json[- ]?out|json\\s+(i/o|in/out))");
    private static final Pattern JSON_SLOGAN = Pattern.compile(
            "(?i)(\\b(dmtool|standalone\\s+cli|picocli\\s+verbs?|cli(?:\\s+verbs?)?)\\b"
                    + "[^\\n]{0,80}(json[- ]?in\\s*([,/]|and)\\s*json[- ]?out|json\\s+(i/o|in/out))|"
                    + "\\b(?:standalone\\s+)?cli\\b[^\\n]{0,80}\\bverbs?\\s*\\+\\s*json\\b)");
    private static final Pattern SCOPED_JSON_ROUTE = Pattern.compile(
            "(?i)(\\bboth\\b|\\bdmtool\\s+\\w+\\s+\\w+\\s+(uses?|returns?|emits?)\\b)");
    private static final Pattern OTHER_BLANKET = Pattern.compile(
            "(?i)(universally[^\\n]{0,100}envelope|"
                    + "one\\s+output\\s+reader\\s+suffices\\s+for\\s+the\\s+whole\\s+cli)");

    @Test
    void livePublicDocsDoNotUniversalizeTheEnvelopeOrJsonOutput() throws Exception {
        List<String> findings = new ArrayList<>();
        for (Path surface : PublicMarkdownSurfaces.live()) {
            List<String> lines = Files.readAllLines(surface);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (containsBlanketClaim(line)) {
                    findings.add(surface + ":" + (index + 1) + ": " + line.strip());
                }
            }
        }
        assertTrue(findings.isEmpty(), () -> "blanket dmtool output claim(s) contradict profiled raw/view routes:\n"
                + String.join("\n", findings));
    }

    @Test
    void matcherRejectsBlanketClaimsAndAllowsQualifiedGuidance() {
        for (String blanket : List.of(
                "Every invocation returns the same JSON result envelope.",
                "All dmtool commands emit one JSON result envelope.",
                "The CLI uses JSON in/out.",
                "dmtool uses JSON in/out.",
                "Envelope-producing routes share one shape; every dmtool command emits JSON.",
                "Not every route returns an envelope; all dmtool commands emit JSON.")) {
            assertTrue(isBlanket(blanket), () -> "negative control escaped: " + blanket);
        }
        for (String qualified : List.of(
                "Not every command returns a result envelope.",
                "Envelope-producing commands share a uniform result envelope.",
                "Like every verb whose effective kind is RESULT_ENVELOPE, this route returns an envelope.",
                "Every verb is described and every envelope verb advertises its schema.",
                "The demo reruns every command and checks its output.",
                "Both runtime verbs return JSON result envelopes.",
                "dmtool model eval uses JSON in/out.")) {
            assertTrue(!isBlanket(qualified), () -> "qualified guidance was rejected: " + qualified);
        }
    }

    static boolean containsBlanketClaim(String content) {
        return content.lines().anyMatch(line -> Arrays.stream(line.split("(?<=[.;])\\s+"))
                .anyMatch(PublicOutputClaimsTest::isBlanketClause));
    }

    static boolean containsJsonInOutSlogan(String content) {
        return JSON_IN_OUT.matcher(content.replace('\n', ' ')).find();
    }

    private static boolean isBlanket(String content) {
        return containsBlanketClaim(content);
    }

    private static boolean isBlanketClause(String clause) {
        return (BLANKET_ENVELOPE.matcher(clause).find()
                && !QUALIFIED.matcher(clause).find())
                || (JSON_SLOGAN.matcher(clause).find() && !SCOPED_JSON_ROUTE.matcher(clause).find())
                || OTHER_BLANKET.matcher(clause).find();
    }

}
