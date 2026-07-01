(ns sha256d.ops
  "The 'gene pool' sha256d.evolve recombines and benchmarks: interchangeable
  reformulations of the SHA-256 round-function primitives. Every variant here is
  *exactly* equivalent to sha256d.core's reference formula for the same primitive --
  proven algebraically below and re-checked exhaustively in test/sha256d/ops_test.cljc
  (all 2^3 single-bit patterns, plus randomized 32-bit words) -- only the operation
  sequence differs, never the result. That correctness gate is non-negotiable; only
  variants that pass it are eligible for sha256d.evolve's benchmark tournament.")

;; --- Ch(x,y,z) = (x&y) ^ (~x&z) -----------------------------------------------------

(defn ch-naive
  "FIPS 180-4 textbook form; identical to sha256d.core/ch."
  [x y z]
  (bit-xor (bit-and x y) (bit-and (bit-not x) z)))

(defn ch-alt
  "z ^ (x & (y^z)) -- one fewer gate than ch-naive (4 ops vs 5: and/not/and/xor).
  Algebraic proof of equivalence: AND distributes over XOR, so
    x & (y^z) = (x&y) ^ (x&z)
  and for any x,z:  z & ~x = z ^ (z&x)  (check both cases of the bit x: x=0 -> z&1=z,
  z^0=z; x=1 -> z&0=0, z^z=0). Substituting:
    z ^ (x & (y^z)) = z ^ (x&y) ^ (x&z) = (x&y) ^ (z ^ (z&x)) = (x&y) ^ (~x&z) = ch-naive.
  This is the formulation used by OpenSSL's and Bitcoin Core's SHA-256 C code."
  [x y z]
  (bit-xor z (bit-and x (bit-xor y z))))

;; --- Maj(x,y,z) = (x&y) ^ (x&z) ^ (y&z) ---------------------------------------------

(defn maj-naive
  "FIPS 180-4 textbook form; identical to sha256d.core/maj."
  [x y z]
  (bit-xor (bit-and x y) (bit-and x z) (bit-and y z)))

(defn maj-alt
  "(x&y) | (z&(x|y)) -- same gate count as maj-naive, different critical path/data
  dependencies (an OR-reduction instead of an XOR-of-three), which pipelines
  differently. Equivalence: for any single bit, at most one of {x&y, x&z, y&z} can be
  1 without the other two also being 1 (e.g. x&y=1 and x&z=1 forces x=y=z=1, which
  makes y&z=1 too) -- so the three pairwise terms are always jointly 0, exactly-one-1,
  or all-1, never exactly-two-1. OR and XOR of three terms agree on every one of those
  three cases, so ORing them (as z&(x|y) expands to (z&x)|(z&y), then OR with x&y)
  gives the same result as XORing them."
  [x y z]
  (bit-or (bit-and x y) (bit-and z (bit-or x y))))

(def gene-pool
  "Named variants per primitive, keyed for sha256d.evolve's candidate generation."
  {:ch  {:naive ch-naive :alt ch-alt}
   :maj {:naive maj-naive :alt maj-alt}})
