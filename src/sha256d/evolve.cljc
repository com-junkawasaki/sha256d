(ns sha256d.evolve
  "Self-contained, deterministic evolutionary search over sha256d.ops' gene pool,
  shaped like Google's AI co-scientist / AlphaEvolve loop (Generation / Reflection /
  Ranking / Evolution / Proximity / Meta-review under a Supervisor) but scoped to a
  finite, closed combinatorial search over hand-verified formula variants -- no LLM
  in the loop, and no risk of an 'almost right' result surviving, because SHA-256's
  correctness gate is absolute: a hash that's merely *close* to the reference isn't a
  lower-scoring candidate the way a slower matrix-multiplication algorithm would be in
  AlphaEvolve's search -- it's simply not SHA-256. Reflection below is therefore a
  hard pass/fail filter applied before any benchmarking, never one term in a score.

  As sha256d.ops grows more genes (loop-unrolling degree, schedule-buffer reuse,
  batch/lane-parallel hashing, ...) this search space grows with it; today it's
  intentionally small (2 primitives x 2 variants each = 4 candidates) and mostly
  demonstrates the shape of the loop rather than a big discovery.

  Entry point: `(run-tournament)` for defaults, or `clojure -M -m sha256d.evolve`."
  (:require [clojure.string :as str]
            [sha256d.core :as core]
            [sha256d.ops :as ops]))

;; --- Generation ---------------------------------------------------------------------

(defn generate-candidates
  "Every combination of gene choices in `pool` (default sha256d.ops/gene-pool), e.g.
  {:ch :naive, :maj :alt}."
  ([] (generate-candidates ops/gene-pool))
  ([pool]
   (reduce (fn [candidates gene]
             (for [c candidates [variant _] (get pool gene)]
               (assoc c gene variant)))
           [{}]
           (keys pool))))

(defn candidate->fns
  "{:ch fn :maj fn ...} for a {:ch :naive :maj :alt ...} candidate."
  [pool candidate]
  (reduce-kv (fn [m gene variant] (assoc m gene (get-in pool [gene variant]))) {} candidate))

;; --- Reflection: hard correctness gate ----------------------------------------------

(def reflection-messages
  "Representative inputs covering the padding boundaries a wrong candidate is most
  likely to trip on: empty, short, mid-block, exactly one block, spanning two blocks,
  and a longer multi-block message."
  (into [[] (core/str->bytes "abc")]
        (map #(vec (repeatedly % (fn [] (rand-int 256)))) [55 56 64 65 128 200])))

(defn reflect
  "true iff `candidate`'s sha256-bytes matches the reference sha256d.core/sha256-bytes
  on every reflection message. Candidates failing this are disqualified outright, not
  merely down-scored -- ranking below never sees them."
  [pool candidate]
  (let [{:keys [ch maj]} (candidate->fns pool candidate)]
    (every? #(= (core/sha256-bytes %) (core/sha256-bytes % ch maj)) reflection-messages)))

;; --- Ranking: pairwise benchmark tournament with Elo-style updates -----------------

(defn- now-ns [] #?(:clj (System/nanoTime) :cljs (* 1e6 (js/performance.now))))
(defn- pow [base exp] #?(:clj (Math/pow base exp) :cljs (js/Math.pow base exp)))
(defn- abs-val [x] (if (neg? x) (- x) x))
(defn- median [xs] (nth (sort xs) (quot (count xs) 2)))

(defn bench-ns-per-hash
  "Median wall-clock ns/hash for `hash-fn` over `payload`, across `reps` timed runs of
  `iters` hashes each -- median-of-batches damps GC/JIT-warmup outliers better than one
  long run or a mean."
  [hash-fn payload {:keys [iters reps] :or {iters 200 reps 7}}]
  (median
   (for [_ (range reps)]
     (let [t0 (now-ns)]
       (dotimes [_ iters] (hash-fn payload))
       (/ (- (now-ns) t0) (double iters))))))

(defn elo-update
  "Standard Elo pairwise update (as used by co-scientist's Ranking agent tournament-
  of-ideas): `score-a` is 1.0/0.5/0.0 for a-wins/draw/b-wins."
  [ra rb score-a & {:keys [k] :or {k 32}}]
  (let [expected-a (/ 1.0 (+ 1.0 (pow 10 (/ (- rb ra) 400.0))))]
    [(+ ra (* k (- score-a expected-a)))
     (+ rb (* k (- (- 1.0 score-a) (- 1.0 expected-a))))]))

(defn rank
  "Benchmarks every surviving candidate, then pairwise-compares ns/hash (faster wins,
  within 1% counts as a draw to avoid over-claiming a winner from measurement noise)
  and feeds each comparison into an Elo update starting at rating 1000. Returns
  candidates sorted fastest (highest Elo) first."
  [pool candidates payload bench-opts]
  (let [timed (mapv (fn [c]
                       (let [{:keys [ch maj]} (candidate->fns pool c)]
                         {:candidate c
                          :ns-per-hash (bench-ns-per-hash #(core/sha256-bytes % ch maj) payload bench-opts)}))
                     candidates)
        n (count timed)
        ratings (atom (vec (repeat n 1000.0)))]
    (doseq [i (range n) j (range (inc i) n)]
      (let [ti (:ns-per-hash (timed i)) tj (:ns-per-hash (timed j))
            score-i (cond (< ti (* 0.99 tj)) 1.0 (> ti (* 1.01 tj)) 0.0 :else 0.5)
            [ri' rj'] (elo-update (@ratings i) (@ratings j) score-i)]
        (swap! ratings assoc i ri' j rj')))
    (->> (map #(assoc %1 :elo %2) timed @ratings)
         (sort-by :elo >))))

;; --- Proximity: collapse practically-indistinguishable results ---------------------

(defn cluster-by-proximity
  "Groups ranked results within `tolerance` (default 1%) of each other's ns/hash into
  a single cluster, so Meta-review can report 'these N are tied' instead of picking a
  spurious single winner out of measurement noise."
  ([ranked] (cluster-by-proximity ranked 0.01))
  ([ranked tolerance]
   (reduce (fn [clusters {:keys [ns-per-hash] :as r}]
             (if-let [current (peek clusters)]
               (if (<= (abs-val (- ns-per-hash (:ns-per-hash (first current))))
                       (* tolerance (:ns-per-hash (first current))))
                 (conj (pop clusters) (conj current r))
                 (conj clusters [r]))
               [[r]]))
           []
           ranked)))

;; --- Evolution: keep elites, recombine survivors' genes -----------------------------

(defn evolve-round
  "Elites (top `elite-n` by Elo) pass through unchanged; the rest of next round's
  population is every recombination (crossover) of gene choices seen among the
  elites. With today's 2x2 gene pool this mostly re-affirms the same 4 candidates
  under fresh measurement; it generalizes as sha256d.ops grows more genes/variants."
  [ranked elite-n]
  (let [elites (mapv :candidate (take elite-n ranked))]
    (distinct
     (into elites
           (reduce (fn [pop gene]
                     (for [c pop variant (distinct (map #(get % gene) elites))]
                       (assoc c gene variant)))
                   elites
                   (keys (first elites)))))))

;; --- Meta-review + Supervisor ---------------------------------------------------------

(defn meta-review [generation ranked clusters disqualified]
  {:generation generation
   :champion (:candidate (first ranked))
   :champion-ns-per-hash (:ns-per-hash (first ranked))
   :cluster-sizes (mapv count clusters)
   :disqualified disqualified
   :leaderboard (mapv #(select-keys % [:candidate :ns-per-hash :elo]) ranked)})

(def default-payload
  (core/str->bytes "sha256d.evolve tournament payload - the quick brown fox jumps over the lazy dog"))

(defn run-tournament
  "Supervisor: Generation -> Reflection -> Ranking -> Proximity -> Meta-review, for
  `generations` rounds with Evolution feeding each round's elites into the next.
  Returns the final round's meta-review. Disqualified (incorrect) candidates are
  recorded but never benchmarked or ranked."
  ([] (run-tournament {}))
  ([{:keys [pool payload generations elite-n bench-opts]
     :or {pool ops/gene-pool
          payload default-payload
          generations 3
          elite-n 2
          bench-opts {:iters 200 :reps 7}}}]
   (loop [gen 1 population (generate-candidates pool) last-review nil]
     (let [disqualified (remove #(reflect pool %) population)
           surviving (filter #(reflect pool %) population)
           ranked (rank pool surviving payload bench-opts)
           clusters (cluster-by-proximity ranked)
           review (meta-review gen ranked clusters disqualified)]
       (if (>= gen generations)
         review
         (recur (inc gen) (evolve-round ranked elite-n) review))))))

;; --- reporting -----------------------------------------------------------------------

(defn report->markdown [{:keys [generation champion champion-ns-per-hash cluster-sizes leaderboard disqualified]}]
  (str "## Generation " generation "\n\n"
       "- champion: `" (pr-str champion) "` (" champion-ns-per-hash " ns/hash)\n"
       "- proximity clusters (sizes): " (str/join ", " cluster-sizes) "\n"
       "- disqualified: " (count disqualified) "\n\n"
       "| candidate | ns/hash | elo |\n|---|---|---|\n"
       (str/join "\n" (for [{:keys [candidate ns-per-hash elo]} leaderboard]
                        (str "| `" (pr-str candidate) "` | " ns-per-hash " | " elo " |")))
       "\n"))

(defn -main [& _]
  (println (report->markdown (run-tournament {}))))
