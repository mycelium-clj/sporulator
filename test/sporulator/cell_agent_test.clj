(ns sporulator.cell-agent-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [sporulator.agent-loop :as agent-loop]
            [sporulator.cell-agent :as ca]
            [sporulator.store :as store]
            [sporulator.eval :as ev]
            [sporulator.llm :as llm]))

;; =============================================================
;; Fixture
;; =============================================================

(def ^:dynamic *store* nil)

(defn with-store [f]
  (let [s (store/open ":memory:")]
    (try
      (binding [*store* s]
        (f))
      (finally
        (store/close s)))))

(use-fixtures :each with-store)

;; =============================================================
;; build-cell-prompt
;; =============================================================

(deftest build-cell-prompt-test
  (testing "includes all brief fields"
    (let [prompt (ca/build-cell-prompt
                   {:id       ":order/compute-tax"
                    :doc      "Computes tax based on subtotal and state"
                    :schema   "{:input {:subtotal :double :state :string} :output {:tax :double}}"
                    :requires ["db"]
                    :context  "Receives data from :order/parse"})]
      (is (str/includes? prompt ":order/compute-tax"))
      (is (str/includes? prompt "Computes tax"))
      (is (str/includes? prompt ":subtotal"))
      (is (str/includes? prompt "db"))
      (is (str/includes? prompt "Receives data from"))))

  (testing "works with minimal brief"
    (let [prompt (ca/build-cell-prompt
                   {:id  ":math/double"
                    :doc "Doubles the input"})]
      (is (str/includes? prompt ":math/double"))
      (is (str/includes? prompt "Doubles"))
      (is (str/includes? prompt "none")))))

;; =============================================================
;; build-result
;; =============================================================

(deftest build-result-test
  (testing "extracts defcell from response"
    (let [response "Here is the cell:\n```clojure\n(cell/defcell :test/foo\n  {:input {:x :int} :output {:y :int}}\n  (fn [_ data] {:y (* 2 (:x data))}))\n```"
          result (ca/build-result ":test/foo" response)]
      (is (= ":test/foo" (:cell-id result)))
      (is (some? (:code result)))
      (is (= response (:raw result)))))

  (testing "handles response without defcell"
    (let [result (ca/build-result ":test/bar" "No code here")]
      (is (= ":test/bar" (:cell-id result)))
      (is (nil? (:code result))))))

;; =============================================================
;; save-cell!
;; =============================================================

(deftest save-cell-test
  (testing "saves cell result to store"
    (let [result {:cell-id ":math/double"
                  :code    "(fn [_ data] {:result (* 2 (:x data))})"
                  :raw     "full response"}
          version (ca/save-cell! *store* result
                    {:schema     "{:input {:x :int} :output {:result :int}}"
                     :doc        "Doubles"
                     :created-by "cell-agent"})]
      (is (= 1 version))
      ;; Verify in store
      (let [cell (store/get-latest-cell *store* ":math/double")]
        (is (some? cell))
        (is (= "(fn [_ data] {:result (* 2 (:x data))})" (:handler cell)))
        (is (= "cell-agent" (:created-by cell)))))))

;; =============================================================
;; mock agent helpers
;; =============================================================

(defn- mock-agent-success
  "Returns a mock agent-loop/run! that immediately succeeds."
  [code raw session]
  {:status  :ok
   :cell-id :cell-under-test
   :code    code
   :raw     raw
   :session session})

(defn- mock-agent-error
  "Returns a mock agent-loop/run! that produces an error."
  [error-msg session]
  {:status  :error
   :cell-id :cell-under-test
   :error   error-msg
   :session session})

(deftest implement-with-feedback-test
  (testing "succeeds when agent loop returns ok"
    (let [events (atom [])
          session (llm/create-session "mock-ok" "prompt")
          result (with-redefs [agent-loop/run!
                               (fn [opts]
                                 (let [evt-fn (:on-event opts)]
                                   (evt-fn {"status" "started"})
                                   (evt-fn {"status" "done"}))
                                 (mock-agent-success
                                   "(ns test.cells.mock-impl ...)"
                                   "(fn [_ data] {:doubled (* 2 (:x data))})"
                                   session))]
                   (ca/implement-with-feedback
                     nil
                     {:id     ":test/mock-impl"
                      :doc    "Doubles the input"
                      :schema "{:input {:x :int} :output {:doubled :int}}"}
                     (fn [_chunk])
                     :on-feedback (fn [e] (swap! events conj e))))]
      (is (= :ok (:status result)))
      (is (= ":test/mock-impl" (:cell-id result)))
      (is (some? (:code result)))
      (is (= session (:session result)))
      (is (some #(= "done" (:event-type %)) @events))))

  (testing "returns error when agent loop fails"
    (let [events (atom [])
          session (llm/create-session "mock-err" "prompt")
          result (with-redefs [agent-loop/run!
                               (fn [opts]
                                 (let [evt-fn (:on-event opts)]
                                   (evt-fn {"status" "started"}))
                                 (mock-agent-error "broken cell" session))]
                   (ca/implement-with-feedback
                     nil
                     {:id     ":test/broken-cell"
                      :doc    "Always broken"
                      :schema "{:input {:x :int} :output {:y :int}}"}
                     (fn [_chunk])
                     :on-feedback (fn [e] (swap! events conj e))))]
      (is (= :error (:status result)))
      (is (= "broken cell" (:error result)))
      (is (some #(= "started" (:event-type %)) @events))))

  (testing "passes turn-budget from max-attempts"
    (let [opts-captured (atom nil)]
      (with-redefs [agent-loop/run! (fn [opts] (reset! opts-captured opts)
                                      {:status :ok :cell-id :x :code "()" :raw "()" :session nil})]
        (ca/implement-with-feedback nil
          {:id ":test/x" :doc "X" :schema "{}"}
          (fn [_])
          :max-attempts 7))
      (is (= 7 (:turn-budget @opts-captured))))))

;; =============================================================
;; implement-cells (parallel)
;; =============================================================

(deftest implement-cells-test
  (testing "implements multiple cells in parallel"
    (let [call-count (atom 0)
          results (with-redefs [agent-loop/run!
                                (fn [opts]
                                  (swap! call-count inc)
                                  {:status  :ok
                                   :cell-id (:cell-id opts)
                                   :code    (str "source for " (:cell-id opts))
                                   :raw     "(fn [_ data] {:y (:x data)})"
                                   :session nil})]
                    (ca/implement-cells
                      nil
                      [{:id ":test/parallel-a" :doc "Cell A" :schema "{:input {:x :int} :output {:y :int}}"}
                       {:id ":test/parallel-b" :doc "Cell B" :schema "{:input {:x :int} :output {:y :int}}"}]))]
      (is (= 2 (count results)))
      (is (every? #(= :ok (:status %)) results))
      (is (= 2 @call-count)))))
