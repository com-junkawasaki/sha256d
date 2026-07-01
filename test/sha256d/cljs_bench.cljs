(ns sha256d.cljs-bench
  "Run the evolve tournament under ClojureScript/node -- the first time the *performance*
  side of the harness (not just correctness) runs off the JVM. Answers round 7's standing
  question: do the JVM speed conclusions (rolling worst, transient ~tie, Ch/Maj noise) hold
  on V8, where there is no boxed-`Long` problem and the GC/JIT differ? The cljs gene pool
  excludes the JVM-only `:mutable`/`:primitive` schedules, so this ranks the 27 portable
  candidates (:ch 3 x :maj 3 x :schedule {precompute, rolling, precompute-transient})."
  (:require [sha256d.evolve :as evolve]))

(defn -main [& _]
  ;; same default methodology as the JVM run (200 iters x 7 reps, 3 generations) so the
  ;; rankings are comparable; `now-ns`'s cljs branch uses js/performance.now (ns via *1e6).
  (println "# ClojureScript / node tournament (V8)\n")
  (println (evolve/report->markdown (evolve/run-tournament {}))))

(set! *main-cli-fn* -main)
