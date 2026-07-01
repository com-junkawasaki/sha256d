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
  Five compression strategies, all bit-identical and injectable via `sha256-bytes-with`:
  `compress` (full 64-word precompute), `compress-rolling` (16-word window),
  `compress-transient` (transient-built precompute), and JVM-only `compress-mutable`
  (in-place `long-array`) and `compress-primitive` (in-place `long-array` **plus an
  unboxed round loop** — the ~2.3x JVM fast path).
- **`sha256d.ops`** -- the "gene pool", now `:ch (3) x :maj (3) x :schedule (5 on the JVM,
  3 on cljs) = 45 candidates (27 on cljs)`. The Ch/Maj variants are `*-naive` (FIPS textbook), `*-alt` (the
  OpenSSL/Bitcoin Core one-fewer-gate formulation), and `*-or` (the same pairwise terms
  as `*-naive`, OR'd instead of XOR'd -- valid because those terms are pairwise-disjoint
  / never-exactly-two-1), each proven algebraically equivalent to the FIPS textbook form
  in a doc-comment and re-checked exhaustively (all single-bit truth-table rows +
  randomized 32-bit words) in `test/sha256d/ops_test.cljc`. The `:schedule` gene is an
  implementation-strategy axis rather than a per-bit formula (`:precompute`, `:rolling`,
  `:precompute-transient`, and JVM-only `:mutable` and `:primitive`).
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

;; JVM-only ~2.3x fast path (unboxed round loop); identical output. Clojure only.
(sha256d/sha256-bytes-with sha256d/compress-primitive (sha256d/str->bytes "abc")
                           sha256d/ch sha256d/maj)
```

```clojure
(require '[sha256d.midstate :as midstate])

;; header is a vector of 80 byte-values (version|prevhash|merkleroot|time|bits|nonce)
(let [mid (midstate/midstate header)]
  ;; re-run this per nonce attempt against the same header prefix -- `mid` is computed once
  (midstate/header-hash mid (subvec header 64 80)))
```

```bash
clojure -M:test               # correctness suite (JVM) -- 6000+ assertions
clojure -M:cljs && node target/cljs-verify.js        # correctness portability proof (node)
clojure -M:evolve             # run the tournament on the JVM, print a Meta-review report
clojure -M:cljs-bench && node target/cljs-bench.js   # run the same tournament on V8 (node)
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

See `docs/evolution-log.md` for what the tournament has actually found, kept deliberately
honest across rounds:

- **Round 1** (2x2 pool): no stable champion — Ch/Maj formula choice within benchmark noise.
- **Round 2** (grew to 3x3 with `ch-or`/`maj-or`): *appeared* to find that the `*-naive`
  forms get eliminated — but see round 3.
- **Round 3** (fixed the harness): added a **mutation** step so the population stops
  prematurely collapsing to 2 candidates, and made **Elo persist across generations** so
  the rounds accumulate evidence. Doing so **dissolved round 2's result** — that
  "elimination" was largely an artifact of the diversity-loss bug (a dropped candidate
  simply stopped being benchmarked); with `naive` kept in the field it actually wins one
  run. Honest verdict: at this payload/budget the Ch/Maj *formula* choice is within noise.
- **Round 4** (first implementation-strategy gene): added `:schedule`
  (`compress` precompute vs `compress-rolling` 16-word window). First signal clearly above
  the noise floor — but **negative**: `:rolling` is consistently ~7-10% *slower* on the
  JVM and always ranks last. The C-level "schedule-buffer reuse" win doesn't transfer to
  idiomatic persistent-vector Clojure (window-sliding via `conj`/`subvec` allocates).
- **Round 5** (tested round 4's causal claim): added `:precompute-transient`
  (`compress-transient`, schedule built in a transient vector) to test whether *cutting
  allocation* is the lever. It isn't — transient precompute is within noise of plain
  precompute and never wins.
- **Round 6** (the decisive mutable-buffer test): added JVM-only `:mutable`
  (`compress-mutable`, a C-style in-place `long-array` schedule with **zero per-step
  allocation**). It was supposed to confirm "you must leave persistent structures to win" —
  instead it **refuted** it: `:mutable` and `:precompute` are statistically tied (they
  alternate the championship, all within ~0.5%). So the schedule container was never the
  bottleneck. The real cost is the **boxed arithmetic in the round function** (varargs
  `add32`, boxed bit-ops/`ch`/`maj`, vector `(K t)`/`(w t)` lookups) — shared by every
  schedule strategy, which is why they all tie or lose. `:rolling` is dead-last for the 6th
  round running (it adds allocation + interleaving without touching the boxing).

- **Round 7** (the payoff): added JVM-only `compress-primitive` — the *same* mutable
  `long-array` schedule as round 6, but with the 64-round loop **unboxed** (`^long` locals,
  `unchecked-add`, primitive rotate/σ, `long-array` K). This is the **first positive result,
  and a big one: ~2.3x faster** (~20k vs ~47k ns/hash), consistent across runs. It confirms
  round 6's diagnosis exactly — holding the schedule constant, unboxing the round arithmetic
  is the entire win — and Ch/Maj is *still* noise even unboxed (round 3 survives). The win is
  **JVM-only** (V8 has no boxed-`Long` problem); the portable reference stays the default.

- **Round 8** (cross-platform validation): ran the *same* tournament under ClojureScript/node
  for the first time. The portable verdicts **reproduce on V8** — precompute best, transient
  worse, rolling last, Ch/Maj noise — so they weren't JVM-JIT artifacts. V8 is ~3x slower in
  absolute terms (~135k ns/hash) and has no analog to the JVM-only `:primitive` win (JS numbers
  are unboxed doubles; nothing for `^long` to fix), correctly confirming that win as JVM-only.

**Net (rounds 1-8):** the harness ordered the whole search space by elimination —
Ch/Maj formula (noise) → schedule data structure (no help; rolling hurts) → **unboxed round
arithmetic (~2.3x, the one real lever, JVM-only)** — rejecting four dead-ends before
triangulating the bottleneck, then confirming the *portable* verdicts on both the JVM and V8.

Open follow-ups (all non-portable or infrastructural): fully inlining ch/maj to kill the last
boxing island (likely small); a V8-specific `Int32Array`+`>>> 0` fast path (the cljs analog of
the JVM `long-array` path); and a SIMD batch-of-N-messages hasher (JVM Vector API / WASM SIMD) —
the only path that could beat the reference, for the mining use case.
