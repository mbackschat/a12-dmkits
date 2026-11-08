# A12 Consistency Checker changelog

This changelog covers the checker and its predicate dependency published through `a12-consistency-releases`.

**Headline topics only.** An entry names the capabilities and breaking changes a consumer would notice, not every fix behind them.

## [@VERSION@]

## [0.16.0]

### Changed

- **BREAKING: checks a DocumentModel as A12 Kernel `31.3.1` does, on model format `29.8.0`.** A model is compatible up to format `29.8.0`, and a mount is read from its group's `includeConfig.reference`, as DM 29 defines it. `DeclaredElement.modelAlias` is now `includedReference`, `UnexpandedMount.alias` is now the nullable `reference`, and `DeclaredReference.refusedByTheReader` and `PreparedModel.resolvableElements` are removed. `PreparedModel` gains `uniquenessCriteria` as its eighth constructor parameter, which shifts its positional `copy` and `componentN` members.
- **BREAKING: a reference that reaches the model root has two kinds.** `Reference.AboveRoot` is now a sealed interface: `OnModelRoot` for a walk that stops on the root, which kernel 31.3.1 admits as a turning point, and `PastModelRoot` for one that climbs past it, which A12 refuses. `Reference.Undeclared` gains `leftOutByConversion`.
- **BREAKING: two check families are gone with the A12 laws behind them.** `ENUM_DISPLAY_COMPARABILITY` is retired because kernel 31.1.1 reads no display label for any comparison, and `VARIANT_FILTER` because DM 29 retired `variantFilter` and A12 reads past it.
- **A reference naming a rule or a computation resolves to nothing**, as kernel 31.1.1 answers it in every role measured.

### Added

- **New check families:** `POSSIBLE_CAUSE_FILTER` for a rule's possible-cause filter, `UNIQUENESS_CRITERIA` for a model's document uniqueness criteria (read through `PreparedModel.uniquenessCriteria`), `SUM_OF_TERMS_OPERANDS` for `SumOfTerms`, `ELEMENT_ID_UNIQUENESS` for two elements sharing an id, and `UNDECLARED_LOCALE_MESSAGE_PARAMETERS` for a message in an undeclared locale whose parameters A12's code generation cannot find.

## [0.14.0]

### Added

- **First Maven publication** of the checker and its predicate dependency, checking models as A12 Kernel `30.8.1` does.
