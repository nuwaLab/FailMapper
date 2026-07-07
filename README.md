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

## Modules

| Module | Role |
|---|---|
| `failmapper-core` | Typed domain contracts (FQN-keyed end to end) |
| `failmapper-analysis` | JavaParser/SymbolSolver extraction: class model, failure model, 21 failure-scenario detectors, symbol API retrieval |
| `failmapper-build` | Build-system oracle: effective POM, transitive test classpath (Maven Resolver), multi-module reactors, Gradle Tooling API |
| `failmapper-exec` | In-memory compilation (structured diagnostics) + forked JUnit Platform execution with hard timeouts |
| `failmapper-coverage` | JaCoCo agent attach + core-API reading, exact per-class attribution — user build files are never modified |
| `failmapper-search` | The FA-MCTS kernel: UCB selection with failure-aware bonus, reward composition, strategy selection, bug classification |
| `failmapper-llm` | LLM clients (DeepSeek, OpenAI-compatible), byte-fidelity prompt templates, code extraction with fallback salvage |
| `failmapper-app` | End-to-end composition and CLI runner |

## Requirements

- JDK 17+
- Maven 3.9+
- An LLM API key (currently DeepSeek; set `DEEPSEEK_API_KEY`)

## Quick start

```bash
cd java
mvn package -DskipTests

# assemble the runtime classpath once
mvn -q -pl failmapper-app dependency:build-classpath -Dmdep.outputFile=/tmp/fm.cp
CP="$(ls -d failmapper-*/target/classes | tr '\n' ':')$(cat /tmp/fm.cp)"

# run: <projectRoot> <targetClassFqn> <outputDir> [maxIterations] [seed]
DEEPSEEK_API_KEY=... java -cp "$CP" org.failmapper.app.FaMctsRunner \
    /path/to/your-maven-project com.example.YourClass ./fm-out 5 42
```

Environment variables: `DEEPSEEK_API_KEY` (required), `DEEPSEEK_MODEL`
(default `deepseek-v4-pro`), `FM_LLM_VERBOSE=1` for per-call logging.

Outputs in the given directory (never inside your project tree):
`best_test.java`, `iteration_log.json`, `potential_bugs.json`,
`verified_bugs.json`, `summary.json`.

## Validation

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

Full test suite: 650+ tests, `cd java && mvn test`.

## The Python baseline

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
