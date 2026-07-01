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

3. **The `*-naive` variants are consistently eliminated by generation 3, in all 3 runs,
   for both genes.** Unlike round 1's inconclusive Ch-alt-vs-naive comparison (which was
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
