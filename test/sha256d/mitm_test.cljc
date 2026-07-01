(ns sha256d.mitm-test
  (:require [clojure.test :refer [deftest testing is]]
            [sha256d.mitm :as mitm]))

(deftest schedule-deps-test
  (testing "base words depend on themselves"
    (let [d (mitm/schedule-deps 20)]
      (is (= #{0} (d 0)))
      (is (= #{15} (d 15)))
      (testing "W16 depends on its four feedback taps {W14,W9,W1,W0}"
        (is (= #{0 1 9 14} (d 16))))
      (testing "W17 depends on {W15,W10,W2,W1}"
        (is (= #{1 2 10 15} (d 17))))))
  (testing "the dependency fan-out saturates to all 16 base words"
    (let [d (mitm/schedule-deps 64)]
      ;; by construction each later word unions its taps; deep words touch every base word
      (is (= (set (range 16)) (d 63)))
      ;; and it saturates fairly early (this is WHY the attack decays) -- verify it is full by t=32
      (is (= (set (range 16)) (d 32))))))

(deftest sixteen-round-classic-mitm-test
  (testing "16-round SHA-256 (no expansion yet) admits the classic 2^128 MITM square-root:
            an 8-word/8-word neutral split, i.e. 128 bits below brute force"
    (let [a (mitm/best-attack 16)]
      (is (some? a))
      (is (= 128 (:pseudo-log2 a)) "balanced MITM floor = 2^(256/2)")
      (is (= 128 (:saved-bits a)))
      (is (:below-brute? a))
      (is (and (pos? (:n1 a)) (pos? (:n2 a)))))))

(deftest attack-decays-with-rounds-test
  (testing "savings are monotonically non-increasing as rounds grow (expansion fan-out shrinks
            the neutral sets) -- the empirical signature of SHA-256's preimage resistance"
    (let [tbl (mitm/attack-table (range 16 40))
          saved (mapv #(get % :saved-bits 0) tbl)]
      (is (apply >= saved) (str "expected non-increasing savings, got " saved))
      (is (= 128 (first saved)) "16 rounds: full 2^128 speedup")
      (is (zero? (last saved)) "by ~40 rounds word-granularity MITM saves nothing"))))

(deftest reflection-gate-test
  (testing "a config with an empty neutral set on either side is rejected (not a valid attack)"
    (let [deps (mitm/schedule-deps 24)
          ;; whole cycle as one arc -> the other chunk is empty -> no neutral split
          whole (mitm/config deps 24 (vec (range 24)))]
      (is (or (zero? (:n1 whole)) (zero? (:n2 whole)))
          "a degenerate split has an empty neutral set")
      (is (nil? (mitm/best-attack 48)) "no word-granularity MITM survives to 48 rounds"))))
