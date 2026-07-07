# FailMapper

**Automated generation of unit tests guided by failure scenarios** — a
failure-aware Monte Carlo Tree Search (MCTS) framework that generates JUnit 5
tests optimized for finding real logical bugs, not just coverage.

This is the **Java-native framework** (a full port and re-architecture of the
original Python research prototype from our ASE 2025 paper; the Python
implementation is archived under [python-baseline/](python-baseline/)).

## How it works

```
Java project ──► Build oracle ──► Source analysis ──► Failure scenarios
                (real classpath)   (JavaParser AST)     (21 detectors)
                                                            │
     Bug reports ◄── LLM verification ◄── Failure-aware MCTS search
     + JUnit tests    (batch verdicts)     │
                                           ├─ LLM generates targeted tests
                                           ├─ in-memory compile (ms)
                                           ├─ forked JUnit run + timeout kill
                                           └─ JaCoCo coverage → reward
```

Instead of pure coverage maximization, the search extracts a per-class
*failure model* (boundary conditions, logical operations, complexity) plus
~21 logical-bug pattern categories, and biases MCTS toward code likely to
fail. The LLM is the expansion operator; execution is real (compile + run +
coverage); a verification pass separates real source bugs from bad tests.

---

## 1. Prerequisites

Check each of these before your first run:

| Requirement | Check with | Notes |
|---|---|---|
| JDK 17+ | `java -version` | 17, 21 both fine |
| Maven 3.9+ | `mvn -version` | used to build FailMapper and to compile your target project |
| DeepSeek API key | — | get one at https://platform.deepseek.com; other OpenAI-compatible endpoints work via env vars |
| Internet access | — | LLM API + Maven Central for dependency resolution |

Your **target project** (the code you want tested) must satisfy three things:

1. **It is a Maven project that has been compiled at least once** — run
   `mvn compile` in it first. FailMapper reads your build model but never
   runs your build.
2. **JUnit 5 is on its test classpath** (`junit-jupiter` or
   `junit-jupiter-api` in `test` scope). Generated tests are JUnit 5 and are
   compiled against *your project's* test classpath.
3. **Its dependencies resolve** from your local `~/.m2` repository or Maven
   Central (private-only repositories are not supported yet).

Multi-module projects are supported — FailMapper locates the module that owns
your target class and uses that module's classpath.

## 2. Install (build from source)

```bash
git clone <this-repository>
cd FailMapper/java
mvn package -DskipTests
```

This takes about a minute and produces a single self-contained executable:

```
java/failmapper-app/target/failmapper.jar     (~17 MB, no other files needed)
```

To also run FailMapper's own test suite (650+ tests, ~10 minutes), drop
`-DskipTests`.

## 3. Your first run (complete copy-paste example)

This example targets a real open-source class from Apache Commons CLI:

```bash
# 1. get a target project and compile it once
git clone --depth 1 https://github.com/apache/commons-cli.git /tmp/commons-cli
cd /tmp/commons-cli && mvn -q compile

# 2. set your API key
export DEEPSEEK_API_KEY=sk-...

# 3. run FailMapper  (args: <project> <class-fqn> <output-dir> [iterations] [seed])
java -jar /path/to/FailMapper/java/failmapper-app/target/failmapper.jar \
    /tmp/commons-cli \
    org.apache.commons.cli.OptionValidator \
    /tmp/fm-out \
    5 42
```

What you will see: the run is quiet by default and finishes with a summary
like

```
best test: /tmp/fm-out/best_test.java
coverage 95.65%, 0 real bug(s), 5 iteration(s)
```

Add `FM_LLM_VERBOSE=1` before the command to watch each LLM call live
(`[llm] call #3 ok reply=6831 chars elapsed=60.8s`).

**Duration & cost:** roughly 2–3 minutes per iteration (dominated by LLM
latency). A 5-iteration run makes ~6–17 LLM calls (extra calls appear when
failing tests go through bug verification) — typically a few cents with
deepseek-v4-pro.

## 4. Command reference

```
java -jar failmapper.jar <project-root> <target-class-fqn> <output-dir> [maxIterations] [seed]
```

| Argument | Required | Meaning | Default |
|---|---|---|---|
| `project-root` | yes | root directory of the target Maven project | — |
| `target-class-fqn` | yes | fully-qualified class to test, e.g. `com.example.Parser` | — |
| `output-dir` | yes | where results are written; **must be outside the target project** (enforced) | — |
| `maxIterations` | no | MCTS iterations; more = deeper search, more LLM cost | 20 |
| `seed` | no | random seed for the search (LLM sampling stays stochastic) | 42 |

| Environment variable | Required | Meaning |
|---|---|---|
| `DEEPSEEK_API_KEY` | yes | API key; read from the environment, never persisted |
| `DEEPSEEK_MODEL` | no | model id (default `deepseek-v4-pro`) |
| `FM_LLM_VERBOSE` | no | set to `1` for one log line per LLM call (sizes + latency only, never prompt content) |

Fixed behavior worth knowing: each generated test executes in a **forked JVM
with a 60-second hard timeout** — an accidentally-infinite test is killed and
recorded, it cannot hang the run.

## 5. Understanding the output

Five files appear in your output directory:

| File | What it is |
|---|---|
| `best_test.java` | The best generated JUnit 5 test class (highest coverage, with verified bug-detecting methods merged in). Copy it into your project's `src/test/java` if you want to keep it. |
| `summary.json` | One-look result: coverage, iterations, bug counts, model, seed |
| `verified_bugs.json` | Every failing test method that went through LLM verification, with the verdict |
| `potential_bugs.json` | Raw failing-test observations collected during the search (pre-verification) |
| `iteration_log.json` | Per-iteration trace: action chosen, reward, coverage — useful to understand what the search did |

The file you care about most is `verified_bugs.json`. Each entry:

```json
{
  "methodName": "testStripHyphensShortPrefix",
  "isRealBug": true,              // true = verified source bug
                                  // false = bad test / false alarm
  "verificationConfidence": 0.95, // 0..0.95
  "explanation": "The source code of Util.stripLeadingHyphens contains..."
}
```

Read `isRealBug: true` entries first and check `explanation` against the
source. Expect some duplicates (several test methods often hit the same
underlying bug) and treat verdicts as strong leads, not court rulings — in
our benchmark roughly 1 false alarm per clean run slips through.

## 6. What FailMapper will never do to your project

- It **never modifies** your `pom.xml`, sources, or tests (verified at
  runtime: the tool refuses to write inside the target tree).
- Coverage instrumentation happens in FailMapper's own forked JVM via the
  JaCoCo agent — nothing is injected into your build.
- All compilation of generated tests happens in temporary directories.

Delete the output directory and there is no trace the tool ever ran.

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `DEEPSEEK_API_KEY environment variable is not set` | `export DEEPSEEK_API_KEY=...` in the same shell |
| `usage: FaMctsRunner ...` and exit | fewer than 3 arguments — see §4 |
| `source file not found for <fqn>` | wrong FQN (check package), or the class lives in a module/source root the project's POM doesn't declare |
| every iteration reports compile errors mentioning `org.junit` | your target project has no JUnit 5 test dependency — add `junit-jupiter` (test scope) and re-run `mvn compile` |
| coverage is always `0.00%` | target project not compiled (`target/classes` missing) — run `mvn compile` in it |
| `output dir ... inside the target project` error | choose an output directory outside the project tree |
| `HTTP 401` | invalid API key; `HTTP 429` / retries logged — rate limited, the client backs off automatically |
| run seems stuck ~1 min then continues | that's the 60 s fork timeout killing a runaway generated test — by design |
| `parent POM ... could not be resolved` | the target's parent/BOM lives in a private repository — build it once locally (`mvn install`) so it lands in `~/.m2` |

## 8. Architecture

| Module | Role |
|---|---|
| `failmapper-core` | Typed domain contracts (FQN-keyed end to end) |
| `failmapper-analysis` | JavaParser/SymbolSolver extraction: class model, failure model, 21 failure-scenario detectors, symbol API retrieval |
| `failmapper-build` | Build-system oracle: effective POM, transitive test classpath (Maven Resolver), multi-module reactors, Gradle Tooling API |
| `failmapper-exec` | In-memory compilation (structured diagnostics) + forked JUnit Platform execution with hard timeouts |
| `failmapper-coverage` | JaCoCo agent attach + core-API reading, exact per-class attribution |
| `failmapper-search` | The FA-MCTS kernel: UCB selection with failure-aware bonus, reward composition, strategy selection, bug classification |
| `failmapper-llm` | LLM clients (DeepSeek, OpenAI-compatible), prompt templates, code extraction with fallback salvage |
| `failmapper-app` | End-to-end composition and the `failmapper.jar` CLI |

## 9. Validation

The port is governed by a fidelity contract
([doc/JAVA_PORT_CONTRACT.md](doc/JAVA_PORT_CONTRACT.md)) with four
machine-checked validation layers:

- **Layer A** — 259 differential fixtures generated from the real Python
  implementations; kernel formulas bit-identical
  ([doc/LAYER_A_DIFFERENTIAL.md](doc/LAYER_A_DIFFERENTIAL.md))
- **Layer B** — extractor alignment on 39 real classes, zero unexplained
  diffs ([doc/LAYER_B_ALIGNMENT.md](doc/LAYER_B_ALIGNMENT.md),
  [doc/FS_DETECTOR_ALIGNMENT.md](doc/FS_DETECTOR_ALIGNMENT.md))
- **Layer P** — prompt templates byte-identical to the Python renderings
- **Layer C** — end-to-end pilot benchmark vs the Python baseline
  ([doc/M5_BENCHMARK.md](doc/M5_BENCHMARK.md)): seeded-bug recall 6/6 vs 0/6,
  ~42% cheaper tokens, ~35% faster, zero project mutations

## 10. The Python baseline

The original prototype is preserved unmodified in
[python-baseline/](python-baseline/) as the frozen differential-testing
oracle (see [python-baseline/ARCHIVED.md](python-baseline/ARCHIVED.md)).

## Citation

```bibtex
@inproceedings{dong2025failmapper,
  title={FailMapper: Automated Generation of Unit Tests Guided by Failure Scenarios},
  author={Dong, Ruiqi and Deng, Zehang and Zhu, Xiaogang and Du, Xiaoning and Liu, Huai and Wang, Shaohua and Wen, Sheng and Xiang, Yang},
  booktitle={2025 40th IEEE/ACM International Conference on Automated Software Engineering (ASE)},
  pages={2388--2400},
  year={2025},
  organization={IEEE}
}
```
