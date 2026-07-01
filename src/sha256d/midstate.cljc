(ns sha256d.midstate
  "Bitcoin block-header mining's classic optimization: an 80-byte header splits into
  exactly two 64-byte compression blocks once padded (80 + 1 + 39 zero + 8 length =
  128 = 2x64). The first block covers version + prev-block-hash + a 36-byte prefix of
  the merkle root -- all constant while a miner iterates the nonce for a fixed
  template. Caching the compression state after that first block ('the midstate') and
  only re-running the second block's 64 rounds per attempt skips re-doing the first
  block's 64 rounds every single time, roughly halving the compression work of the
  *inner* SHA-256 per nonce trial (the outer wrapping SHA-256 over the 32-byte inner
  digest is unaffected -- it's already minimal, one block).

  This is exactly the kind of implementation-strategy variation sha256d.evolve's
  Reflection gate exists to check: `header-hash` below is verified equivalent to
  plain `sha256d.core/sha256d-bytes` on the full header, not benchmarked on faith."
  (:require [sha256d.core :as core]))

(def header-length-bytes 80)
(def header-length-bits-be
  "80 bytes = 640 bits as an 8-byte big-endian count -- always the same for a Bitcoin
  block header, so it's a constant rather than computed per call."
  [0 0 0 0 0 0 0x02 0x80])

(defn midstate
  "Compression state after an 80-byte header's constant first 64-byte chunk."
  [header-bytes]
  {:pre [(= header-length-bytes (count header-bytes))]}
  (core/compress core/H0 (subvec (vec header-bytes) 0 64)))

(defn header-hash
  "sha256d of an 80-byte header, given `mid` (from `midstate` on the same header) and
  the 16 bytes that vary between attempts (merkle-root tail 4B + time 4B + bits 4B +
  nonce 4B, i.e. header bytes [64 80)). Never re-touches the first chunk."
  [mid tail-16-bytes]
  {:pre [(= 16 (count tail-16-bytes))]}
  (let [chunk2 (-> (vec tail-16-bytes)
                    (conj 0x80)
                    (into (repeat 39 0))
                    (into header-length-bits-be))
        inner  (core/words->bytes (core/compress mid chunk2))]
    (core/sha256-bytes inner)))

(defn header-hash-reference
  "No-caching reference form (recomputes everything from the full header) -- used to
  check `header-hash` against, not for actual mining use."
  [header-bytes]
  (core/sha256d-bytes header-bytes))
