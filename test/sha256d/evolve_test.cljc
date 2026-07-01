(ns sha256d.evolve-test
  (:require [clojure.test :refer [deftest testing is]]
            [sha256d.core :as core]
            [sha256d.ops :as ops]
            [sha256d.evolve :as evolve]))

(defn expected-candidate-count [pool]
  (reduce * (map (comp count val) pool)))

(deftest generate-candidates-test
  (testing "every combination of the default gene pool, size derived from the pool itself
            so this test doesn't need editing every time a gene/variant is added"
    (is (= (expected-candidate-count ops/gene-pool) (count (evolve/generate-candidates))))
    (is (= (set (for [ch-k (keys (:ch ops/gene-pool)) maj-k (keys (:maj ops/gene-pool))]
                  {:ch ch-k :maj maj-k}))
           (set (evolve/generate-candidates))))))

(deftest reflect-test
  (testing "every real gene-pool candidate passes the correctness gate"
    (doseq [c (evolve/generate-candidates)]
      (is (true? (evolve/reflect ops/gene-pool c)) (pr-str c))))
  (testing "a deliberately broken candidate is disqualified, not merely down-scored"
    (let [broken-pool (assoc-in ops/gene-pool [:ch :broken] (fn [x _y _z] x))]
      (is (false? (evolve/reflect broken-pool {:ch :broken :maj :naive}))))))

(deftest rank-and-cluster-test
  (testing "rank produces a full, Elo-sorted leaderboard over the surviving candidates"
    (let [payload (core/str->bytes "rank-test payload")
          ranked (evolve/rank ops/gene-pool (evolve/generate-candidates) payload {:iters 10 :reps 3})]
      (is (= (expected-candidate-count ops/gene-pool) (count ranked)))
      (is (apply >= (map :elo ranked)))
      (is (every? #(<= 0 (:ns-per-hash %)) ranked))))
  (testing "cluster-by-proximity partitions the ranked list without dropping anyone"
    (let [payload (core/str->bytes "cluster-test payload")
          ranked (evolve/rank ops/gene-pool (evolve/generate-candidates) payload {:iters 10 :reps 3})
          clusters (evolve/cluster-by-proximity ranked)]
      (is (= (count ranked) (reduce + (map count clusters)))))))

(deftest evolve-round-mutation-test
  (testing "mutation reintroduces gene variants the elites dropped (round-3 fix for
            premature convergence) while keeping the elites and every candidate correct"
    (let [;; both elites agree on :maj :alt and only use :ch {:alt,:or}: under crossover
          ;; alone, :ch :naive and :maj {:naive,:or} would be lost forever -- mutation
          ;; must bring them back
          ranked   [{:candidate {:ch :alt :maj :alt}} {:candidate {:ch :or :maj :alt}}]
          next-pop (set (evolve/evolve-round ops/gene-pool ranked 2))
          ch-vars  (set (map :ch next-pop))
          maj-vars (set (map :maj next-pop))]
      (is (contains? next-pop {:ch :alt :maj :alt}) "elite survives")
      (is (contains? next-pop {:ch :or :maj :alt}) "elite survives")
      (is (contains? ch-vars :naive) ":ch :naive reintroduced by mutation")
      (is (contains? maj-vars :naive) ":maj :naive reintroduced by mutation")
      (is (contains? maj-vars :or) ":maj :or reintroduced by mutation")
      (is (>= (count next-pop) 3) "population does not collapse to just the 2 elites")
      (doseq [c next-pop]
        (is (true? (evolve/reflect ops/gene-pool c)) (pr-str c))))))

(deftest rank-persistent-ratings-test
  (testing "rank seeds Elo from prior-ratings so evidence carries across generations"
    (let [payload (core/str->bytes "persist-test payload")
          cands   (evolve/generate-candidates)
          favored (first cands)
          ;; an absurd prior Elo can't be overtaken by one generation of pairwise games
          ;; (K=32 per game), so the seeded candidate must still rank first
          ranked  (evolve/rank ops/gene-pool cands payload {:iters 10 :reps 3} {favored 1.0e6})]
      (is (= favored (:candidate (first ranked)))))))

(deftest run-tournament-smoke-test
  (testing "a small, fast run completes and returns a well-formed, correct champion with
            a diverse (non-collapsed) final population"
    (let [review (evolve/run-tournament {:generations 2 :elite-n 2 :bench-opts {:iters 10 :reps 3}})]
      (is (contains? (set (evolve/generate-candidates)) (:champion review)))
      (is (empty? (:disqualified review)))
      (is (seq (:leaderboard review)))
      (is (>= (:population-size review) 3) "mutation keeps the field diverse past gen 1"))))
