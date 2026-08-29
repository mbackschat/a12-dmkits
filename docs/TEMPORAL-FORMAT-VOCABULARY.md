# Temporal format vocabulary — derived from the kernel

> **Generated, never hand-edited.** Regenerate with `./build.sh :dumpTemporalVocabulary`;
> `TemporalFormatVocabularyGuardTest` fails the build when this file and the kernel disagree.
>
> **What this claims.** Over the candidate universe listed at the bottom, the kernel admits exactly
> the formats in the first table — on all three date-bearing kinds, since the vocabulary is
> kind-independent ([KF206](KERNEL-FINDINGS.md#kf206)). It does **not** claim the admitted set is
> closed: a format outside the universe is undiscovered, not refused. The universe is printed
> precisely so that limit travels with the answer.
>
> **Cite this file** rather than re-listing formats. A site that legitimately covers a subset says
> which subset and why, in the guard's registry.

## Admitted — 12

- `HH:mm:ss`
- `MM`
- `MM-dd`
- `MMdd`
- `dd.MM.yyyy`
- `ddMM`
- `yyyy`
- `yyyy-MM`
- `yyyy-MM-dd`
- `yyyy-MM-dd'T'HH:mm:ss`
- `yyyyMM`
- `yyyyMMdd`

## Refused — 123

Refused on all three kinds. Listed because without them, "the gate reads the string" and
"there is no gate" are the same observation.

- `MM.yyyy`
- `MM-yyyy`
- `yyyy/MM`
- `dd.MM`
- `HH:mm`
- `H:mm:ss`
- `HH:mm:ss.SSS`
- `hh:mm:ss a`
- `HHmmss`
- `HH-mm-ss`
- `yyyy-MM-dd HH:mm:ss`
- `dd.MM.yyyy HH:mm:ss`
- `yyyy-MM-dd HH:mm`
- `yyyyMM-dd`
- `yyyy-MMdd`
- `yyyyMM.dd`
- `yy`
- `yyMM`
- `MMyy`
- `ddMMyy`
- `dd.MM.yy`
- `M`
- `d`
- `MMM`
- `MMMM`
- `yyyyMMddHHmmss`
- `yyyy-MM-ddHH:mm:ss`
- `yyyyMMdd HH:mm:ss`
- `yyyy-MM-dd'T'HH:mm:ss.SSS`
- `yyyyMMdd'T'HHmmss`
- `yyyyMMdd'T'HH:mm:ss`
- `yyyy-MM-dd'T'HH:mm:ssZ`
- `yyyy-MM-dd'T'HH:mm:ss'Z'`
- `yyyy/MM/dd HH:mm:ss`
- `MM/dd/yyyy HH:mm:ss`
- `yyyy.MM`
- `yyyy.MM.dd`
- `yyyy/MM/dd`
- `yyyydd`
- `yyyy-dd`
- `yyyy.dd`
- `yyyy/dd`
- `yyyyddMM`
- `yyyy-dd-MM`
- `yyyy.dd.MM`
- `yyyy/dd/MM`
- `MMyyyy`
- `MM/yyyy`
- `MMyyyydd`
- `MM-yyyy-dd`
- `MM.yyyy.dd`
- `MM/yyyy/dd`
- `MM.dd`
- `MM/dd`
- `MMddyyyy`
- `MM-dd-yyyy`
- `MM.dd.yyyy`
- `MM/dd/yyyy`
- `dd`
- `ddyyyy`
- `dd-yyyy`
- `dd.yyyy`
- `dd/yyyy`
- `ddyyyyMM`
- `dd-yyyy-MM`
- `dd.yyyy.MM`
- `dd/yyyy/MM`
- `dd-MM`
- `dd/MM`
- `ddMMyyyy`
- `dd-MM-yyyy`
- `dd/MM/yyyy`
- `HH`
- `HHmm`
- `HH-mm`
- `HH.mm`
- `HH.mm.ss`
- `HHss`
- `HH:ss`
- `HH-ss`
- `HH.ss`
- `HHssmm`
- `HH:ss:mm`
- `HH-ss-mm`
- `HH.ss.mm`
- `mm`
- `mmHH`
- `mm:HH`
- `mm-HH`
- `mm.HH`
- `mmHHss`
- `mm:HH:ss`
- `mm-HH-ss`
- `mm.HH.ss`
- `mmss`
- `mm:ss`
- `mm-ss`
- `mm.ss`
- `mmssHH`
- `mm:ss:HH`
- `mm-ss-HH`
- `mm.ss.HH`
- `ss`
- `ssHH`
- `ss:HH`
- `ss-HH`
- `ss.HH`
- `ssHHmm`
- `ss:HH:mm`
- `ss-HH-mm`
- `ss.HH.mm`
- `ssmm`
- `ss:mm`
- `ss-mm`
- `ss.mm`
- `ssmmHH`
- `ss:mm:HH`
- `ss-mm-HH`
- `ss.mm.HH`
- `yyyy-MM-dd'T'HH:mm`
- `dd.MM.yyyy'T'HH:mm:ss`
- `dd.MM.yyyy'T'HH:mm`
- `dd.MM.yyyy HH:mm`

## Kind-split — 0

None. Every candidate answers identically on DATE, TIME and DATE_TIME, which is KF206's
kind-independence re-derived over the whole universe rather than over its witnesses.

## DATERANGE `(format, separator)` pairs admitted — 8

A DATERANGE allowlists the PAIR, so this is a separate domain from the table above and not a
projection of it — the two differ in both directions. Probed over the same candidate formats
× the separators `/` `-` `.` `` `` ` - ` `:` `_`.

| format | separator |
|---|---|
| `MM-dd` | `/` |
| `MM` | *(empty)* |
| `MM` | `/` |
| `dd.MM.yyyy` | `-` |
| `dd.MM` | `-` |
| `yyyy-MM-dd` | `/` |
| `yyyy-MM` | `/` |
| `yyyy` | `/` |

## Candidate universe — 135

Every format string put to the kernel, and **the denominator of every count above.** It is the
union of the estate's existing probes with a generated set: each ordered selection of the date
components `yyyy`/`MM`/`dd` joined by one of `` `` `-` `.` `/`, each ordered selection of the
time components `HH`/`mm`/`ss` joined by one of `` `` `:` `-` `.`, and the two complete date
forms joined to two clock forms by `'T'` or a space. Separators are generated because that is
the axis KF196 showed decides admission.

What it therefore does **not** reach, so a reader can judge the limit rather than infer it:
a string mixing the two component pools, a mixed separator within one string, a component
width this list does not name (`yy`, `M`, `MMM`), and any literal beyond `'T'`.

- `yyyy`
- `yyyy-MM`
- `yyyyMM`
- `MM`
- `MM-dd`
- `yyyy-MM-dd`
- `dd.MM.yyyy`
- `MM.yyyy`
- `MM-yyyy`
- `yyyy/MM`
- `dd.MM`
- `HH:mm:ss`
- `yyyy-MM-dd'T'HH:mm:ss`
- `HH:mm`
- `H:mm:ss`
- `HH:mm:ss.SSS`
- `hh:mm:ss a`
- `HHmmss`
- `HH-mm-ss`
- `yyyy-MM-dd HH:mm:ss`
- `dd.MM.yyyy HH:mm:ss`
- `yyyy-MM-dd HH:mm`
- `yyyyMM-dd`
- `yyyy-MMdd`
- `yyyyMM.dd`
- `yy`
- `yyMM`
- `MMyy`
- `ddMMyy`
- `dd.MM.yy`
- `M`
- `d`
- `MMM`
- `MMMM`
- `yyyyMMddHHmmss`
- `yyyy-MM-ddHH:mm:ss`
- `yyyyMMdd HH:mm:ss`
- `yyyy-MM-dd'T'HH:mm:ss.SSS`
- `yyyyMMdd'T'HHmmss`
- `yyyyMMdd'T'HH:mm:ss`
- `yyyy-MM-dd'T'HH:mm:ssZ`
- `yyyy-MM-dd'T'HH:mm:ss'Z'`
- `yyyy/MM/dd HH:mm:ss`
- `MM/dd/yyyy HH:mm:ss`
- `yyyy.MM`
- `yyyyMMdd`
- `yyyy.MM.dd`
- `yyyy/MM/dd`
- `yyyydd`
- `yyyy-dd`
- `yyyy.dd`
- `yyyy/dd`
- `yyyyddMM`
- `yyyy-dd-MM`
- `yyyy.dd.MM`
- `yyyy/dd/MM`
- `MMyyyy`
- `MM/yyyy`
- `MMyyyydd`
- `MM-yyyy-dd`
- `MM.yyyy.dd`
- `MM/yyyy/dd`
- `MMdd`
- `MM.dd`
- `MM/dd`
- `MMddyyyy`
- `MM-dd-yyyy`
- `MM.dd.yyyy`
- `MM/dd/yyyy`
- `dd`
- `ddyyyy`
- `dd-yyyy`
- `dd.yyyy`
- `dd/yyyy`
- `ddyyyyMM`
- `dd-yyyy-MM`
- `dd.yyyy.MM`
- `dd/yyyy/MM`
- `ddMM`
- `dd-MM`
- `dd/MM`
- `ddMMyyyy`
- `dd-MM-yyyy`
- `dd/MM/yyyy`
- `HH`
- `HHmm`
- `HH-mm`
- `HH.mm`
- `HH.mm.ss`
- `HHss`
- `HH:ss`
- `HH-ss`
- `HH.ss`
- `HHssmm`
- `HH:ss:mm`
- `HH-ss-mm`
- `HH.ss.mm`
- `mm`
- `mmHH`
- `mm:HH`
- `mm-HH`
- `mm.HH`
- `mmHHss`
- `mm:HH:ss`
- `mm-HH-ss`
- `mm.HH.ss`
- `mmss`
- `mm:ss`
- `mm-ss`
- `mm.ss`
- `mmssHH`
- `mm:ss:HH`
- `mm-ss-HH`
- `mm.ss.HH`
- `ss`
- `ssHH`
- `ss:HH`
- `ss-HH`
- `ss.HH`
- `ssHHmm`
- `ss:HH:mm`
- `ss-HH-mm`
- `ss.HH.mm`
- `ssmm`
- `ss:mm`
- `ss-mm`
- `ss.mm`
- `ssmmHH`
- `ss:mm:HH`
- `ss-mm-HH`
- `ss.mm.HH`
- `yyyy-MM-dd'T'HH:mm`
- `dd.MM.yyyy'T'HH:mm:ss`
- `dd.MM.yyyy'T'HH:mm`
- `dd.MM.yyyy HH:mm`
