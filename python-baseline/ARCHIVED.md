# Python Baseline — Archived

This directory contains the original Python implementation of FailMapper
(ASE 2025), archived on 2026-07-08 after the Java port passed all five
migration milestones (see `../doc/JAVA_PORT_CONTRACT.md` and
`../doc/M5_BENCHMARK.md`).

**Status: frozen oracle.** The algorithm-bearing code here is the reference
baseline (contract oracle commit `d2baa9e`) used by the port's differential
validation layers (A/B/P). Do not evolve features here — the active
implementation lives in `../java/`.

- Original usage instructions: [README.md](README.md)
- Original technical docs: [doc/](doc/)
- All source paths cited in `../doc/JAVA_PORT_CONTRACT.md` (e.g.
  `fa_mcts.py:406`) refer to files in THIS directory; line numbers are pinned
  to commit `d2baa9e` (files unchanged since, apart from this relocation).
