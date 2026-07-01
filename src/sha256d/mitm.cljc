(ns sha256d.mitm
  "Meet-in-the-middle / splice-and-cut preimage ATTACK-PARAMETER search for reduced-round
  SHA-256 -- the real, published family of BELOW-BRUTE-FORCE preimage algorithms
  (Aoki & Sasaki 2009; extended by the biclique technique, Khovratovich-Rechberger-
  Savelieva 2012). Run via the co-scientist shape: Generation enumerates cut/arc configs,
  Reflection is a HARD gate verifying neutrality against the message-schedule dependency
  graph, Ranking orders by attack complexity, Meta-review reports the best below-brute-force
  attack per round count.

  IMPORTANT / HONEST SCOPE. This does NOT break full 64-round SHA-256 and cannot: it searches
  the attack-configuration space and *computes the resulting complexity* (cryptanalysis papers
  report complexity; they do not run a 2^128 attack either). On reduced rounds it yields
  genuine below-2^256 preimage attacks. As rounds grow, the message expansion's dependency
  fan-out collapses the neutral sets so the savings go to zero -- which is exactly WHY full
  SHA-256 has no known meaningful-margin preimage attack. The search demonstrates the wall
  rather than crossing it.

  The core object is the message-schedule dependency graph: which of the 16 free base words
  W0..W15 each expanded word W_t (t>=16) transitively depends on, via
  W_t = sigma1(W_{t-2}) + W_{t-7} + sigma0(W_{t-15}) + W_{t-16}."
  (:require [clojure.set :as set]))

(def n-bits 256)   ; digest / internal-state size
(def word-bits 32)

(defn schedule-deps
  "Vector of length `r`: entry t is the set of base message-word indices {0..15} that round
  t's schedule word W_t depends on. t<16 -> {t}; t>=16 -> union of the four feedback taps."
  [r]
  (loop [t 0 deps []]
    (if (= t r)
      deps
      (recur (inc t)
             (conj deps
                   (if (< t 16)
                     #{t}
                     (reduce into #{}
                             (map deps [(- t 2) (- t 7) (- t 15) (- t 16)]))))))))

(defn- arc
  "The `len` round indices starting at `start`, taken cyclically over `r` rounds. Splice-and-cut
  views the R rounds as a cycle (closed by the Davies-Meyer feedforward), so each of the two
  MITM chunks is one contiguous arc of that cycle."
  [r start len]
  (mapv #(mod % r) (range start (+ start len))))

(defn config
  "Evaluate one splice-and-cut split: chunk A = the given `a-rounds`, chunk B = the rest.
  Neutral words for B are those used only by A (N1 = used(A) \\ used(B)); neutral for A are
  N2 = used(B) \\ used(A). Words used by both must stay fixed. Returns the config with its
  neutral-set sizes and the MITM complexity."
  [deps r a-rounds]
  (let [a  (set a-rounds)
        b  (set/difference (set (range r)) a)
        ua (reduce into #{} (map deps a))
        ub (reduce into #{} (map deps b))
        n1 (set/difference ua ub)
        n2 (set/difference ub ua)
        d1 (* word-bits (count n1))          ; neutral freedom, bits, each side
        d2 (* word-bits (count n2))
        ;; MITM finds F1(x1)=F2(x2) on n bits: build 2^d1 table, probe 2^d2, match on n.
        ;; A match needs d1+d2 >= n; the cheapest balanced attack is 2^(n/2). When the neutral
        ;; freedom is short (d1+d2 < n) the shared bits are iterated, giving 2^(n - min(d1,d2)).
        pseudo-log2 (max (quot n-bits 2)
                         (- n-bits (min d1 d2)))]
    {:a-start (first a-rounds) :a-len (count a-rounds)
     :n1 (count n1) :n2 (count n2)
     :saved-bits (- n-bits pseudo-log2)          ; bits below brute force (pseudo-preimage)
     :pseudo-log2 pseudo-log2
     :below-brute? (< pseudo-log2 n-bits)}))

(defn best-attack
  "Generation + Reflection + Ranking: over every contiguous cyclic arc split of the R-round
  cycle, keep only configs whose BOTH neutral sets are non-empty (Reflection's hard gate --
  a MITM with a one-sided empty neutral set is not a valid below-brute-force attack), and
  return the one with the lowest pseudo-preimage complexity. nil if none is below brute force."
  [r]
  (let [deps (schedule-deps r)]
    (->> (for [start (range r), len (range 1 r)]
           (config deps r (arc r start len)))
         (filter #(and (pos? (:n1 %)) (pos? (:n2 %))))    ; Reflection: both sides neutral
         (sort-by :pseudo-log2)
         first)))

(defn attack-table
  "Meta-review: best below-brute-force MITM/splice-and-cut attack for each round count in `rs`.
  Each row: {:rounds R :pseudo-log2 L :saved-bits (256-L) :n1 .. :n2 ..} or {:rounds R :none? true}."
  [rs]
  (mapv (fn [r]
          (if-let [a (best-attack r)]
            {:rounds r :pseudo-log2 (:pseudo-log2 a) :saved-bits (:saved-bits a)
             :n1 (:n1 a) :n2 (:n2 a)}
            {:rounds r :none? true}))
        rs))

(defn report
  "Human-readable Meta-review of the attack search over round counts `rs` (default 16..64/8)."
  ([] (report (range 16 65 4)))
  ([rs]
   (str "# MITM / splice-and-cut preimage attack search (word-granularity)\n\n"
        "brute force = 2^256. 'pseudo-preimage' cost is the MITM complexity; lower = better attack.\n\n"
        "| rounds | best cost | saved bits | neutral |N1|/|N2| | below 2^256? |\n"
        "|---|---|---|---|---|\n"
        (apply str
          (for [{:keys [rounds pseudo-log2 saved-bits n1 n2 none?]} (attack-table rs)]
            (if none?
              (str "| " rounds " | (none) | 0 | - | NO |\n")
              (str "| " rounds " | 2^" pseudo-log2 " | " saved-bits " | " n1 "/" n2 " | "
                   (if (< pseudo-log2 n-bits) "yes" "NO") " |\n")))))))

(defn -main [& _] (println (report)))
