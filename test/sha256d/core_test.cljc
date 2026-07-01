(ns sha256d.core-test
  "Correctness gate for sha256d.core, against independently-computed ground truth
  (every hex literal below was generated with Python's hashlib / the system `shasum`
  utility, not hand-derived, and re-verified by exact string length before use --
  see the ADR for this repo for the derivation transcript)."
  (:require [clojure.test :refer [deftest testing is]]
            [sha256d.core :as core]
            [sha256d.ops :as ops]))

(deftest fips-known-answer-test
  (testing "empty message (single block)"
    (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
           (core/bytes->hex (core/sha256-bytes [])))))
  (testing "\"abc\" (single block)"
    (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
           (core/bytes->hex (core/sha256-bytes (core/str->bytes "abc"))))))
  (testing "56-byte NIST message (exercises 2-block padding: 56+1+7+... -> 64+64)"
    (is (= "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
           (core/bytes->hex
            (core/sha256-bytes (core/str->bytes "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))))))
  (testing "1,000,000 x 'a' (exercises many-block iteration)"
    (is (= "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
           (core/bytes->hex (core/sha256-bytes (vec (repeat 1000000 (int \a)))))))))

(deftest sha256d-test
  (testing "sha256d is literally sha256 of sha256"
    (let [msg (core/str->bytes "abc")]
      (is (= (core/sha256-bytes (core/sha256-bytes msg))
             (core/sha256d-bytes msg))))))

(deftest hex-roundtrip-test
  (testing "bytes->hex-reversed reverses byte order, not the hex string"
    (is (= "01020304" (core/bytes->hex [1 2 3 4])))
    (is (= "04030201" (core/bytes->hex-reversed [1 2 3 4])))))

(deftest pad-length-invariant-test
  (testing "padded length is always a positive multiple of 64 bytes"
    (doseq [n (range 0 200)]
      (let [padded (core/pad (repeat n 0))]
        (is (zero? (mod (count padded) 64)))
        (is (pos? (count padded)))))))

(deftest schedule-strategy-equivalence-test
  (testing "every alternate schedule strategy (rolling window, transient precompute) is
            bit-identical to the reference full-precompute compress, on inputs spanning
            every padding boundary and enough blocks to exercise them many times over"
    (doseq [compress-fn [core/compress-rolling core/compress-transient]
            n (range 0 260)]
      (let [msg (vec (map #(mod (* 37 (inc %)) 256) (range n)))]  ; deterministic, varied
        (is (= (core/sha256-bytes msg)
               (core/sha256-bytes-with compress-fn msg core/ch core/maj))
            (str compress-fn " n=" n))))
    (testing "known FIPS vectors also pass via each alternate schedule"
      (doseq [compress-fn [core/compress-rolling core/compress-transient]]
        (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
               (core/bytes->hex (core/sha256-bytes-with compress-fn [] core/ch core/maj))))
        (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
               (core/bytes->hex (core/sha256-bytes-with compress-fn
                                                        (core/str->bytes "abc") core/ch core/maj))))))))

#?(:clj
   (deftest jvm-only-schedule-equivalence-test
     (testing "the JVM-only fast paths (mutable long-array schedule; unboxed round loop)
               are bit-identical to the reference across every padding boundary and many
               blocks, and via all ch/maj gene variants for the unboxed path"
       (doseq [compress-fn [core/compress-mutable core/compress-primitive core/compress-primitive-inline]
               n (range 0 260)]
         (let [msg (vec (map #(mod (* 37 (inc %)) 256) (range n)))]
           (is (= (core/sha256-bytes msg)
                  (core/sha256-bytes-with compress-fn msg core/ch core/maj))
               (str compress-fn " n=" n))))
       ;; the unboxed path composes with the ch/maj genes -- check every combination
       (doseq [[_ ch-fn]  (:ch ops/gene-pool)
               [_ maj-fn] (:maj ops/gene-pool)]
         (let [msg (core/str->bytes "compose-primitive-with-ch-maj-genes")]
           (is (= (core/sha256-bytes msg)
                  (core/sha256-bytes-with core/compress-primitive msg ch-fn maj-fn))))))))

#?(:clj
   (deftest compress-2way-equivalence-test
     (testing "each lane of the interleaved 2-way compress is bit-identical to the single-lane
               compress-primitive-inline, on many random (state, block) pairs"
       (let [rng (java.util.Random. 42)
             rand-word (fn [] (bit-and (.nextLong rng) 0xffffffff))
             rand-state (fn [] (vec (repeatedly 8 rand-word)))
             rand-block (fn [] (vec (repeatedly 64 #(.nextInt rng 256))))]
         (dotimes [_ 300]
           (let [s1 (rand-state) b1 (rand-block) s2 (rand-state) b2 (rand-block)
                 [o1 o2] (core/compress-primitive-2way s1 b1 s2 b2)]
             (is (= (core/compress-primitive-inline s1 b1) o1))
             (is (= (core/compress-primitive-inline s2 b2) o2))))))))
