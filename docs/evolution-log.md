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
