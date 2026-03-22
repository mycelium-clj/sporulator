(ns sporulator.eval
  "In-process code evaluation for sporulator.
   Replaces the Go bridge — no nREPL needed since we're in the same JVM.
   Provides safe eval with timeout, output capture, and error handling."
  (:require [clojure.string :as str]
            [clojure.test :as test])
  (:import [java.io StringWriter]))

;; ── Safe eval ──────────────────────────────────────────────────

(defn eval-code
  "Evaluates a string of Clojure code. Returns a result map:
     {:status :ok      :result value :output stdout-string}
     {:status :error   :error message :output stdout-string}
     {:status :timeout :error message}

   Uses load-string which properly handles (ns ...) forms.

   Options:
     :timeout-ms  — max eval time in ms (default 30000)"
  [code & {:keys [timeout-ms] :or {timeout-ms 30000}}]
  (let [out    (StringWriter.)
        result (promise)
        cl     (.getContextClassLoader (Thread/currentThread))
        thread (doto (Thread.
                      (fn []
                        (.setContextClassLoader (Thread/currentThread) cl)
                        (try
                          (let [val (binding [*out* out]
                                      (load-string code))]
                            (deliver result {:status :ok :result val}))
                          (catch Throwable t
                            (let [cause (or (.getCause t) t)]
                              (deliver result {:status :error
                                               :error  (or (ex-message cause)
                                                           (str (type cause)))}))))))
                 (.setDaemon true))]
    (.start thread)
    (let [r (deref result timeout-ms ::timeout)]
      (if (= r ::timeout)
        (do (.interrupt thread)
            {:status  :timeout
             :error   (str "Evaluation timed out after " timeout-ms "ms")
             :output  (str out)})
        (assoc r :output (str out))))))

;; ── Cell instantiation ─────────────────────────────────────────

(defn instantiate-cell
  "Loads a cell from assembled source code and verifies it registered.
   Returns {:status :ok :cell-id kw} or {:status :error :error msg}."
  [source expected-cell-id]
  (let [r (eval-code source)]
    (if (= :ok (:status r))
      (try
        (if-let [get-cell (requiring-resolve 'mycelium.cell/get-cell!)]
          (if-let [cell (get-cell expected-cell-id)]
            {:status :ok :cell-id expected-cell-id :output (:output r)}
            {:status :error
             :error  (str "Cell " expected-cell-id " not found in registry after loading")
             :output (:output r)})
          {:status :error
           :error  "mycelium.cell/get-cell! not found on classpath"
           :output (:output r)})
        (catch Exception e
          {:status :error
           :error  (.getMessage e)
           :output (:output r)}))
      r)))

;; ── Test runner ────────────────────────────────────────────────

(defn run-cell-tests
  "Loads test source code, runs the tests, and returns structured results.
   Returns {:status :ok :passed? bool :summary {:test :pass :fail :error} :output str}
        or {:status :error :error msg}."
  [test-source]
  (let [load-result (eval-code test-source)]
    (if (not= :ok (:status load-result))
      load-result
      ;; Find the test namespace that was just loaded
      (let [ns-name (second (re-find #"\(ns\s+(\S+)" test-source))]
        (if-not ns-name
          {:status :error
           :error  "Could not extract namespace from test source"}
          (let [test-ns (find-ns (symbol ns-name))]
            (if-not test-ns
              {:status :error
               :error  (str "Could not find test namespace: " ns-name)}
          (let [out       (StringWriter.)
                counters  (atom {:test 0 :pass 0 :fail 0 :error 0})
                ;; Custom reporter that captures results without
                ;; leaking to the parent test framework
                reporter  (fn [m]
                            (let [out-w out]
                              (case (:type m)
                                :begin-test-ns nil
                                :end-test-ns   nil
                                :begin-test-var
                                (swap! counters update :test inc)
                                :pass
                                (swap! counters update :pass inc)
                                :fail
                                (do (swap! counters update :fail inc)
                                    (.write out-w
                                      (str "\nFAIL in " (test/testing-vars-str m) "\n"
                                           (:message m "")
                                           "\nexpected: " (pr-str (:expected m))
                                           "\n  actual: " (pr-str (:actual m)) "\n")))
                                :error
                                (do (swap! counters update :error inc)
                                    (.write out-w
                                      (str "\nERROR in " (test/testing-vars-str m) "\n"
                                           (:message m "")
                                           "\n  actual: " (pr-str (:actual m)) "\n")))
                                :summary nil
                                nil)))
                _         (binding [*out*        out
                                    test/report  reporter]
                            (test/run-tests test-ns))
                summary   @counters]
            {:status  :ok
             :passed? (and (zero? (:fail summary))
                           (zero? (:error summary)))
             :summary summary
             :output  (str (:output load-result) (str out))}))))))))

;; ── Contract verification ──────────────────────────────────────

(defn verify-cell-contract
  "Checks that a cell is registered in the mycelium registry.
   Returns {:status :ok :cell spec-map} or {:status :error :error msg}."
  [cell-id]
  (try
    (if-let [get-cell (requiring-resolve 'mycelium.cell/get-cell!)]
      (if-let [cell (get-cell cell-id)]
        {:status :ok :cell cell}
        {:status :error
         :error  (str "Cell " cell-id " is not registered")})
      {:status :error
       :error  "mycelium.cell/get-cell! not found on classpath"})
    (catch Exception e
      {:status :error :error (.getMessage e)})))
