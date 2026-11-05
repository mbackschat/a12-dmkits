const assert = require("node:assert/strict");
const { test } = require("node:test");
const { bundleUrl, unescapeJsString, changelogTree, render, releasesBetween } = require("./kernel-changelog.ts");

const tree = {
  type: "root",
  children: [
    { type: "heading", depth: 1, children: [{ type: "text", value: "Kernel Changelog" }] },
    { type: "heading", depth: 2, children: [{ type: "text", value: "[31.2.0] - 2026-09-14" }] },
    { type: "heading", depth: 3, children: [{ type: "text", value: "Added" }] },
    { type: "list", children: [{ type: "listItem", children: [{ type: "paragraph", children: [
      { type: "text", value: "[A12K-4329] - (DM) Added " }, { type: "inlineCode", value: "changeNote" },
      { type: "text", value: " to elements, a 'note' it's \"quoted\"." }] }] }] },
    { type: "heading", depth: 2, children: [{ type: "text", value: "[31.1.1] - 2026-08-17" }] },
    { type: "list", children: [{ type: "listItem", children: [{ type: "paragraph", children: [
      { type: "text", value: "[A12K-4331] - fixed " }, { type: "strong", children: [{ type: "text", value: "values" }] }] }] }] },
    { type: "heading", depth: 2, children: [{ type: "text", value: "[31.0.0] - 2026-05-18" }] },
    { type: "paragraph", children: [{ type: "text", value: "Major release: a new node\n" },
      { type: "inlineCode", value: "includeConfig" }, { type: "text", value: "." }] },
  ],
};

/** A bundle as a minifier writes it: the tree is one single-quoted JSON.parse literal among other modules. */
function bundleOf(value: unknown): string {
  const literal = JSON.stringify(value).replace(/\\/g, "\\\\").replace(/'/g, "\\'");
  return `x=1;83902(e){e.exports=JSON.parse('{"a":1}')},83903(e){e.exports=JSON.parse('${literal}')},83942(e){}`;
}

test("the bundle URL is resolved from the index page's script tag, and a page without one is refused", () => {
  assert.equal(bundleUrl('<script defer="defer" src="main.bundle.2e937fad.js?c07"></script>'),
    "https://geta12.com/main.bundle.2e937fad.js?c07");
  assert.throws(() => bundleUrl("<script src=\"vendor.js\"></script>"), /layout changed/);
});

test("the literal is decoded, not evaluated: quotes, backslashes and unicode escapes survive", () => {
  assert.equal(unescapeJsString("a\\'b\\\\c\\u00e9\\x41\\n"), "a'b\\céA\n");
  assert.equal(unescapeJsString("\\u{1F600}"), "😀");
});

test("the changelog literal is the one carrying the heading, not the first JSON.parse in the bundle", () => {
  assert.deepEqual(changelogTree(bundleOf(tree)), tree);
  assert.throws(() => changelogTree("e.exports=JSON.parse('{\"type\":\"root\"}')"), /layout changed/);
});

test("rendering keeps inline code, emphasis and quotes on one line per list item", () => {
  const markdown = render(tree);
  assert.match(markdown, /^# Kernel Changelog\n/);
  assert.match(markdown, /\n- \[A12K-4329\] - \(DM\) Added `changeNote` to elements, a 'note' it's "quoted"\.\n/);
  assert.match(markdown, /\n- \[A12K-4331\] - fixed \*\*values\*\*\n/);
  assert.match(markdown, /\nMajor release: a new node `includeConfig`\.\n/, "a soft break joins with a space");
});

test("a range keeps the releases after --from up to and including --to, and refuses a malformed bound", () => {
  const markdown = render(tree);
  const upgrade = releasesBetween(markdown, "31.0.0", "31.1.1");
  assert.match(upgrade, /^## \[31\.1\.1\]/);
  assert.doesNotMatch(upgrade, /31\.2\.0|31\.0\.0|Kernel Changelog/);
  assert.match(releasesBetween(markdown, "31.1.1"), /^## \[31\.2\.0\][\s\S]*changeNote/);
  assert.doesNotMatch(releasesBetween(markdown, "31.1.1"), /31\.1\.1/);
  assert.throws(() => releasesBetween(markdown, "31.1"), /--from must be a version/);
});
