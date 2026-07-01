(ns sha256d.core-test
  "Correctness gate for sha256d.core, against independently-computed ground truth
  (every hex literal below was generated with Python's hashlib / the system `shasum`
  utility, not hand-derived, and re-verified by exact string length before use --
  see the ADR for this repo for the derivation transcript)."
  (:require [clojure.test :refer [deftest testing is]]
            [sha256d.core :as core]))

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
