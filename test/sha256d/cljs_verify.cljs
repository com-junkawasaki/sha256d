(ns sha256d.cljs-verify
  "Run the same known-answer/property checks as test/sha256d/*_test.cljc under
  ClojureScript (node), proving the .cljc core actually runs on cljs -- not just the
  JVM. Mirrors com-junkawasaki/num-clj's test/num/cljs_verify.cljs pattern."
  (:require [sha256d.core :as core]
            [sha256d.ops :as ops]
            [sha256d.midstate :as ms]))

(defn -main [& _]
  (let [pass (atom 0) fail (atom 0)
        check (fn [label ok?] (if ok? (swap! pass inc) (do (swap! fail inc) (println "  ✗" label))))]
    (check "sha256(\"\")"
           (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
              (core/bytes->hex (core/sha256-bytes []))))
    (check "sha256(\"abc\")"
           (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
              (core/bytes->hex (core/sha256-bytes (core/str->bytes "abc")))))
    (check "56-byte NIST 2-block message"
           (= "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
              (core/bytes->hex
               (core/sha256-bytes (core/str->bytes "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")))))
    (check "every gene-pool combination (ch x maj x schedule) matches reference"
           (let [msg (core/str->bytes "the quick brown fox jumps over the lazy dog")
                 reference (core/sha256-bytes msg)]
             (every? #(= reference %)
                     (for [[_ ch-fn] (:ch ops/gene-pool)
                           [_ maj-fn] (:maj ops/gene-pool)
                           [_ compress-fn] (:schedule ops/gene-pool)]
                       (core/sha256-bytes-with compress-fn msg ch-fn maj-fn)))))
    (check "alternate schedule strategies (rolling, transient) match reference across sizes"
           (every? (fn [n]
                     (let [msg (vec (map #(mod (* 37 (inc %)) 256) (range n)))
                           ref (core/sha256-bytes msg)]
                       (and (= ref (core/sha256-bytes-with core/compress-rolling msg core/ch core/maj))
                            ;; also exercises transient-vector nth reads under cljs
                            (= ref (core/sha256-bytes-with core/compress-transient msg core/ch core/maj)))))
                   (range 0 130)))
    (check "midstate header-hash matches no-caching reference (20 random headers)"
           (every? (fn [_]
                     (let [header (vec (repeatedly ms/header-length-bytes #(rand-int 256)))]
                       (= (ms/header-hash-reference header)
                          (ms/header-hash (ms/midstate header) (subvec header 64 80)))))
                   (range 20)))
    (println (str "cljs sha256d verify: " @pass " passed, " @fail " failed"))
    (when (pos? @fail) (js/process.exit 1))))

(set! *main-cli-fn* -main)
