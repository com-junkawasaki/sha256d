(ns sha256d.core
  "FIPS 180-4 SHA-256 and Bitcoin's SHA-256d (double SHA-256), as a portable .cljc
  reference implementation over plain sequences of byte-values (ints 0-255) -- no
  host byte-array type in the hot path, so the same code runs on JVM Clojure and
  ClojureScript unchanged.

  This namespace is the correctness oracle for the rest of the repo: sha256d.ops
  supplies alternative, individually-proven-equivalent formulations of the Ch/Maj
  round primitives, and sha256d.evolve benchmarks candidates built from them against
  `sha256-bytes`/`sha256d-bytes` here. Any candidate whose output diverges from this
  one, on any input, is disqualified -- unlike search over an approximate objective
  (e.g. AlphaEvolve's matrix-multiplication search), a hash function that is merely
  *close* to correct is not SHA-256 at all.

  `compress`/`sha256-bytes`/`sha256d-bytes` take the round-function primitives
  (`ch`/`maj`) as an explicit seam (2-arity overloads default to this namespace's own
  `ch`/`maj`) so alternative formulations can be dropped in without duplicating the
  padding/schedule/compression pipeline.")

(def H0
  "Initial hash value, FIPS 180-4 §5.3.3."
  [0x6a09e667 0xbb67ae85 0x3c6ef372 0xa54ff53a
   0x510e527f 0x9b05688c 0x1f83d9ab 0x5be0cd19])

(def K
  "Round constants, FIPS 180-4 §4.2.2 (first 32 bits of the fractional parts of the
  cube roots of the first 64 primes)."
  [0x428a2f98 0x71374491 0xb5c0fbcf 0xe9b5dba5 0x3956c25b 0x59f111f1 0x923f82a4 0xab1c5ed5
   0xd807aa98 0x12835b01 0x243185be 0x550c7dc3 0x72be5d74 0x80deb1fe 0x9bdc06a7 0xc19bf174
   0xe49b69c1 0xefbe4786 0x0fc19dc6 0x240ca1cc 0x2de92c6f 0x4a7484aa 0x5cb0a9dc 0x76f988da
   0x983e5152 0xa831c66d 0xb00327c8 0xbf597fc7 0xc6e00bf3 0xd5a79147 0x06ca6351 0x14292967
   0x27b70a85 0x2e1b2138 0x4d2c6dfc 0x53380d13 0x650a7354 0x766a0abb 0x81c2c92e 0x92722c85
   0xa2bfe8a1 0xa81a664b 0xc24b8b70 0xc76c51a3 0xd192e819 0xd6990624 0xf40e3585 0x106aa070
   0x19a4c116 0x1e376c08 0x2748774c 0x34b0bcb5 0x391c0cb3 0x4ed8aa4a 0x5b9cca4f 0x682e6ff3
   0x748f82ee 0x78a5636f 0x84c87814 0x8cc70208 0x90befffa 0xa4506ceb 0xbef9a3f7 0xc67178f2])

;; --- 32-bit word primitives -------------------------------------------------------
;; Every op is bit-position-local (and/or/xor/not/shift/rotate) or a bounded-width add
;; (<=5 terms, each <2^32); double-precision `+` is exact at that magnitude on both
;; platforms, so `mask32` after add/shift is enough to get correct mod-2^32 wraparound
;; on JVM Clojure (64-bit long) and ClojureScript (32-bit JS bitwise coercion) alike --
;; no #?(:clj :cljs) split needed for the arithmetic itself.

(defn mask32 [x] (bit-and x 0xffffffff))
(defn add32 [& xs] (mask32 (apply + xs)))
(defn rotr32 [x n] (mask32 (bit-or (unsigned-bit-shift-right x n) (bit-shift-left x (- 32 n)))))
(defn shr32 [x n] (unsigned-bit-shift-right x n))

(defn ch  [x y z] (bit-xor (bit-and x y) (bit-and (bit-not x) z)))
(defn maj [x y z] (bit-xor (bit-and x y) (bit-and x z) (bit-and y z)))

(defn big-sigma0   [x] (bit-xor (rotr32 x 2)  (rotr32 x 13) (rotr32 x 22)))
(defn big-sigma1   [x] (bit-xor (rotr32 x 6)  (rotr32 x 11) (rotr32 x 25)))
(defn small-sigma0 [x] (bit-xor (rotr32 x 7)  (rotr32 x 18) (shr32 x 3)))
(defn small-sigma1 [x] (bit-xor (rotr32 x 17) (rotr32 x 19) (shr32 x 10)))

;; --- padding + block/word packing -------------------------------------------------

(defn- u64be-bytes
  "8-byte big-endian encoding of a non-negative integer `n` well within the
  double-precision-exact range (any realistic message bit-length). Splits into
  32-bit halves via arithmetic (quot/mod by 2^32) rather than shifting past bit 31:
  JS bitwise ops are 32-bit only and silently mask any shift count to its low 5 bits
  (`x >>> 40` behaves as `x >>> 8`), so shifts of 56/48/40/32 are a JVM-only trick --
  `unsigned-bit-shift-right` there operates on a 64-bit `long` -- not a portable one."
  [n]
  (let [hi (quot n 0x100000000) lo (mod n 0x100000000)]
    (into (mapv #(bit-and (unsigned-bit-shift-right hi %) 0xff) [24 16 8 0])
          (mapv #(bit-and (unsigned-bit-shift-right lo %) 0xff) [24 16 8 0]))))

(defn pad
  "FIPS 180-4 §5.1.1: append 0x80, zero-pad, then the original length as a 64-bit
  big-endian bit count, so the total length is a multiple of 64 bytes."
  [byte-seq]
  (let [bytes*   (vec byte-seq)
        len      (count bytes*)
        bit-len  (* 8 len)
        pad-len  (mod (- 56 (mod (inc len) 64)) 64)]
    (-> bytes*
        (conj 0x80)
        (into (repeat pad-len 0))
        (into (u64be-bytes bit-len)))))

(defn block->words
  "16 big-endian 32-bit words from a 64-byte block."
  [block]
  (let [b (vec block)]
    (vec (for [i (range 16)]
           (let [o (* i 4)]
             (bit-or (bit-shift-left (b o) 24)
                     (bit-shift-left (b (+ o 1)) 16)
                     (bit-shift-left (b (+ o 2)) 8)
                     (b (+ o 3))))))))

(defn words->bytes
  "8 big-endian 32-bit words -> 32-byte digest."
  [words]
  (vec (mapcat (fn [w] (map #(bit-and (unsigned-bit-shift-right w %) 0xff) [24 16 8 0])) words)))

(defn extend-schedule
  "16 block words -> the 64-word message schedule W[0..63], FIPS 180-4 §6.2.2 step 1."
  [w16]
  (loop [w (vec w16) t 16]
    (if (= t 64)
      w
      (recur (conj w (add32 (small-sigma1 (w (- t 2)))
                             (w (- t 7))
                             (small-sigma0 (w (- t 15)))
                             (w (- t 16))))
             (inc t)))))

(defn extend-schedule-transient
  "Same 64-word schedule as `extend-schedule`, but grown in a transient vector with one
  `persistent!` at the end, to cut the per-step persistent-vector allocation `conj` does.
  Portable: transient vectors support indexed reads (`nth`) on both Clojure and
  ClojureScript, and the result is made persistent before `run-rounds` ever reads it."
  [w16]
  (loop [w (transient (vec w16)) t 16]
    (if (= t 64)
      (persistent! w)
      (recur (conj! w (add32 (small-sigma1 (nth w (- t 2)))
                             (nth w (- t 7))
                             (small-sigma0 (nth w (- t 15)))
                             (nth w (- t 16))))
             (inc t)))))

;; --- compression -------------------------------------------------------------------

(defn- run-rounds
  "The 64-round compression, given 8-word `state` and a fully-materialized, indexable
  message schedule `w` (a vector, so `(w t)` reads W[t]). Shared by `compress` and
  `compress-transient`; only how `w` is built differs between them."
  [[h0 h1 h2 h3 h4 h5 h6 h7] w ch-fn maj-fn]
  (loop [a h0 b h1 c h2 d h3 e h4 f h5 g h6 h h7 t 0]
    (if (= t 64)
      [(add32 h0 a) (add32 h1 b) (add32 h2 c) (add32 h3 d)
       (add32 h4 e) (add32 h5 f) (add32 h6 g) (add32 h7 h)]
      (let [t1 (add32 h (big-sigma1 e) (ch-fn e f g) (K t) (w t))
            t2 (add32 (big-sigma0 a) (maj-fn a b c))]
        (recur (add32 t1 t2) a b c (add32 d t1) e f g (inc t))))))

(defn compress
  "One compression round over a 64-byte `block`, folding it into 8-word `state`.
  `ch-fn`/`maj-fn` default to this namespace's reference `ch`/`maj` -- pass alternative,
  proven-equivalent formulations (see sha256d.ops) to benchmark them through the exact
  same padding/schedule/compression pipeline. Schedule built via `extend-schedule`."
  ([state block] (compress state block ch maj))
  ([state block ch-fn maj-fn]
   (run-rounds state (extend-schedule (block->words block)) ch-fn maj-fn)))

(defn compress-transient
  "Same result as `compress`, but builds the message schedule with `extend-schedule-
  transient` (transient vector) to test whether cutting the schedule-build allocation is
  a measurable, portable win over `compress`'s persistent-`conj` build (round 5). Same
  `run-rounds` compression body; only the schedule construction differs."
  ([state block] (compress-transient state block ch maj))
  ([state block ch-fn maj-fn]
   (run-rounds state (extend-schedule-transient (block->words block)) ch-fn maj-fn)))

(defn compress-rolling
  "Same result as `compress`, but computes the message schedule in a 16-word rolling
  window just-in-time inside the round loop instead of materializing the full 64-word
  W vector first -- the 'schedule-buffer reuse' implementation strategy, which trades a
  64-element allocation per block for a 16-element one. `win` holds the last 16 schedule
  words [W(t-16)..W(t-1)] on entry to step t; for t<16 they are the block words, and for
  t>=16 W(t)=σ1(W(t-2))+W(t-7)+σ0(W(t-15))+W(t-16) reads win[14]/win[9]/win[1]/win[0]
  respectively, then the window slides. Held to the same hard correctness gate as the
  reference (see test/sha256d/core_test.cljc and sha256d.evolve/reflect) -- if it ever
  diverged from `compress` on any input it would be disqualified, not merely slower."
  ([state block] (compress-rolling state block ch maj))
  ([[h0 h1 h2 h3 h4 h5 h6 h7] block ch-fn maj-fn]
   (let [bw (block->words block)]
     (loop [a h0 b h1 c h2 d h3 e h4 f h5 g h6 h h7 win bw t 0]
       (if (= t 64)
         [(add32 h0 a) (add32 h1 b) (add32 h2 c) (add32 h3 d)
          (add32 h4 e) (add32 h5 f) (add32 h6 g) (add32 h7 h)]
         (let [wt   (if (< t 16)
                      (nth bw t)
                      (add32 (small-sigma1 (nth win 14)) (nth win 9)
                             (small-sigma0 (nth win 1)) (nth win 0)))
               t1   (add32 h (big-sigma1 e) (ch-fn e f g) (K t) wt)
               t2   (add32 (big-sigma0 a) (maj-fn a b c))
               win' (if (< t 16) win (conj (subvec win 1 16) wt))]
           (recur (add32 t1 t2) a b c (add32 d t1) e f g win' (inc t))))))))

;; --- public digest API --------------------------------------------------------------

(defn sha256-bytes-with
  "SHA-256 with an injectable compression strategy (`compress` = precompute the full
  message schedule, or `compress-rolling` = 16-word rolling window) alongside the
  ch/maj seams. sha256d.evolve treats the schedule strategy as a third gene and
  benchmarks it through this entry point; `sha256-bytes` below is just this with the
  reference `compress`."
  [compress-fn byte-seq ch-fn maj-fn]
  (->> (partition 64 (pad byte-seq))
       (reduce #(compress-fn %1 %2 ch-fn maj-fn) H0)
       words->bytes))

(defn sha256-bytes
  "SHA-256 of a sequence of byte values (ints 0-255). Returns a 32-element byte vector."
  ([byte-seq] (sha256-bytes byte-seq ch maj))
  ([byte-seq ch-fn maj-fn] (sha256-bytes-with compress byte-seq ch-fn maj-fn)))

(defn sha256d-bytes
  "Bitcoin's SHA256d: SHA256(SHA256(x)). Used for block header hashes, txids and
  merkle-tree nodes throughout the Bitcoin protocol."
  ([byte-seq] (sha256-bytes (sha256-bytes byte-seq)))
  ([byte-seq ch-fn maj-fn] (sha256-bytes (sha256-bytes byte-seq ch-fn maj-fn) ch-fn maj-fn)))

;; --- convenience: strings / hex -----------------------------------------------------

(defn str->bytes
  "UTF-8 bytes of a string, as a vector of ints 0-255."
  [s]
  #?(:clj  (vec (map #(bit-and (int %) 0xff) (.getBytes ^String s "UTF-8")))
     :cljs (vec (js/Array.from (.encode (js/TextEncoder.) s)))))

(def ^:private hex-chars "0123456789abcdef")

(defn bytes->hex
  "Lowercase hex string for a byte-value sequence, in the given (on-wire) order."
  [byte-seq]
  (apply str (mapcat (fn [b] [(nth hex-chars (bit-and (unsigned-bit-shift-right b 4) 0xf))
                              (nth hex-chars (bit-and b 0xf))])
                     byte-seq)))

(defn bytes->hex-reversed
  "Bitcoin conventionally displays hashes byte-reversed relative to their on-wire
  (little-endian) storage order -- e.g. block hashes/txids as seen in explorers and
  RPC output. This reverses the byte sequence before hex-encoding to match that
  display convention; `bytes->hex` gives the raw on-wire order."
  [byte-seq]
  (bytes->hex (reverse byte-seq)))
