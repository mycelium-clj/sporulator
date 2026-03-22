(ns sporulator.eval
  "In-process code evaluation for sporulator.
   Replaces the Go bridge — no nREPL needed since we're in the same JVM.
   Provides safe eval with timeout, output capture, and error handling."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
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

;; ── Workflow compilation & execution ───────────────────────────

(defn compile-workflow
  "Compiles a manifest EDN string into a runnable workflow.
   Returns {:status :ok :compiled workflow} or {:status :error :error msg}."
  [manifest-edn-str]
  (let [r (eval-code
            (str "(require 'mycelium.core)\n"
                 "(mycelium.core/pre-compile " manifest-edn-str ")"))]
    (if (= :ok (:status r))
      {:status :ok :compiled (:result r) :output (:output r)}
      {:status :error :error (or (:error r) "Compilation failed")
       :output (:output r)})))

(defn run-workflow
  "Compiles and runs a workflow with the given input data and resources.
   Returns {:status :ok :result data} or {:status :error :error msg}."
  [manifest-edn-str input resources]
  (try
    (let [compile-result (compile-workflow manifest-edn-str)]
      (if (not= :ok (:status compile-result))
        compile-result
        (let [run-fn  (requiring-resolve 'mycelium.core/run-compiled)
              error?  (requiring-resolve 'mycelium.core/error?)
              wf-err  (requiring-resolve 'mycelium.core/workflow-error)
              result  (run-fn (:compiled compile-result) resources input)]
          (if (error? result)
            {:status :error
             :error  (pr-str (wf-err result))
             :result result}
            {:status :ok :result result}))))
    (catch Exception e
      {:status :error :error (ex-message e)})))

;; ── Schema validation ──────────────────────────────────────────

(defn validate-schema
  "Validates data against a Malli schema (lite syntax string).
   Returns {:valid? true} or {:valid? false :explanation ...}
   or {:valid? false :error msg} if schema can't be parsed."
  [schema-str data]
  (try
    (let [parsed (edn/read-string schema-str)
          ;; Convert lite syntax map to malli [:map ...] form
          malli-schema (if (map? parsed)
                         (into [:map] (map (fn [[k v]] [k v]) parsed))
                         parsed)
          validate-fn  (requiring-resolve 'malli.core/validate)
          explain-fn   (requiring-resolve 'malli.core/explain)]
      (if (validate-fn malli-schema data)
        {:valid? true}
        {:valid? false
         :explanation (explain-fn malli-schema data)}))
    (catch Exception e
      {:valid? false :error (.getMessage e)})))

;; ── Lint ────────────────────────────────────────────────────────

(defn lint-code
  "Runs clj-kondo on a code string. Returns a map with :errors and :warnings
   vectors, or nil values if the code is clean. Returns {:error msg} if
   clj-kondo is not available."
  [code]
  (try
    (let [proc (-> (ProcessBuilder. ["clj-kondo" "--lint" "-" "--lang" "clj"])
                   (.redirectErrorStream true)
                   (.start))]
      (with-open [out (.getOutputStream proc)]
        (.write out (.getBytes code "UTF-8")))
      (let [output (slurp (.getInputStream proc))
            exit   (.waitFor proc)
            lines  (str/split-lines output)
            parsed (keep (fn [line]
                           (when-let [[_ _file row col level msg]
                                      (re-find #"^(.+):(\d+):(\d+): (error|warning): (.+)$" line)]
                             {:line    (parse-long row)
                              :col     (parse-long col)
                              :level   level
                              :message msg}))
                         lines)
            errors   (filterv #(= "error" (:level %)) parsed)
            warnings (filterv #(= "warning" (:level %)) parsed)]
        {:errors   (when (seq errors) errors)
         :warnings (when (seq warnings) warnings)}))
    (catch java.io.IOException _
      {:error "clj-kondo not found on PATH"})
    (catch Exception e
      {:error (.getMessage e)})))

;; ── Test correction merging ────────────────────────────────────

(defn- split-deftests
  "Splits a string into individual (deftest ...) form strings.
   Returns a vector of {:name string :body string}."
  [code]
  (when (and code (not (str/blank? code)))
    (let [;; Match (deftest <name> ...) forms
          pattern #"(?s)\(deftest\s+(\S+)\s"
          matches (re-seq pattern code)
          names   (mapv second matches)]
      (if (empty? names)
        [{:name nil :body (str/trim code)}]
        ;; Split by deftest boundaries
        (let [parts (str/split code #"(?=\(deftest\s)")
              parts (filterv #(not (str/blank? %)) parts)]
          (mapv (fn [part]
                  (let [name (second (re-find #"\(deftest\s+(\S+)" part))]
                    {:name name :body (str/trim part)}))
                parts))))))

(defn merge-test-corrections
  "Merges corrected deftest forms into original test body.
   Corrections replace matching tests by name; new tests are appended."
  [original corrections]
  (if (or (nil? corrections) (str/blank? corrections))
    original
    (let [orig-tests (split-deftests original)
          corr-tests (split-deftests corrections)
          corr-map   (into {} (keep (fn [{:keys [name body]}]
                                      (when name [name body]))
                                    corr-tests))
          ;; Replace matching, keep non-matching
          merged     (mapv (fn [{:keys [name body]}]
                             (if (and name (contains? corr-map name))
                               (get corr-map name)
                               body))
                           orig-tests)
          ;; Find new tests (in corrections but not in original)
          orig-names (set (keep :name orig-tests))
          new-tests  (filterv (fn [{:keys [name]}]
                                (and name (not (contains? orig-names name))))
                              corr-tests)]
      (str/join "\n\n" (into merged (mapv :body new-tests))))))
