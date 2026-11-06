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
| Java | 550 | 81631 | 54672 | 18715 | 8244 |
| JavaScript | 2 | 283 | 144 | 122 | 17 |
| Kotlin | 354 | 70787 | 42353 | 22898 | 5536 |
| TypeScript | 18 | 3862 | 3294 | 286 | 282 |
| **Total** | **928** | **157797** | **101548** | **42024** | **14225** |

### Verification

Automated tests, laws, differentials, consumer checks, and their support code.

| Language | Files | Lines | Code | Comments | Blanks |
|---|---:|---:|---:|---:|---:|
| HTML | 4 | 101 | 56 | 45 | 0 |
| Java | 1313 | 201814 | 139960 | 38861 | 22993 |
| JavaScript | 12 | 1036 | 666 | 266 | 104 |
| Kotlin | 1569 | 225235 | 167853 | 36478 | 20904 |
| Shell | 12 | 2522 | 1935 | 359 | 228 |
| TypeScript | 52 | 7566 | 6465 | 604 | 497 |
| **Total** | **2962** | **438274** | **316935** | **76613** | **44726** |

### Tooling

Build, verification, release, deployment, and maintainer tooling.

| Language | Files | Lines | Code | Comments | Blanks |
|---|---:|---:|---:|---:|---:|
| BASH | 2 | 362 | 223 | 125 | 14 |
| CSS | 1 | 1 | 1 | 0 | 0 |
| Groovy | 20 | 11388 | 8523 | 2447 | 418 |
| HTML | 6 | 167 | 156 | 0 | 11 |
| Java | 1 | 70 | 52 | 10 | 8 |
| JavaScript | 5 | 1796 | 1512 | 157 | 127 |
| Kotlin | 6 | 134 | 123 | 0 | 11 |
| Python | 1 | 166 | 144 | 1 | 21 |
| Shell | 62 | 6479 | 4651 | 1327 | 501 |
| TypeScript | 28 | 3964 | 3362 | 325 | 277 |
| **Total** | **132** | **24527** | **18747** | **4392** | **1388** |

Maintainers regenerate these tables with the latest locmetry; release publishers compare them with fresh counts before any public mutation.
<!-- source-statistics:root:end -->
