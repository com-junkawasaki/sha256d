# sha256d-clj

A portable **`.cljc` SHA-256 / Bitcoin SHA-256d** reference implementation, plus a
small **evolutionary benchmark tournament** over interchangeable, individually-proven
round-primitive formulations -- shaped like Google DeepMind's **[AI
co-scientist](https://research.google/blog/accelerating-scientific-breakthroughs-with-an-ai-co-scientist/)**
(Generation / Reflection / Ranking / Evolution / Proximity / Meta-review agents under a
Supervisor, an Elo-based ranking tournament, and self-play/recursive self-critique
driving iterative improvement) and **AlphaEvolve** (LLM-guided evolutionary code search
over a scored program population), but scoped down to a finite, closed, non-LLM search:
for a hash function, "close to correct" isn't a lower-scoring candidate the way a
slower matrix-multiplication algorithm is in AlphaEvolve's search -- it's simply not
SHA-256. Reflection here is therefore a hard correctness gate, applied before any
benchmarking, never one term in a fitness score, and there's no LLM-driven "debate" --
Evolution's recombination is deterministic gene-crossover over a hand-verified pool,
not self-play (see ADR-2607012300 for why: no Workflow/token cost per run).

## Why this shape

The starting reference for this repo was a case study on Google DeepMind's
AlphaProof / Gemini Deep Think / AlphaEvolve / FunSearch line of work -- LLM +
evolutionary-search systems that discover genuinely new algorithms (AlphaEvolve found a
48-multiplication algorithm for 4x4 matrix multiplication, beating Strassen's
56-year-old bound). That search methodology -- maintain a population of candidate
programs, verify/score each, keep and recombine the best -- transfers to SHA-256, but
with one absolute constraint those systems don't have: **correctness is binary, not a
score.** So this repo keeps the *shape* of that search (see `sha256d.evolve`) while
making the correctness gate (`sha256d.evolve/reflect`) a hard filter, and keeps the
"discoveries" honest and small: the gene pool starts with two well-known, individually
algebra-proven Ch/Maj reformulations (see `sha256d.ops`), plus Bitcoin mining's classic
midstate-caching optimization (`sha256d.midstate`), which is a structural ~2x win of a
completely different order than round-primitive rewrites -- see
`docs/evolution-log.md` for what the tournament actually found when run.

## Modules

- **`sha256d.core`** -- FIPS 180-4 SHA-256 + Bitcoin's SHA-256d (`sha256(sha256(x))`),
  over plain sequences of byte values (ints 0-255), no host byte-array type in the hot
  path. This is the correctness oracle everything else in the repo is checked against.
- **`sha256d.ops`** -- the "gene pool", currently 3 variants each for Ch and Maj
  (9 candidates): `*-naive` (FIPS textbook), `*-alt` (the OpenSSL/Bitcoin Core
  one-fewer-gate formulation), and `*-or` (the same pairwise terms as `*-naive`, OR'd
  instead of XOR'd -- valid because those terms are pairwise-disjoint/never-exactly-
  two-1). Each proven algebraically equivalent to the FIPS textbook form in a
  doc-comment and re-checked exhaustively (all single-bit truth-table rows + randomized
  32-bit words) in `test/sha256d/ops_test.cljc`.
- **`sha256d.midstate`** -- Bitcoin block-header mining's classic optimization: cache
  the compression state after a header's constant first 64 bytes so each nonce attempt
  only re-runs the second block's 64 rounds, not the whole 80-byte header.
- **`sha256d.evolve`** -- the tournament: Generation (enumerate gene combinations) ->
  Reflection (hard correctness gate) -> Ranking (pairwise Elo benchmark tournament) ->
  Proximity (cluster results within 1% as ties) -> Evolution (recombine elites) ->
  Meta-review, for N generations under a Supervisor (`run-tournament`).

## Usage

```clojure
(require '[sha256d.core :as sha256d])

(sha256d/bytes->hex (sha256d/sha256-bytes (sha256d/str->bytes "abc")))
;; => "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

(sha256d/bytes->hex (sha256d/sha256d-bytes (sha256d/str->bytes "abc"))) ; SHA256(SHA256(x))
```

```clojure
(require '[sha256d.midstate :as midstate])

;; header is a vector of 80 byte-values (version|prevhash|merkleroot|time|bits|nonce)
(let [mid (midstate/midstate header)]
  ;; re-run this per nonce attempt against the same header prefix -- `mid` is computed once
  (midstate/header-hash mid (subvec header 64 80)))
```

```bash
clojure -M:test               # correctness suite (JVM) -- 5100+ assertions
clojure -M:cljs && node target/cljs-verify.js   # portability proof (node)
clojure -M:evolve              # run the tournament, print a Meta-review report
```

## Portability

The round-function primitives (`Ch`/`Maj`/`Σ0`/`Σ1`/rotr/add32) are **plain shared
`.cljc`** with no `#?(:clj :cljs)` split: every operation is either bit-position-local
(and/or/xor/not/shift/rotate, sign-representation-invariant on both platforms) or a
bounded-width add of at most 5 terms under 2^32, which double-precision `+` computes
exactly on both the JVM and V8, before a `mask32` truncation. The one place that
genuinely needs care is the padding's 64-bit big-endian length field: JS bitwise
operators are 32-bit only and silently mask any shift count to its low 5 bits (`x >>>
40` behaves as `x >>> 8`), so it's built from two 32-bit halves via `quot`/`mod` rather
than shifting past bit 31 -- see `sha256d.core/u64be-bytes` and its doc-comment for the
bug this repo actually hit and fixed (`test/sha256d/cljs_verify.cljs`, run under node,
is what caught it -- the JVM test suite alone did not, since `unsigned-bit-shift-right`
on a `long` is 64-bit-aware and masked the bug).

## Status / follow-ups

See `docs/evolution-log.md` for what the tournament has actually found so far: round 1
(2x2 gene pool) found no stable champion -- Ch/Maj formula choice was within benchmark
noise. Round 2 grew the pool to 3x3 (added `ch-or`/`maj-or`) and found a clearer signal:
the `*-naive` forms are consistently eliminated by generation 3 across repeated runs
(real evidence the one-fewer-gate `alt`/`or` forms measurably help), while `alt` vs `or`
remain a toss-up. The generation-over-generation diversity loss in `evolve-round`
(convergence to 2 candidates regardless of pool size) is still open. Growing the gene
pool further (loop-unrolling degree, schedule-buffer reuse, batch/lane-parallel
hashing) and running the tournament under node (not just JVM) remain open follow-ups.
