# Model synthesis fidelity specification

This specification defines the complete classification of the model-performance profile axes consumed by `profile compare`. It keeps every axis because an extractor, synthesizer, comparator, or schema change must not add or drop one without assigning an explicit fidelity policy.

The executable registry is authoritative once G52 ships. A guard compares that registry with the profile schema and this table, and fails on an unclassified or stale axis.

## Policies

| Policy | Comparison rule |
|---|---|
| `EXACT` | Actual equals target for every seed. |
| `AT_LEAST` | Actual is greater than or equal to target; used for performance-critical mechanisms where under-representation would produce a falsely cheap benchmark. |
| `BUCKET` | Actual lands in the same or adjacent declared logarithmic bucket; the comparator reports the exact delta. |
| `INFORMATIVE` | Reported but does not decide the comparison outcome. |

## Axis classification

| Profile area | Axes | Policy | Reason |
|---|---|---|---|
| Structure cardinality | field count, group count, rule count, computation count, repeatable-group count | `EXACT` | These determine report/catalog size and the number of evaluation programs. |
| Structure topology | maximum group depth, exact repeatable-depth histogram, maximum repeatable depth | `EXACT` | These select traversal and nested-context paths. |
| Field population | field-kind histogram, computed-field-kind histogram | `EXACT` | Kind-specific formal checks and computation paths must retain their population. |
| Group width | fields-per-group distribution | `BUCKET` | Valid placement constraints may move fields between equivalent groups; the width regime must remain comparable. |
| Repeatability | bounded max repetitions, bounded chain capacity, field chain capacity, sibling-repeatable fan | `BUCKET` | Capacity magnitude and topology drive row work; exact per-node reconstruction is neither observable nor required. |
| Unbounded repeatability | unbounded scope/scan counts | `EXACT` on extract; synthesis refuses | Substituting one would understate work; no implicit bounding policy is allowed. |
| Constraints | number bound presence, string length/pattern presence, number scale, enum cardinality | `BUCKET` | These drive formal-check families and cost while validity limits exact placement. |
| AST shape | node count, depth, Boolean arity, variadic arity, distinct fan-in | `BUCKET` | Evaluator work must retain the same shape regime without reproducing protected expressions. |
| Dispatch mix | operator histogram, intrinsic arithmetic histogram | `AT_LEAST` for non-zero families; exact total is informative | Every target operator family must remain exercised; harmless support nodes may add occurrences. |
| Construct mix | construct presence/count, construct combinations, constructs by scope | `AT_LEAST` | Dropping a performance-critical mechanism or its scope joint would create a falsely cheap twin. |
| Scope work | scope class, bounded scope capacity | `BUCKET` | Evaluation multiplicity must stay in the same regime. |
| Explicit scans | scan count, scan capacity, maximum star depth, filtered scans and filter complexity | `AT_LEAST` for count/depth; `BUCKET` for capacity | Explicit collection work is a primary interpreter scaling axis and may not be under-represented. |
| Combined work | scope × scan work capacity and scope × scan joint | `BUCKET`, with no lower-bucket result for a non-zero target | The same scan at model scope and per-row scope are different complexity classes. |
| Resolution | walk-up distance and reference fan-in/fan-out | `BUCKET` | These drive repeated path resolution and report usage inversion. |
| Group fill | occurrence count, contained-field width/capacity | `AT_LEAST` for occurrence; `BUCKET` for width/capacity | Group-fill work must not disappear; equivalent group widths may vary. |
| Semantic index | occurrence count and target capacity | `AT_LEAST` for occurrence; `BUCKET` for capacity | Index lookup and construction paths must remain present at comparable scale. |
| Uniqueness | occurrence count, key arity/capacity | `AT_LEAST` for occurrence; `BUCKET` for capacity | Hash/cache paths must remain exercised. |
| Parallel references | sibling-branch reference count/fan | `AT_LEAST` | Under-representation removes the cross-branch join path. |
| Outer correlation | every effective `$` occurrence and correlated capacity | `AT_LEAST` | Correlation is a nested-loop/hash-join performance discriminator. |
| Computation tables | alternative and precondition distributions, tolerance-band histogram | `BUCKET` for distributions; `AT_LEAST` for non-zero bands | These select ordered-table and tolerance paths. |
| Aggregate scale | matched/mismatched aggregated-cell scale counts | `AT_LEAST` | Both rescaling regimes present in the target must remain present. |
| Computation dependency | feeding-computation count and maximum cascade depth | `EXACT` for maximum depth; `AT_LEAST` for feeding count | Cascade depth determines staged evaluation; feeding work must not disappear. |
| Wiring | referenced-field count and field fan-out | `BUCKET` | Dense and sparse dependency surfaces must remain distinguishable. |
| Opaque elements | opaque rule/computation counts and reasons | `INFORMATIVE`; never synthesized as a fake typed construct | The profile records a coverage ceiling; synthesis cannot honestly reproduce unknown semantics. |

## Committed calibration profiles

The compact, breadth-shaped, and depth-shaped JSON files imported from the PR are `profile′` results extracted from synthesized own-domain models. They are approved committed fixtures. Their private real-model source profiles are not present.

The PR artifacts are adapted once to the final current schema before landing. The implementation does not retain removed PR fields or a compatibility reader. Their provenance remains `profile′` from synthesized own-domain models; adapting the schema must not turn that into a claim that they were extracted from the private targets.

## Generation boundary

Synthesis builds rules and computations through the typed API, then persists the complete program population with one `BoundModel.addAll(rules, computations)` operation. Per-program immutable snapshot writes are not an equivalent implementation: they repeatedly parse, validate, and serialize the growing model and make the committed breadth/depth calibration profiles needlessly superlinear to generate. The bulk boundary preserves the same terminal strict model check and byte-identical output.
