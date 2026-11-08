# Source statistics

How large each part of a12-dmkits is, by purpose and language. The [project README](../README.md) links here; the release repositories carry their own artifact-scoped tables. Regenerate with [`scripts/update-code-statistics.sh`](../scripts/update-code-statistics.sh).

<!-- source-statistics:root:start -->
## Source statistics

Measured with [locmetry](https://github.com/mbackschat/locmetry) from this repository, using [`.locmetryrc`](../.locmetryrc). Only tracked source files are counted; generated code, data fixtures, documentation, third-party wrappers, and build output are excluded. Code excludes comments and blank lines.

### Production

Product implementation and examples.

| Language | Files | Lines | Code | Comments | Blanks |
|---|---:|---:|---:|---:|---:|
| CSS | 2 | 887 | 750 | 3 | 134 |
| HTML | 2 | 347 | 335 | 0 | 12 |
| Java | 563 | 82938 | 55522 | 19005 | 8411 |
| JavaScript | 2 | 283 | 144 | 122 | 17 |
| Kotlin | 364 | 71737 | 42947 | 23149 | 5641 |
| TypeScript | 19 | 3913 | 3324 | 306 | 283 |
| **Total** | **952** | **160105** | **103022** | **42585** | **14498** |

### Verification

Automated tests, laws, differentials, consumer checks, and their support code.

| Language | Files | Lines | Code | Comments | Blanks |
|---|---:|---:|---:|---:|---:|
| HTML | 4 | 101 | 56 | 45 | 0 |
| Java | 1349 | 205028 | 142379 | 39179 | 23470 |
| JavaScript | 12 | 1036 | 666 | 266 | 104 |
| Kotlin | 1583 | 227076 | 169353 | 36676 | 21047 |
| Shell | 12 | 2533 | 1943 | 361 | 229 |
| TypeScript | 58 | 7995 | 6817 | 648 | 530 |
| **Total** | **3018** | **443769** | **321214** | **77175** | **45380** |

### Tooling

Build, verification, release, deployment, and maintainer tooling.

| Language | Files | Lines | Code | Comments | Blanks |
|---|---:|---:|---:|---:|---:|
| BASH | 2 | 364 | 223 | 127 | 14 |
| CSS | 1 | 1 | 1 | 0 | 0 |
| Groovy | 21 | 11408 | 8538 | 2452 | 418 |
| HTML | 6 | 167 | 156 | 0 | 11 |
| Java | 1 | 70 | 52 | 10 | 8 |
| JavaScript | 5 | 1668 | 1408 | 161 | 99 |
| Kotlin | 6 | 134 | 123 | 0 | 11 |
| Python | 1 | 166 | 144 | 1 | 21 |
| Shell | 62 | 6500 | 4661 | 1337 | 502 |
| TypeScript | 31 | 4526 | 3771 | 434 | 321 |
| **Total** | **136** | **25004** | **19077** | **4522** | **1405** |

Maintainers regenerate these tables with the latest locmetry; release publishers compare them with fresh counts before any public mutation.
<!-- source-statistics:root:end -->
