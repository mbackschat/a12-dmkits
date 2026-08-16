#!/usr/bin/env python3
"""findings-overlap.py — does a findings entry RESTATE an existing one?

WHY THIS EXISTS. The knowledge estate is append-only with an amend-in-place rule, and the
plausible long-run failure of that shape is duplication: at 600+ entries the cost of FINDING
the entry to amend rises with n, and nothing helps. A proposal to build near-duplicate
detection (shingling or embeddings over the entry corpus) was deferred pending a measurement
rather than an argument, and this script is that measurement.

THE ANSWER IT PRODUCED, 2026-08-16, over 609 entries / 185,136 pairs:

  pairs > 0.40 cosine ....... 67
    cross-doc ............... 54, of which 52 are IF|KF
    same-doc ................ 13
  highest overall ........... 0.723  IF72 | KF80
  highest same-doc .......... 0.517  IF153 | IF163

ZERO are restatements, and the reason is structural rather than lucky. The cross-doc mass is
the estate's deliberate pairing: KERNEL-FINDINGS records what the kernel does, measured against
the kernel; INTERPRETER-FINDINGS records the clean-room reimplementation matching it. The same
fact appearing in both from two perspectives IS the licensing boundary made visible, so high
similarity there is the design working, not a defect. The same-doc pairs are increments of one
workstream or explicitly-declared twins: the strongest, IF112 | IF118, is a formal-side and an
eval-side defect of one shape, and IF118's own first sentence reads "The EVAL twin of IF112".

So near-duplicate detection is NOT worth building today. The estate stays coherent by explicit
cross-reference ("the EVAL twin of", "the sibling IF250's sweep", "graduated from IG58"), and
that convention is visible in the data rather than assumed.

RE-OPEN TRIGGER, because this answer rests on a current fact. Re-run when the corpus has grown
materially (say +50%, or ~900 entries) or when the cross-reference convention visibly lapses.
If same-doc pairs above 0.40 stop being twins-or-increments on inspection, the deferral expires.

RECALL LIMIT, stated rather than implied. This finds LEXICAL overlap. A genuine restatement in
wholly different vocabulary is invisible to it, so the measurement bounds the problem from one
side only: it can prove duplication EXISTS, and it cannot prove none does. Its verdict is
"nothing in the top of a 185k-pair ranking is a restatement", which is what the deferral needs.

USAGE
  scripts/findings-overlap.py                 report the ranking and the same-doc split
  scripts/findings-overlap.py --threshold 0.5 raise the reporting bar
"""
import argparse
import math
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"

STOP = set("""a an the and or but if then than that this these those is are was were be been being
have has had do does did of in on at to for with by from as it its not no nor so such only own
same too very can will just should now which who whom what when where why how all any both each few
more most other some there here we our us you your they them their he she his her i me my one two
also into over under again further once about against between during before after above below up down
out off because while until though although whether either neither rather via per vs cf etc""".split())

TOKEN = re.compile(r"[a-z][a-z0-9_]{2,}")

MIN_TOKENS = 20
"""Entries below this are stubs or pure pointers; scoring them produces noise, not signal."""


def tokens(text):
    text = re.sub(r"```.*?```", " ", text, flags=re.S)
    text = re.sub(r"`[^`]*`", " ", text)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"<[^>]+>", " ", text)
    return [t for t in TOKEN.findall(text.lower()) if t not in STOP]


def _anchored(path, pattern, id_group, title_group):
    out, cur = [], None
    for line in path.read_text().split("\n"):
        m = re.match(pattern, line)
        if m:
            if cur:
                out.append(cur)
            cur = {"id": m.group(id_group).upper(), "title": m.group(title_group).strip(), "body": []}
        elif cur is not None:
            cur["body"].append(line)
    if cur:
        out.append(cur)
    return out


def load():
    entries = []
    entries += _anchored(DOCS / "INTERPRETER-FINDINGS.md",
                         r'^#{2,4} (IF\d+):?(.*?)<a id="if\d+"></a>\s*$', 1, 2)
    entries += _anchored(DOCS / "KERNEL-FINDINGS.md",
                         r'^#{2,4} (?P<t>.*?)<a id="(?P<i>kf\d+)"></a>\s*$', "i", "t")
    for line in (DOCS / "API-FINDINGS.md").read_text().split("\n"):
        m = re.match(r"^(\d+)\. \*\*(.*)$", line)
        if m:
            entries.append({"id": "API" + m.group(1), "title": m.group(2)[:160], "body": [m.group(2)]})
    for e in entries:
        e["tf"] = Counter(tokens(e["title"] + " " + "\n".join(e["body"])))
    return [e for e in entries if sum(e["tf"].values()) >= MIN_TOKENS]


def vectorize(entries):
    n = len(entries)
    df = Counter()
    for e in entries:
        df.update(e["tf"].keys())
    idf = {t: math.log(n / (1 + c)) for t, c in df.items()}
    for e in entries:
        v = {t: (1 + math.log(c)) * idf.get(t, 0.0) for t, c in e["tf"].items()}
        norm = math.sqrt(sum(x * x for x in v.values())) or 1.0
        e["vec"] = {t: x / norm for t, x in v.items()}


def family(entry_id):
    return "API" if entry_id.startswith("API") else entry_id[:2]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--threshold", type=float, default=0.40)
    ap.add_argument("--show", type=int, default=10)
    args = ap.parse_args()

    entries = load()
    vectorize(entries)
    n = len(entries)

    same, cross = [], []
    for i in range(n):
        a = entries[i]
        for j in range(i + 1, n):
            b = entries[j]
            small, big = (a["vec"], b["vec"]) if len(a["vec"]) < len(b["vec"]) else (b["vec"], a["vec"])
            score = sum(v * big.get(t, 0.0) for t, v in small.items())
            if score > args.threshold:
                (same if family(a["id"]) == family(b["id"]) else cross).append((score, a, b))
    same.sort(key=lambda x: -x[0])
    cross.sort(key=lambda x: -x[0])

    ifkf = sum(1 for _, a, b in cross if {family(a["id"]), family(b["id"])} == {"IF", "KF"})
    print(f"{n} entries, {n * (n - 1) // 2:,} pairs, threshold {args.threshold}")
    print(f"  cross-doc  {len(cross):>4}   (IF|KF pairs: {ifkf}) — the deliberate kernel/interpreter pairing")
    print(f"  same-doc   {len(same):>4}   — the only genuine duplication candidates\n")
    print("same-doc pairs, judge these by READING them; the score is a shortlist, never a verdict:")
    for score, a, b in same[:args.show]:
        print(f"  {score:.3f}  {a['id']} | {b['id']}")
        print(f"          A {a['title'][:100]}")
        print(f"          B {b['title'][:100]}")


if __name__ == "__main__":
    main()
