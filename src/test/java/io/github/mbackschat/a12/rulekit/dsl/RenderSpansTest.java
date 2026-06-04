package io.github.mbackschat.a12.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.rulekit.Fixtures;
import io.github.mbackschat.a12.rulekit.validate.Span;

/**
 * The F4a renderer emit-map ({@code renderWithSpans}) — every {@link Condition} node's character
 * span into the rendered text, the foundation for authored-rule span-pointing. The text must stay
 * byte-identical to {@link DslRenderer#render}.
 */
class RenderSpansTest {

    private static final BoundModel m = Fixtures.subscription();
    private static final String NAME = "/Subscription/Addons/Name";
    private static final String FEE = "/Subscription/Addons/MonthlyFee";

    private final DslRenderer renderer = new DslRenderer();

    @Test
    void textIsByteIdenticalToRender() {
        Condition c = m.field(NAME).filled().and(m.num(FEE).gt("100"));
        assertEquals(renderer.render(c), renderer.renderWithSpans(c).text());
    }

    @Test
    void aLeafNodeSpansItsWholeRenderedText() {
        Condition leaf = m.field(NAME).filled();
        DslRenderer.Rendered rendered = renderer.renderWithSpans(leaf);

        assertEquals(new Span(0, rendered.text().length()), rendered.spans().get(leaf));
        // the span addresses exactly the leaf's text
        Span s = rendered.spans().get(leaf);
        assertEquals(renderer.render(leaf), rendered.text().substring(s.start(), s.end()));
    }

    @Test
    void childSpansLocateEachConjunctInTheRenderedText() {
        Condition left = m.field(NAME).filled();
        Condition right = m.num(FEE).gt("100");
        Condition both = left.and(right);

        DslRenderer.Rendered rendered = renderer.renderWithSpans(both);
        String text = rendered.text();

        Span ls = rendered.spans().get(left);
        Span rs = rendered.spans().get(right);
        assertEquals(renderer.render(left), text.substring(ls.start(), ls.end()));
        assertEquals(renderer.render(right), text.substring(rs.start(), rs.end()));
        assertEquals(new Span(0, text.length()), rendered.spans().get(both));
    }
}
