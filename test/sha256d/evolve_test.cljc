(ns sha256d.evolve-test
  (:require [clojure.test :refer [deftest testing is]]
            [sha256d.core :as core]
            [sha256d.ops :as ops]
            [sha256d.evolve :as evolve]))

(deftest generate-candidates-test
  (testing "every combination of the 2x2 default gene pool"
    (is (= 4 (count (evolve/generate-candidates))))
    (is (= #{{:ch :naive :maj :naive} {:ch :naive :maj :alt}
             {:ch :alt :maj :naive} {:ch :alt :maj :alt}}
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
      (is (= 4 (count ranked)))
      (is (apply >= (map :elo ranked)))
      (is (every? #(<= 0 (:ns-per-hash %)) ranked))))
  (testing "cluster-by-proximity partitions the ranked list without dropping anyone"
    (let [payload (core/str->bytes "cluster-test payload")
          ranked (evolve/rank ops/gene-pool (evolve/generate-candidates) payload {:iters 10 :reps 3})
          clusters (evolve/cluster-by-proximity ranked)]
      (is (= (count ranked) (reduce + (map count clusters)))))))

(deftest run-tournament-smoke-test
  (testing "a small, fast run completes and returns a well-formed, correct champion"
    (let [review (evolve/run-tournament {:generations 2 :elite-n 2 :bench-opts {:iters 10 :reps 3}})]
      (is (contains? (set (evolve/generate-candidates)) (:champion review)))
      (is (empty? (:disqualified review)))
      (is (seq (:leaderboard review))))))
