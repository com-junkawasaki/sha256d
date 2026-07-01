# Evolution log

Append-only. Each entry is the Meta-review output of one `(sha256d.evolve/run-tournament)`
call (or `clojure -M:evolve`), left mostly unedited so this log reflects what the harness
actually measured, not a cleaned-up narrative.

## 2026-07-01 — initial run, JVM (OpenJDK 24, Temurin), Apple Silicon

Three consecutive `clojure -M:evolve` runs (default settings: 3 generations, elite-n 2,
200 iters x 7 reps per benchmark) over the current 2x2 gene pool (`sha256d.ops/ch-naive`
vs `ch-alt`, `maj-naive` vs `maj-alt`):

```
run 1: champion {:ch :alt,   :maj :naive} (51748.75 ns/hash) -- clusters: [1, 1] (NOT tied)
run 2: champion {:ch :alt,   :maj :alt}   (61070.84 ns/hash) -- clusters: [2]    (tied)
run 3: champion {:ch :naive, :maj :alt}   (61386.67 ns/hash) -- clusters: [2]    (tied)
```

**Findings:**

1. **No stable champion.** The "winner" changes between runs and 2 of 3 runs land in a
   single proximity cluster (i.e. Ranking itself found no significant difference). At
   this payload size, on this JIT, `ch-alt`/`maj-alt`'s one-fewer-gate savings are
   *below the noise floor* of a wall-clock micro-benchmark that also pays for the full
   padding/schedule/compression pipeline around the primitive call. This is a real,
   useful negative result, not a bug: it says the Ch/Maj formula choice alone isn't
   where meaningful throughput is hiding for this repo's current gene pool -- the
   Bitcoin midstate optimization (sha256d.midstate, ~2x fewer compression rounds per
   mining attempt) is a structural win of a completely different order, and the next
   genes worth adding (loop unrolling degree, schedule-buffer reuse, batch/lane-
   parallel hashing) are likely to matter more than further round-primitive rewrites.
2. **Generation-over-generation diversity loss.** By generation 3 the leaderboard only
   ever contains 2 distinct candidates, both sharing one gene value (e.g. both
   `:maj :naive`, or both `:maj :alt`). `evolve-round`'s recombination step only
   recombines gene *values already present among the current elites* -- once both
   elites happen to agree on a gene (a coin-flip after generation 1, given the Ranking
   noise above), that gene's other variant is gone for the rest of the run. There's no
   mutation/reintroduction step, so this is effectively genetic drift with no
   selective pressure behind it (since the "selection" driving convergence is largely
   benchmark noise per finding 1). **Follow-up:** either add a small random-
   reintroduction step to `evolve-round`, or don't treat convergence as meaningful
   until the gene pool is large enough / the payload is large enough that Ranking's
   pairwise comparisons are reliably outside the 1% proximity tolerance.

**Not yet done (explicitly out of scope for this initial pass, left for follow-up):**
ClojureScript/node benchmarking (only JVM was measured here -- `now-ns`'s cljs branch
via `js/performance.now` is implemented and the *correctness* of core/ops/midstate is
proven under cljs in `test/sha256d/cljs_verify.cljs`, but the evolve tournament itself
has only been run on the JVM so far); growing the gene pool beyond Ch/Maj.

## 2026-07-01 (round 2) — grew the gene pool to 3x3, JVM only

Added `sha256d.ops/ch-or` and `maj-or`: the same pairwise terms as `ch-naive`/
`maj-naive` but OR'd instead of XOR'd, valid because the terms being combined are
pairwise-disjoint (Ch) or never exactly-two-1 (Maj) -- see their doc-comments for the
proofs, and `test/sha256d/ops_test.cljc`'s exhaustive truth-table + randomized checks.
Gene pool is now 3x3 = 9 candidates. `clojure -M:test` (14 tests, 5154 assertions) and
the cljs proof (5/5) both still pass.

Three more `clojure -M:evolve` runs, same defaults:

```
run 1: champion {:ch :alt, :maj :alt} (51511.88 ns/hash) -- runner-up {:ch :alt, :maj :or}
run 2: champion {:ch :or,  :maj :alt} (45749.79 ns/hash) -- runner-up {:ch :alt, :maj :alt}
run 3: champion {:ch :alt, :maj :alt} (46046.88 ns/hash) -- runner-up {:ch :alt, :maj :or}
```

**Findings:**

3. **[⚠ LARGELY SUPERSEDED by round 3, finding 6 — this "signal" turned out to be
   substantially an artifact of the diversity-loss bug in finding 5, not a real speed
   difference.]** The `*-naive` variants are consistently eliminated by generation 3, in
   all 3 runs, for both genes. Unlike round 1's inconclusive Ch-alt-vs-naive comparison (which was
   only ever a 2-way fight), with `or` in the pool as a second same-cost-as-`alt`
   competitor, `naive` (one more gate than either) loses often enough in early-generation
   pairwise comparisons that `evolve-round`'s elitism drops it for good. This is
   reasonably strong evidence -- across 3 independent runs, not just one -- that the
   well-known "one-fewer-gate" reformulation is a real, measurable win here, not just
   noise as round 1's smaller pool made it look. It's still not a *novel* discovery
   (`ch-alt`/`maj-alt` are already how OpenSSL/Bitcoin Core write this), but it's the
   tournament's first result where Ranking's signal clearly exceeds its own noise floor.
4. **`alt` vs `or` (same gate count, different data-dependency shape) remain a genuine
   toss-up**: the champion alternates between them across runs, and each run's top-2
   still lands in 2 separate (not tied) clusters rather than 1 -- so there IS a
   measurable gap between whichever wins and whichever loses each specific run, it just
   isn't the *same* one twice. Distinguishing `alt` from `or` reliably would need either
   a larger reps/iters budget or a payload where the primitive call is a bigger fraction
   of total work.
5. **Diversity loss (finding 2, round 1) still reproduces**: every run's final
   leaderboard still has exactly 2 entries, not 9 or even 3+ -- confirms it's a property
   of `evolve-round`'s recombination-among-elites-only design, not something the larger
   pool alone fixes. Still an open follow-up.

**Still not done:** node/cljs benchmarking of the tournament itself; the diversity-loss
fix; genes beyond Ch/Maj (loop-unrolling degree, schedule-buffer reuse, batch/lane-
parallel hashing).

## 2026-07-01 (round 3) — fixed the harness, and it dissolved round 2's "finding"

Fixed the two flaws the earlier rounds documented, in `sha256d.evolve`:

- **Mutation (fixes finding 5, the premature convergence):** `evolve-round` now, in
  addition to elitism + crossover, reintroduces every pool variant the elites have
  dropped (grafted onto the top elite). The population no longer collapses to 2 -- it's
  a stable 5 every run now (`population size (diversity): 5` in all 3 runs below).
- **Persistent Elo (makes generations cumulative):** `rank` seeds each generation's Elo
  from the previous generation's ratings (newcomers at 1000) instead of resetting to
  1000 every round, so 3 generations over a stable diverse field pool ~3x as many
  pairwise games into each rating. Elo spread widened from the old artificial ±16 to
  ~120 points (≈971–1090), i.e. the ratings now carry real accumulated evidence.

`clojure -M:test` (16 tests, 5167 assertions, incl. new `evolve-round-mutation-test` and
`rank-persistent-ratings-test`) and the cljs proof (5/5) both pass. Three runs:

```
run 1: champion {:ch :naive, :maj :alt} (45442 ns/hash), pop 5, clusters [1,1,3]
run 2: champion {:ch :alt,   :maj :alt} (45845 ns/hash), pop 5, clusters [1,2,1,1]
run 3: champion {:ch :alt,   :maj :alt} (46471 ns/hash), pop 5, clusters [3,1,1]
```

**Findings:**

6. **Fixing the harness dissolved round 2's headline result — an important, humbling
   meta-finding.** Round 2 (finding 3) reported that the `*-naive` forms were
   "consistently eliminated," read as real evidence the one-fewer-gate `alt`/`or` forms
   are faster. Round 3 shows that was **largely an artifact of the diversity-loss bug
   itself**: round 2's `evolve-round` *dropped* `naive` from the population early (on
   noise) and then never re-benchmarked it, so of course it never appeared in the final
   leaderboard — that's not the same as `naive` losing on speed. With mutation now
   keeping `naive` in the field and re-measured every generation, **`{:ch :naive, :maj
   :alt}` actually wins run 1 outright.** All candidates now sit within ~3% of each other
   (45.4k–47.0k ns/hash) with heavy proximity-clustering (ties). So the honest verdict
   reverts to round 1's: at this payload and measurement budget, the Ch/Maj *formula*
   choice is within noise. The lesson is the general one — don't trust a search harness's
   "discoveries" until its own convergence behavior is sound; a premature-convergence bug
   manufactures crisp-looking signals out of noise.
7. **The only weak surviving lean:** `:maj :alt` is the champion's Maj in all 3 runs, and
   `:ch :alt` is in the top two in all 3. It's suggestive but NOT decisive — the gaps are
   inside the proximity tolerance and `:ch :naive` still won once. Not claimed as a result.
8. **Diversity fix confirmed end-to-end** (finding 5 closed): population is a stable 5,
   and `run-tournament-smoke-test` now asserts `population-size >= 3` so a regression back
   to collapse would fail CI.

**Still not done:** node/cljs benchmarking of the tournament itself (evolve.cljc's cljs
branches compile but the loop has still only been *run* on the JVM); a payload/'budget
where the primitive is a bigger fraction of total work, to actually resolve alt-vs-or if
it's resolvable at all; genes beyond Ch/Maj (loop-unrolling degree, schedule-buffer
reuse, batch/lane-parallel hashing) — the real efficiency frontier, per round 1 finding 1.

## 2026-07-01 (round 4) — added a real implementation-strategy gene; got the first signal that clears the noise

Acting on round 3's conclusion (the efficiency frontier is *structural*, not the
round-primitive formula), added the first non-formula gene: `:schedule`, selecting the
message-schedule strategy.

- `sha256d.core/compress-rolling`: computes the 64-word schedule in a 16-word rolling
  window just-in-time inside the round loop, instead of `compress`'s full-precompute
  `extend-schedule` — the "schedule-buffer reuse" idea. Proven bit-identical to the
  reference across 260 input sizes + FIPS vectors (`compress-rolling-equivalence-test`)
  and under cljs.
- Pool is now `:ch (3) x :maj (3) x :schedule (2) = 18` candidates; `rank`/`reflect`
  route through the new injectable `sha256-bytes-with`. `clojure -M:test` (17 tests,
  5449 assertions) and the cljs proof (6/6) pass.

Three runs (leaderboards trimmed to top + the surviving `:rolling` entry):

```
run 1: champion {:ch :or, :maj :or,    :schedule :precompute} 45948 ns/hash, elo 1222
       ...(4 more :precompute, elo 1102-1196)...
       LAST {:ch :or, :maj :or,  :schedule :rolling}  49351 ns/hash, elo 927
run 2: champion {:ch :or, :maj :naive, :schedule :precompute} 46489 ns/hash, elo 1165
       LAST {:ch :or, :maj :alt, :schedule :rolling}  50031 ns/hash, elo 946
run 3: champion {:ch :or, :maj :naive, :schedule :precompute} 45325 ns/hash, elo 1170
       LAST {:ch :or, :maj :alt, :schedule :rolling}  48946 ns/hash, elo 943
```

**Findings:**

9. **First signal that clearly exceeds the noise floor — and it's a negative result about
   the optimization I just added.** In all 3 runs `:schedule :precompute` wins and the
   sole surviving `:rolling` candidate is *last*, in its own low-Elo cluster (~927-946,
   well below the ~1100-1220 precompute pack), consistently ~7-10% slower in ns/hash
   (~49-50k vs ~45-47k). Unlike Ch/Maj (rounds 1-3, all within ~3%/tolerance), this gap
   is stable across runs and outside the proximity tolerance. The persistent-Elo spread
   widened to ~295 points (vs round 3's ~120 on pure noise), i.e. the round-3 harness
   correctly *amplifies* a real signal — good confirmation it now separates signal from
   noise rather than manufacturing it.
10. **Why: the C-level "schedule-buffer reuse" win does NOT transfer to idiomatic
    persistent-vector Clojure.** In C, rolling uses an in-place 16-word `int[]` with zero
    allocation, beating a 64-word buffer. Here, sliding the window with
    `(conj (subvec win 1 16) wt)` allocates a new (sub)vector every one of the 48
    extension steps per block — *more* churn than `extend-schedule`'s single flat 64-word
    vector that the JIT indexes cheaply with `(w t)`. So the "optimization" is a
    pessimization on this platform. A mutable `int-array` + `aset` rolling window would
    likely win on the JVM (it's how OpenSSL does it) but breaks `.cljc` portability
    (JVM/JS array semantics diverge) — deliberately not done; noted as a platform-specific
    follow-up, not a portable gene.
11. **Within `:precompute`, Ch/Maj is still noise** (consistent with round 3): `:ch :or`
    happens to top all 3 runs but `:maj` alternates (`:or`/`:naive`/`:naive`) and the
    intra-precompute gaps sit inside tolerance. Not claimed as a result.

**Net so far:** three rounds of Ch/Maj formula search found nothing above noise; the first
implementation-strategy gene immediately produced a clear (negative) signal. That is
itself the headline — *for this workload the lever is allocation/implementation strategy,
not bit-level formula* — and it re-confirms round 3's thesis. The genuine wins remain
structural and already in the repo (`sha256d.midstate`'s ~2x fewer compression rounds per
mining nonce), or would require a mutable-buffer, platform-specific (non-`.cljc`) rolling
schedule that this repo's portability constraint rules out.

**Still not done:** node/cljs *benchmarking* of the tournament (correctness under cljs is
proven; speed there — where V8's allocation behavior differs and rolling might fare
differently — is not measured); a mutable-array JVM-only schedule as a separate,
explicitly-non-portable experiment; genes for batch/lane-parallel hashing.

## 2026-07-01 (round 5) — tested round 4's "it's the allocation" hypothesis; it failed

Round 4 concluded rolling loses *because* per-step `subvec`/`conj` allocates. That's a
causal claim, and the portable, idiomatic way to test it is transients — the standard
Clojure tool for cutting allocation without leaving persistent-data-structure land. So:

- `sha256d.core/extend-schedule-transient` + `compress-transient`: same full 64-word
  precompute as `compress`, but the schedule is grown in a transient vector (`conj!`,
  one `persistent!` before `run-rounds` reads it). Extracted the shared 64-round body
  into a private `run-rounds` so `compress`/`compress-transient` differ *only* in how the
  schedule is built. Proven bit-identical across 260 sizes + FIPS vectors, **including a
  cljs check that transient-vector `nth` reads work under ClojureScript** (they do — the
  round-3 "verify portability under node" reflex paid off again; this could have been a
  cljs-only break and wasn't). Pool is now `:ch(3) x :maj(3) x :schedule(3) = 27`.
  17 tests / 5729 assertions JVM + 6/6 cljs green.

Three runs (top of each leaderboard + the transient and rolling entries):

```
run 1: champ {:ch :or,  :maj :alt, :precompute} 45659 (elo 1264); transient(or,alt) 46343 (elo 1017); rolling 49786 (elo 929)
run 2: champ {:ch :or,  :maj :alt, :precompute} 46175 (elo 1242); transient(or,naive) 46961 (elo 999); rolling 50048 (elo 939)
run 3: champ {:ch :alt, :maj :alt, :precompute} 45561 (elo 1259); transient(alt,alt) 49020 (elo 1069); rolling 50255 (elo 845)
```

**Findings:**

12. **Hypothesis NOT supported: cutting schedule-build allocation (transient) did not
    speed anything up.** A plain-`:precompute` candidate takes every champion slot across
    all 3 runs; `:precompute-transient` never wins — it lands in the same noise band as
    plain precompute, and in run 3 it's clearly *slower* (49020 vs the precompute pack's
    45.4–47.8k). So reducing the persistent-`conj` allocation of the schedule build is a
    wash-to-slightly-negative, not the hoped-for first positive result. Likely because
    (a) `conj` onto a <64-element persistent vector is already cheap (small tries, JIT
    escape-analysis), so there's little to save, and (b) `transient`/`persistent!` + the
    transient `nth` path carry their own fixed overhead that 48 steps don't amortize.
13. **This refines round 4's finding 10.** Rolling's ~7-10% penalty (re-confirmed here —
    rolling is dead-last again in all 3 runs) is therefore *not* simply "allocation," or
    the transient precompute would have helped. It's more specifically rolling's per-step
    `subvec`-view-plus-`conj` (a larger, differently-shaped allocation than one trie node)
    together with interleaving the schedule into the round loop, which stops the JIT from
    optimizing a tight separable schedule pass. Cutting *precompute's* allocation, by
    contrast, changes nothing measurable.
14. **Conclusion for the schedule axis (3 strategies now tested):** plain `compress`
    (persistent-vector precompute) is the best portable schedule strategy; both
    alternatives are equal-or-worse. The schedule build/representation is a dead end for a
    *portable* speedup — the reference was already near-optimal. Efficiency wins remain
    structural (`sha256d.midstate`) or non-portable (mutable `int-array`, out of scope).

**Meta (rounds 1-5):** five rounds, zero portable speedups found — every Ch/Maj formula is
within noise, and both schedule alternatives are ties-or-losses. That is itself the honest
result: **in portable persistent-Clojure the reference implementation is already at the
efficient frontier for a single-stream hash; the only real levers are algorithmic/structural
(midstate, already in the repo) or require abandoning `.cljc` (mutable buffers, SIMD/lane
parallelism).** The co-scientist harness earned its keep less by finding a faster SHA-256
than by *rejecting* three plausible "optimizations" (naive-elimination, rolling, transient)
that don't survive an honest, convergence-sound tournament.

**Still not done:** node/cljs *benchmarking* (V8 allocation differs — transient/rolling
might rank differently there); a deliberately non-`.cljc` JVM-only mutable-`int-array`
schedule to confirm the "leaving persistent structures is the only way" claim; a
batch/lane-parallel (multi-message) gene, the one axis that could plausibly beat the
reference and hasn't been tried.
