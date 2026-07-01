# Below-brute-force SHA-256 preimage, designed by a co-scientist search

This is the *inverse* problem: given a digest, find a preimage faster than the 2²⁵⁶ brute-force
bound. It is the honest realization of "design an algorithm that inverts SHA-256 below brute force,
using the co-scientist approach." Implemented in `sha256d.mitm`, tested in `test/sha256d/mitm_test.cljc`.

## The one honest boundary, stated once

- **Below-brute-force preimage algorithms are real** — the meet-in-the-middle (MITM) /
  splice-and-cut / biclique family (Aoki & Sasaki 2009; Khovratovich–Rechberger–Savelieva 2012).
- On **reduced rounds** they give large, genuine speedups (this repo's search reproduces them).
- On **full 64 rounds** the best published record is ≈ **2²⁵⁵·⁵** (biclique, ~45 rounds), a margin of
  ~0.5 bits — real but cryptographically meaningless. **No algorithm with a meaningful margin below
  2²⁵⁶ is known for full SHA-256, and none is believed to exist.** This search does not cross that
  wall; it *demonstrates* it. Anything claiming to break full SHA-256 would be fabrication.

## Why a preimage is a search over the message schedule (recap)

From `docs/…` / the round-15/16 analysis: undoing the Davies–Meyer feedforward is O(1) (the post-64
state is `digest ⊟ IV`), and each compression round is invertible *given* its schedule word `W_t`.
So a preimage is any solution of `F(W₀..₁₅) = IV`, where the 48 expanded words
`W_t = σ₁(W_{t-2}) ⊞ W_{t-7} ⊞ σ₀(W_{t-15}) ⊞ W_{t-16}` (t≥16) are determined by the 16 free words.
**The message expansion is the sole source of hardness** — without it, inversion is polynomial.

## The algorithm: meet-in-the-middle / splice-and-cut

Split the R-round cycle (closed by the feedforward) into two chunks A and B at a cut point.
Compute A forward from the (spliced) start and B backward from the target, and match in the middle.
The attack is below brute force when each chunk has **neutral message words** — base words in
`{W₀..W₁₅}` used by *one* chunk only, so they can be varied without disturbing the other:

- `N1 = used(A) \ used(B)` (neutral for B), `N2 = used(B) \ used(A)` (neutral for A);
  `d1 = 32·|N1|`, `d2 = 32·|N2|` bits of independent freedom per side.
- MITM finds `F1(x1) = F2(x2)` on the n=256-bit mid-state: build a 2^d1 table, probe with 2^d2,
  match on n. Cheapest balanced attack is 2^(n/2); with short freedom (`d1+d2 < n`) the shared bits
  are iterated, giving **2^(n − min(d1,d2))**. So the pseudo-preimage cost is
  `2^max(128, 256 − 32·min(|N1|,|N2|))` — below 2²⁵⁶ whenever both neutral sets are non-empty.
  (A pseudo-preimage converts to a full preimage with modest standard overhead.)

## The co-scientist search that *designs* it

The valuable, tractable problem is not running the attack (2¹²⁸ is infeasible) but **finding the
configuration that minimizes complexity** — exactly what automated cryptanalysis does. Mapped onto
the co-scientist agents (`sha256d.mitm`):

- **Generation** — enumerate splice-and-cut configs: every contiguous cyclic arc split `(start, len)`.
- **Reflection (hard gate)** — verify neutrality *exactly* against the schedule dependency graph
  (`schedule-deps`): a config is valid only if BOTH neutral sets are non-empty. A one-sided split is
  not a below-brute-force attack and is rejected — the analog of the forward work's bit-identical gate.
- **Ranking** — order valid configs by pseudo-preimage complexity (lower = better).
- **Meta-review** — report the best attack per round count, and the decay curve.

At word granularity the config space is small enough to search exhaustively (so it is — honesty:
the evolutionary/tournament machinery only becomes *necessary* at the finer bit-level/biclique
granularity, see below). The dependency graph is the crux: `schedule-deps` computes, for each round,
which base words its `W_t` transitively depends on.

## Measured result (this repo's search, word granularity)

`clojure -M -e "(require 'sha256d.mitm)(println (sha256d.mitm/report))"`

| rounds | best cost | saved bits | neutral \|N1\|/\|N2\| | below 2²⁵⁶? |
|---|---|---|---|---|
| 16 | 2¹²⁸ | 128 | 4/12 | yes |
| 18 | 2¹²⁸ | 128 | 4/9 | yes |
| 20 | 2¹²⁸ | 128 | 4/7 | yes |
| 22 | 2¹⁶⁰ | 96 | 3/3 | yes |
| 24 | 2²²⁴ | 32 | 2/1 | yes |
| 26+ | (none) | 0 | — | **no** |

So the search **designs genuine below-brute-force preimage attacks**: e.g. *20-round SHA-256 has a
2¹²⁸ preimage via a 4-word / 7-word neutral split* — a real ~2¹²⁸× speedup over brute force. And it
**locates the wall precisely**: past ~24 rounds the message-expansion fan-out makes every base word
feed both chunks (the dependency graph saturates to all 16 base words by round ~23), so no neutral
split exists and the word-granularity attack vanishes. This is the empirical signature of SHA-256's
preimage resistance — the same mechanism, message expansion, that makes forward inversion hard.

## Gap to the published record, and where co-scientist search would earn its keep

The literature reaches ~**45 rounds** (biclique, ≈2²⁵⁵·⁵) — further than this word-granularity search
(~24 rounds) — using finer tools this implementation does *not* include:

- **bit-level neutral bits** (not whole 32-bit words) — far more configurations, so the search space
  becomes intractable and a true evolutionary/tournament search (or MILP/SAT, as in the automated-
  cryptanalysis literature) is needed;
- **bicliques** (initial structures that manufacture extra rounds of independence);
- **probabilistic / partial matching** across the modular-addition mixing.

Extending `sha256d.mitm` to bit-level + biclique is the point where the full co-scientist machinery
(Generation of biclique dimensions, Reflection verifying the differential independence, Elo Ranking
by complexity, Evolution recombining neutral-bit sets) would be *required* rather than optional.
Even fully realized, that frontier ends at ~45 rounds / ≈2²⁵⁵·⁵. **Full 64-round SHA-256 stays
unbroken by any meaningful margin — which is the correct, honest answer to "invert it below brute force."**
