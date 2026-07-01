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

## 2026-07-01 (round 6) — the mutable-buffer test that was supposed to confirm the claim; it refuted it

Round 5 teed this up: build the C-style in-place mutable schedule and confirm rounds 4-5's
standing claim that *the only way to beat the persistent-vector precompute is to leave
persistent structures*.

- `sha256d.core/compress-mutable` (JVM-only, `#?(:clj ...)`): one 64-slot `long-array`
  (not `int-array` — SHA-256 words are unsigned 32-bit and don't fit a Java `int`), filled
  with `aset`, read with `aget` — **zero per-step allocation**, exactly the C approach.
  Excluded from the ClojureScript gene pool (portability preserved: cljs proof still 6/6).
  Proven bit-identical on the JVM across 260 sizes (`compress-mutable-equivalence-test`).
  JVM pool is now `:ch(3) x :maj(3) x :schedule(4) = 36`; 18 tests / 6007 assertions.

Three runs (top few + transient/rolling tails):

```
run 1: champ {alt,alt,:mutable} 45914 (elo 1270); {alt,naive,:mutable} 45848 (1260);
       {alt,alt,:precompute} 45836 (1061); ...; transient 46922 (967); rolling 48726 (919)
run 2: champ {alt,alt,:precompute} 45810 (elo 1290); {alt,naive,:mutable} 45983 (1288);
       {alt,alt,:mutable} 46064 (1226); ...; transient 46645 (1065); rolling 48765 (761)
run 3: champ {alt,alt,:precompute} 46295 (elo 1310); {alt,alt,:mutable} 46488 (1257);
       ...; transient 46900 (1015); rolling 49332 (928)
```

**Findings:**

15. **The claim is REFUTED: even a zero-allocation mutable `long-array` schedule does NOT
    beat the persistent-vector precompute.** `:mutable` and `:precompute` are statistically
    tied — they alternate the championship (mutable wins run 1, precompute wins runs 2-3),
    and all their top ns/hash values sit within ~0.5% (45.8-46.5k). So "leaving persistent
    structures" was NOT the missing lever; the schedule container and its allocation were
    never the bottleneck. (`:rolling` is dead-last for the *sixth* round running; `:precompute-
    transient` again mid-pack.)
16. **So the real bottleneck is the boxed arithmetic in the round function**, which every
    schedule strategy shares: `add32`'s `(apply + xs)` varargs boxing, bit-ops on boxed
    `Long`s, boxed `ch`/`maj` return values, and the `(K t)`/`(w t)` vector lookups — ~128
    rounds of it per 2-block hash, dwarfing the ~48-step schedule build that the schedule
    genes vary. That's why *every* schedule strategy ties or loses: they all pay the same
    dominant boxed-round cost. It also explains rolling's persistent last place cleanly —
    rolling doesn't reduce that cost, and it *adds* per-step `subvec`/`conj` allocation while
    interleaving the schedule into the round loop (defeating a tight, separable, JIT-friendly
    round pass). Mutable and precompute both keep the schedule build separate and the round
    loop tight, so they tie.
17. **This overturns the round-5 "meta" conclusion's framing.** Rounds 4-5 concluded the
    reference was "at the efficient frontier" and further wins needed non-`.cljc` mutable
    buffers. Round 6 shows the mutable buffer *doesn't* help — so the frontier isn't the data
    structure at all; it's the **unboxed-vs-boxed arithmetic** of the round function, a lever
    none of rounds 1-6 has touched (all variants reuse the same boxed `add32`/bit-ops).

**Clear next experiment (round 7):** a primitive/unboxed round function — `^long` type hints,
`unchecked-add`, a fixed-arity (non-varargs) add, primitive `ch`/`maj`, and avoiding the
boxed vector lookups. On the JVM this is the classic 2-5x Clojure numeric win and is the
first thing that should actually move ns/hash (and might finally make the Ch/Maj formula
differences visible above the noise, since boxing currently swamps them). `^long` hints are
valid `.cljc` (cljs ignores them), so a portable primitive round function is plausible —
that would be the first genuine, portable positive result if it lands.

## 2026-07-01 (round 7) — THE FIRST POSITIVE RESULT: unboxing the round loop is ~2.3x

Round 6's diagnosis (bottleneck = boxed round arithmetic, not the schedule) made a sharp,
falsifiable prediction. Round 7 tested it:

- `sha256d.core/compress-primitive` (JVM-only, `#?(:clj ...)`): the *same* mutable
  `long-array` schedule as `compress-mutable` (so the schedule is held constant vs round 6),
  but the 64-round loop is UNBOXED — `^long` loop locals, `unchecked-add`, primitive
  `rotr*`/`bsig0*`/`bsig1*` (return-hint on the arg vector — the first cut mis-placed it on
  the fn name and threw `AbstractMethodError: invokePrim(J)…is abstract`; fixed), and a
  primitive `long-array` K. Confirmed **zero reflection warnings** (genuinely unboxed) and
  bit-identical across 260 sizes AND all 9 ch/maj gene combinations. Still calls injected
  `ch-fn`/`maj-fn` (a residual boxing island), so it composes with the :ch/:maj genes.
  Excluded from the cljs pool; JVM pool now `:ch(3) x :maj(3) x :schedule(5) = 45`.
  18 tests / 6294 assertions; cljs proof still 6/6.

Three runs (champion + a boxed reference row + rolling tail):

```
run 1: champ {alt,alt,:primitive} 20692 ns/hash (elo 1370); ...all :primitive ~20.2-21.0k...
       {alt,alt,:mutable} 47123; {alt,alt,:precompute} 46741; {alt,alt,:rolling} 49835
run 2: champ {naive,alt,:primitive} 20074; ...:primitive ~20.1-20.6k...; :precompute 46637; :rolling 49572
run 3: champ {alt,alt,:primitive} 20450; ...:primitive ~20.0-20.4k...; :precompute 46893; :rolling 49464
```

**Findings:**

18. **First positive result, and it's decisive: unboxing the round loop is ~2.3x.** Every
    `:primitive` candidate lands at ~20-21k ns/hash; every boxed schedule variant
    (precompute/mutable/transient) at ~46.6-47.5k; rolling ~49.5k. That's 46.7k/20.4k ≈
    **2.29x**, consistent across all 3 runs, with a clean, huge Elo gap (primitives
    ~1090-1388, boxed ~815-1130). This exactly confirms round 6's prediction: `:mutable`
    (mutable schedule, *boxed* round) ties precompute at ~47k, and adding unboxing to that
    *same* schedule drops it to ~20k — so the lever was the round arithmetic's boxing, full
    stop, isolated cleanly because the schedule was held constant between :mutable and
    :primitive.
19. **Ch/Maj is STILL noise, even unboxed** (round 3 survives): among the primitives,
    `{:ch :naive}` even wins run 2, and all 9 primitive candidates sit within a few % of each
    other. Removing boxing did not make the formula choice matter — it genuinely doesn't for
    throughput (the round is dominated by the σ/add chain and memory, not the one ch/maj gate
    difference; and ch/maj here are still the residual boxed calls anyway).
20. **The win is JVM-only, not portable — stated honestly.** `compress-primitive` uses
    `long-array` + `^long` and is `#?(:clj ...)`; ClojureScript never sees it. On V8 there is
    no boxed-`Long` problem to fix (JS numbers are unboxed doubles natively), so this specific
    ~2.3x does not transfer, and the cljs performance question remains unmeasured. The
    portable reference `compress` stays the default; `compress-primitive` is an opt-in JVM
    fast path (`sha256-bytes-with compress-primitive …`).

**Meta (rounds 1-7):** the optimization arc is now essentially complete and, crucially,
*correctly ordered by the harness*: Ch/Maj formula (rounds 1-3) = noise; schedule data
structure (rounds 4-6, incl. a zero-alloc mutable buffer) = no help / rolling hurts; unboxed
round arithmetic (round 7) = the one real lever, ~2.3x on the JVM. The co-scientist loop
rejected four plausible dead-ends and, by elimination, drove straight to the actual
bottleneck — which is exactly what a diagnosis-by-tournament is supposed to do. The single
biggest correctness catch along the way (the JS shift-count bug, round 3) and the biggest
speed lever (round 7) both came from *taking the mechanism seriously*, not from guessing.

**Still open:** whether *any* restructuring helps on cljs/V8 (needs a node benchmark harness —
the tournament has only ever been run on the JVM); fully inlining ch/maj to remove the last
boxing island (likely small, given finding 19); and batch/lane-parallel (multi-message)
hashing, which needs real SIMD (JVM Vector API / WASM SIMD) to beat the reference and is
therefore also non-portable.
