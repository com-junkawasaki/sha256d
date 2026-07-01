(ns sha256d.midstate-test
  "The property that matters here is equivalence to the no-caching reference path on
  many random headers -- that's a stronger correctness proof than any single fixture,
  since it exercises every nonce/timestamp/bits bit pattern, not just one. The fixture
  below is additionally cross-checked: it's a synthetic (NOT the real genesis block)
  80-byte header whose sha256d was computed independently via Python's hashlib, not
  hand-derived -- see the ADR for the derivation transcript."
  (:require [clojure.test :refer [deftest testing is]]
            [sha256d.core :as core]
            [sha256d.midstate :as ms]))

(defn rand-byte [] (rand-int 256))
(defn rand-header [] (vec (repeatedly ms/header-length-bytes rand-byte)))

(deftest midstate-equivalence-property-test
  (testing "header-hash via cached midstate matches the no-caching reference, on many random headers"
    (dotimes [_ 500]
      (let [header (rand-header)
            mid    (ms/midstate header)
            tail   (subvec header 64 80)]
        (is (= (ms/header-hash-reference header)
               (ms/header-hash mid tail)))))))

(deftest midstate-reuse-across-nonces-test
  (testing "the same midstate is valid for every nonce of the same first-64-bytes prefix"
    (let [prefix (vec (repeatedly 64 rand-byte))
          mid    (ms/midstate (into prefix (repeat 16 0)))]
      (dotimes [_ 200]
        (let [tail (vec (repeatedly 16 rand-byte))
              header (into prefix tail)]
          (is (= (ms/header-hash-reference header)
                 (ms/header-hash mid tail))))))))

(deftest header-hash-with-fast-path-test
  (testing "header-hash-with the JVM fast compress finds the identical hash as the reference,
            on many random headers -- the two mining wins (midstate + fast compress) compose
            without changing the result"
    #?(:clj
       (dotimes [_ 300]
         (let [header (rand-header)
               mid    (ms/midstate header)
               tail   (subvec header 64 80)]
           (is (= (ms/header-hash-reference header)
                  (ms/header-hash-with core/compress-primitive-inline mid tail))))))))

(deftest search-nonce-cross-strategy-test
  (testing "search-nonce finds the same winning nonce with the reference and (on the JVM) the
            fast compress -- correctness is independent of the compression strategy"
    (let [prefix12 (vec (repeatedly 12 rand-byte))
          ;; a fixed header prefix -> one midstate; vary only the nonce
          mid      (ms/midstate (into (vec (repeatedly 64 rand-byte)) (repeat 16 0)))
          zero-bits 8            ;; ~1/256 hit rate -> found within a few hundred nonces
          ref-hit  (ms/search-nonce core/compress mid prefix12 zero-bits 0 5000)]
      (is (some? ref-hit) "a nonce meeting an 8-leading-zero-bit target exists within 5000 tries")
      (when ref-hit
        (is (>= (#'ms/leading-zero-bits (reverse (second ref-hit))) zero-bits)))
      #?(:clj
         (is (= ref-hit (ms/search-nonce core/compress-primitive-inline mid prefix12 zero-bits 0 5000))
             "the JVM fast path finds the identical winning nonce as the reference"))
      #?(:clj
         (is (= ref-hit (ms/search-nonce-parallel core/compress mid prefix12 zero-bits 0 5000 10))
             "the parallel search returns the identical globally-lowest winning nonce")))))

(deftest synthetic-header-fixture-test
  (testing "synthetic (non-genesis) 80-byte header, sha256d cross-checked via Python hashlib"
    (let [header-hex "010000000000000000000000000000000000000000000000000000000000000000000000000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f29ab5f491d00ffff7c2bac1d"
          expected   "7f02852d8625141943ac4f39a7d8a976182f32efe3cd686e8568c2db23536af8"
          header     (vec (map #(Integer/parseInt (apply str %) 16) (partition 2 header-hex)))]
      (is (= 80 (count header)))
      (is (= expected (core/bytes->hex (ms/header-hash-reference header))))
      (is (= expected (core/bytes->hex (ms/header-hash (ms/midstate header) (subvec header 64 80))))))))
