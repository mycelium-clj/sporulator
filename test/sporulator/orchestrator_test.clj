(ns sporulator.orchestrator-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [sporulator.orchestrator :as orch]
            [sporulator.store :as store]
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
;; Helpers
;; =============================================================

(def ^:private tax-cell-brief
  {:id       ":order/compute-tax"
   :doc      "Computes tax as subtotal * 0.1"
   :schema   "{:input {:subtotal :double} :output {:tax :double}}"
   :requires []
   :context  ""})

(defn- mock-llm-send-stream
  "Returns a mock session-send-stream fn that returns canned responses.
   response-fn takes the user message and returns a response string."
  [response-fn]
  (fn [session _client msg on-chunk & _]
    (swap! (:messages session) conj {:role "user" :content msg})
    (let [resp (response-fn msg)]
      (when on-chunk (on-chunk resp))
      (swap! (:messages session) conj {:role "assistant" :content resp})
      resp)))

;; =============================================================
;; generate-test-contract
;; =============================================================

(deftest generate-test-contract-test
  (testing "generates a test contract for a cell"
    (store/create-run! *store*
      {:id "test-run-1" :spec-hash "" :manifest-id "" :status "running"})
    (let [events (atom [])
          contract
          (with-redefs
            [llm/session-send-stream
             (mock-llm-send-stream
               (fn [msg]
                 (if (str/includes? (or msg "") "review")
                   ;; Self-review response
                   "ALL TESTS VERIFIED"
                   ;; Test generation response
                   "(deftest compute-tax-test\n  (testing \"basic tax computation\"\n    (is (= {:tax 10.0} (handler {} {:subtotal 100.0})))))\n")))]
            (orch/generate-test-contract
              nil ;; client (mocked)
              {:brief    tax-cell-brief
               :base-ns  "test.app"
               :store    *store*
               :run-id   "test-run-1"
               :on-event (fn [e] (swap! events conj e))
               :on-chunk (fn [_])}))]
      (is (some? contract))
      (is (= ":order/compute-tax" (:cell-id contract)))
      (is (some? (:test-code contract)))
      (is (some? (:test-body contract)))
      ;; Should have been persisted to store
      (let [tc (store/get-test-contract *store* "test-run-1" ":order/compute-tax")]
        (is (some? tc))
        (is (= "pending" (:status tc)))))))

;; =============================================================
;; implement-from-contract
;; =============================================================

(deftest implement-from-contract-test
  (testing "implements a cell from a test contract"
    (store/create-run! *store*
      {:id "impl-run-1" :spec-hash "" :manifest-id "" :status "running"})
    (let [events (atom [])
          ;; Generate a contract first (with mocked LLM)
          contract
          (with-redefs
            [llm/session-send-stream
             (mock-llm-send-stream
               (fn [_] "(deftest tax-test\n  (is (= {:tax 10.0} (handler {} {:subtotal 100.0}))))"))]
            (orch/generate-test-contract
              nil
              {:brief    tax-cell-brief
               :base-ns  "test.implapp"
               :store    *store*
               :run-id   "impl-run-1"
               :on-event (fn [_])
               :on-chunk (fn [_])}))
          ;; Now implement (with mocked LLM)
          result
          (with-redefs
            [llm/session-send-stream
             (mock-llm-send-stream
               (fn [_]
                 "```clojure\n(ns test.implapp.cells.compute-tax\n  (:require [mycelium.cell :as cell]))\n\n(cell/defcell :order/compute-tax\n  {:doc \"Computes tax as subtotal * 0.1\"\n   :input {:subtotal :double}\n   :output {:tax :double}}\n  (fn [_ data] {:tax (* (:subtotal data) 0.1)}))\n```"))]
            (orch/implement-from-contract
              nil
              {:contract contract
               :store    *store*
               :run-id   "impl-run-1"
               :on-event (fn [e] (swap! events conj e))
               :on-chunk (fn [_])
               :max-attempts 3}))]
      (is (= :ok (:status result)))
      ;; Should have cell_attempt in store
      (let [attempts (store/get-cell-attempts *store* "impl-run-1" ":order/compute-tax")]
        (is (pos? (count attempts)))))))

;; =============================================================
;; Review gate
;; =============================================================

(deftest review-gate-test
  (testing "blocks until response is delivered"
    (let [gate (orch/create-review-gate)
          result (future
                   (orch/await-review gate 1000))]
      ;; Deliver response
      (orch/deliver-review gate [{:cell-id ":test/a" :decision "approve"}])
      (let [r (deref result 2000 :timeout)]
        (is (not= :timeout r))
        (is (= 1 (count r)))
        (is (= "approve" (:decision (first r)))))))

  (testing "returns nil on timeout"
    (let [gate (orch/create-review-gate)
          result (orch/await-review gate 50)]
      (is (nil? result)))))

;; =============================================================
;; run! (end-to-end with mocks)
;; =============================================================

(deftest run-orchestrator-test
  (testing "full orchestration flow with auto-approve"
    (let [events (atom [])
          call-count (atom 0)
          result
          (with-redefs
            [llm/session-send-stream
             (mock-llm-send-stream
               (fn [msg]
                 (swap! call-count inc)
                 (cond
                   ;; Test generation
                   (and msg (str/includes? msg "Implement"))
                   "```clojure\n(ns test.orch.cells.compute-tax\n  (:require [mycelium.cell :as cell]))\n\n(cell/defcell :order/compute-tax\n  {:doc \"Computes tax\"\n   :input {:subtotal :double}\n   :output {:tax :double}}\n  (fn [_ data] {:tax (* (:subtotal data) 0.1)}))\n```"

                   ;; Test writing
                   (and msg (str/includes? msg "test"))
                   "(deftest tax-test\n  (is (= {:tax 10.0} (handler {} {:subtotal 100.0}))))"

                   ;; Self-review
                   :else
                   "ALL TESTS VERIFIED")))]
            (orch/orchestrate!
              nil ;; client
              {:leaves       [{:cell-id   ":order/compute-tax"
                               :step-name "compute-tax"
                               :doc       "Computes tax"
                               :input-schema  "{:subtotal :double}"
                               :output-schema "{:tax :double}"
                               :requires      []}]
               :base-ns      "test.orch"
               :store        *store*
               :on-event     (fn [e] (swap! events conj e))
               :on-chunk     (fn [_])
               :auto-approve? true
               :max-attempts  3}))]
      (is (= :ok (:status result)))
      ;; Should have events
      (is (pos? (count @events)))
      ;; Should have a run in store
      (is (some? (:run-id result))))))

;; =============================================================
;; lint-fix-loop
;; =============================================================

(deftest lint-fix-loop-test
  (testing "returns clean code unchanged"
    (let [code "(defn foo [x] (+ x 1))"
          r (orch/lint-fix-loop nil nil code ":test/lint-ok"
              :max-attempts 3 :on-event (fn [_]))]
      (is (= :ok (:status r)))
      (is (= code (:code r)))))

  (testing "fixes lint errors with LLM"
    (let [call-count (atom 0)
          session    (llm/create-session "lint-test" "")
          r (with-redefs [llm/session-send-stream
                          (mock-llm-send-stream
                            (fn [_msg]
                              (swap! call-count inc)
                              ;; Return fixed code
                              "```clojure\n(defn foo [x] (+ x 1))\n```"))]
              (orch/lint-fix-loop
                nil session
                "(defn foo [x] (undefined-fn x))"
                ":test/lint-fix"
                :max-attempts 3 :on-event (fn [_])))]
      (is (= :ok (:status r)))
      (is (pos? @call-count)))))

;; =============================================================
;; impl-review gate
;; =============================================================

(deftest impl-review-gate-test
  (testing "sends implementations for review and processes responses"
    (store/create-run! *store*
      {:id "impl-rev-1" :spec-hash "" :manifest-id "" :status "running"})
    (let [events (atom [])
          contract (with-redefs
                     [llm/session-send-stream
                      (mock-llm-send-stream
                        (fn [_] "(deftest t (is true))"))]
                     (orch/generate-test-contract nil
                       {:brief    tax-cell-brief
                        :base-ns  "test.implrev"
                        :store    *store*
                        :run-id   "impl-rev-1"
                        :on-event (fn [_])
                        :on-chunk (fn [_])}))
          ;; Implement it
          result (with-redefs
                   [llm/session-send-stream
                    (mock-llm-send-stream
                      (fn [_]
                        "```clojure\n(ns test.implrev.cells.compute-tax\n  (:require [mycelium.cell :as cell]))\n\n(cell/defcell :order/compute-tax\n  {:doc \"Computes tax\"\n   :input {:subtotal :double}\n   :output {:tax :double}}\n  (fn [_ data] {:tax (* (:subtotal data) 0.1)}))\n```"))]
                   (orch/implement-from-contract nil
                     {:contract contract
                      :store    *store*
                      :run-id   "impl-rev-1"
                      :on-event (fn [e] (swap! events conj e))
                      :on-chunk (fn [_])
                      :max-attempts 3
                      :on-impl-review
                      (fn [impls]
                        ;; Auto-approve all
                        (mapv (fn [i] {:cell-id (:cell-id i) :decision "approve"})
                              impls))}))]
      (is (= :ok (:status result))))))

;; =============================================================
;; resume! (orchestrator resume)
;; =============================================================

(deftest resume-orchestrator-test
  (testing "resumes from a previous run"
    ;; First, run a full orchestration
    (let [first-result
          (with-redefs
            [llm/session-send-stream
             (mock-llm-send-stream
               (fn [msg]
                 (cond
                   (and msg (str/includes? msg "Implement"))
                   "```clojure\n(ns test.resume.cells.compute-tax\n  (:require [mycelium.cell :as cell]))\n\n(cell/defcell :order/compute-tax\n  {:doc \"Computes tax\"\n   :input {:subtotal :double}\n   :output {:tax :double}}\n  (fn [_ data] {:tax (* (:subtotal data) 0.1)}))\n```"
                   (and msg (str/includes? msg "test"))
                   "(deftest tax-test\n  (is (= {:tax 10.0} (handler {} {:subtotal 100.0}))))"
                   :else "ALL TESTS VERIFIED")))]
            (orch/orchestrate! nil
              {:leaves [{:cell-id ":order/compute-tax" :step-name "compute-tax"
                         :doc "Computes tax" :input-schema "{:subtotal :double}"
                         :output-schema "{:tax :double}" :requires []}]
               :base-ns "test.resume" :store *store*
               :manifest-id ":test-resume-app"
               :on-event (fn [_]) :on-chunk (fn [_])
               :auto-approve? true :max-attempts 3}))]
      (is (= :ok (:status first-result)))
      ;; Now resume — should detect previous run
      (let [resume-result
            (with-redefs
              [llm/session-send-stream
               (mock-llm-send-stream (fn [_] "ALL TESTS VERIFIED"))]
              (orch/resume! nil
                {:manifest-id ":test-resume-app"
                 :store       *store*
                 :on-event    (fn [_])
                 :on-chunk    (fn [_])
                 :auto-approve? true}))]
        (is (some? resume-result))
        (is (= :ok (:status resume-result)))))))
