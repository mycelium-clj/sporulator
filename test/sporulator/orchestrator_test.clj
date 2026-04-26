(ns sporulator.orchestrator-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [sporulator.feedback :as fb]
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

(defmacro with-llm-mock
  "Binds both session-send-stream and session-continue-stream to the same
   mock fn, since the agent loop uses both to drive a conversation."
  [mock-form & body]
  `(let [m# ~mock-form]
     (with-redefs [llm/session-send-stream     m#
                   llm/session-continue-stream m#]
       ~@body)))

(defn- assistant-msg
  "Builds the assistant message a real session would persist for a response."
  [resp]
  (cond-> {:role "assistant" :content (:content resp)}
    (seq (:tool-calls resp))
    (assoc :tool_calls
           (mapv (fn [tc] {:id (:id tc) :type "function"
                           :function {:name (:name tc)
                                      :arguments (:arguments-json tc)}})
                 (:tool-calls resp)))))

(defn- mock-llm-send-stream
  "Mock session-send-stream / session-continue-stream for prose-only flows.
   response-fn receives the latest user message (or nil for continue) and
   returns the assistant text."
  [response-fn]
  (fn [session _client & rest-args]
    (let [user-msg (when (string? (first rest-args)) (first rest-args))]
      (when user-msg
        (swap! (:messages session) conj {:role "user" :content user-msg}))
      (let [text (response-fn user-msg)
            resp {:content text :tool-calls nil :finish-reason "stop"}]
        (swap! (:messages session) conj (assistant-msg resp))
        resp))))

(defn- tc
  "Builds a tool-call response shape for use as a canned mock response.
   `args-json` is a JSON string of the tool arguments."
  [tool-name & [args-json]]
  (let [args-json (or args-json "{}")
        parsed    (try (json/read-str args-json :key-fn keyword)
                       (catch Exception _ {}))]
    {:content       nil
     :finish-reason "tool_calls"
     :tool-calls    [{:id             (str "call_" (gensym))
                      :name           (name (keyword tool-name))
                      :arguments-json args-json
                      :arguments      parsed}]}))

(defn- mock-agent-stream
  "Mock for both llm/session-send-stream and llm/session-continue-stream when
   driving the agent loop. Returns one canned response per call (per session
   id), drawn from `turn-responses` in order."
  [turn-responses]
  (let [counters (atom {})]
    (fn [session _client & rest-args]
      (let [user-msg (when (string? (first rest-args)) (first rest-args))]
        (when user-msg
          (swap! (:messages session) conj {:role "user" :content user-msg}))
        (let [sid  (:id session)
              cur  (get @counters sid 0)
              resp (if (< cur (count turn-responses))
                     (nth turn-responses cur)
                     (last turn-responses))]
          (swap! counters assoc sid (inc cur))
          (swap! (:messages session) conj (assistant-msg resp))
          resp)))))

(defn- mock-orchestrator-stream
  "Hybrid mock: identifies agent-loop calls by the presence of a `:tools`
   kwarg in the call, and feeds those from `tool-responses`. Other (prose)
   calls get text from `respond-fn`."
  [respond-fn tool-responses]
  (let [tool-counters (atom {})]
    (fn [session _client & rest-args]
      (let [user-msg (when (string? (first rest-args)) (first rest-args))
            opts     (if user-msg (drop 2 rest-args) (drop 1 rest-args))
            tools?   (boolean (some #{:tools} opts))
            sid      (:id session)]
        (when user-msg
          (swap! (:messages session) conj {:role "user" :content user-msg}))
        (let [resp (if tools?
                     (let [cur (get @tool-counters sid 0)]
                       (swap! tool-counters assoc sid (inc cur))
                       (if (< cur (count tool-responses))
                         (nth tool-responses cur)
                         (last tool-responses)))
                     {:content       (respond-fn user-msg)
                      :tool-calls    nil
                      :finish-reason "stop"})]
          (swap! (:messages session) conj (assistant-msg resp))
          resp)))))

;; =============================================================
;; Dispatched-output detection (Phase 1 of contract-aware prompts)
;; =============================================================

(deftest dispatched-output-detector-test
  (testing "lite-form dispatched output (map values)"
    (is (orch/dispatched-output?
          {:success {:validated-handle :string}
           :failure {:error :string}})))

  (testing "Malli-form dispatched output (vector values)"
    (is (orch/dispatched-output?
          {:success [:map [:n :int]]
           :failure [:map [:e :string]]})))

  (testing "single-transition output"
    (is (orch/dispatched-output?
          {:success {:n :int}})))

  (testing "flat output is NOT dispatched"
    (is (not (orch/dispatched-output? {:n :int :x :string})))
    (is (not (orch/dispatched-output? {:status :keyword :total :double}))))

  (testing "edge cases"
    (is (not (orch/dispatched-output? nil)))
    (is (not (orch/dispatched-output? {})))
    (is (not (orch/dispatched-output? [:map [:k :v]])))
    (is (not (orch/dispatched-output? "string")))
    (is (not (orch/dispatched-output? {:k {}}))         "empty map values shouldn't count")))

(deftest turn-budget-for-test
  (testing "cells without :requires stay at 15 turns"
    (is (= 15 (#'orch/turn-budget-for {:requires []})))
    (is (= 15 (#'orch/turn-budget-for {:requires nil})))
    (is (= 15 (#'orch/turn-budget-for {}))))

  (testing "cells with any :requires get 25 turns"
    ;; DB-aware (and other resource-aware) cells need more turns to
    ;; write helpers, write handler, debug resource interaction, and
    ;; converge. Phase 4 validation: PE/RLH stagnated 15-turn budgets
    ;; while pure cells converged in <20 calls.
    (is (= 25 (#'orch/turn-budget-for {:requires [:db]})))
    (is (= 25 (#'orch/turn-budget-for {:requires [:db :http]})))))

(deftest build-test-prompt-error-string-block-test
  (testing "test-gen prompt warns against asserting on exact error strings"
    ;; When the brief doesn't pin specific wording, test-gen LLMs
    ;; sometimes invent verbatim error messages and assert on them with
    ;; equality. The implementor then has no way to know what string to
    ;; produce and thrashes (Phase 4 validation 2026-04-26 — validate-
    ;; message stagnated chasing "Message must be non-empty and at most
    ;; 500 characters." that the brief never specified).
    (let [prompt (#'orch/build-test-prompt
                   {:id "x/y"
                    :doc "Validates input. Returns :error on failure."
                    :schema "{:input {:k :string} :output {:success {:n :int} :failure {:error :string}}}"
                    :requires []
                    :resource-docs nil
                    :context nil})]
      (is (or (str/includes? prompt "exact error string")
              (str/includes? prompt "verbatim")
              (str/includes? prompt "string?"))
          "must guide error-path tests away from hardcoded equality"))))

(deftest build-test-prompt-jdbc-block-test
  (testing "cells requiring :db get next.jdbc qualified-key guidance"
    ;; next.jdbc/execute! returns rows with NAMESPACED-keyword keys by
    ;; default (e.g. {:guestbook/id 1}). Without warning the test-gen
    ;; LLM writes assertions like (:id row) which return nil → tests
    ;; become unsatisfiable and the implementor stagnates.
    (let [prompt (#'orch/build-test-prompt
                   {:id "guestbook/persist-entry"
                    :doc "Inserts a row."
                    :schema "{:input {:k :string} :output {:id :int}}"
                    :requires [:db]
                    :resource-docs nil
                    :context nil})]
      (is (str/includes? prompt "next.jdbc/execute!")
          "JDBC block must mention next.jdbc/execute!")
      (is (or (str/includes? prompt "qualified")
              (str/includes? prompt "as-unqualified-maps"))
          "JDBC block must explain qualified-key default or builder-fn workaround")))

  (testing "cells without :db do NOT get the JDBC block"
    (let [prompt (#'orch/build-test-prompt
                   {:id "x/y"
                    :doc "..."
                    :schema "{:input {:n :int} :output {:n :int}}"
                    :requires []
                    :resource-docs nil
                    :context nil})]
      (is (not (str/includes? prompt "as-unqualified-maps"))
          "non-db cells should not be given JDBC builder-fn guidance"))))

(deftest parse-schema-output-test
  (testing "parses a normal brief schema string"
    (is (= {:n :int}
           (orch/parse-schema-output "{:input {:x :int} :output {:n :int}}"))))

  (testing "parses a dispatched output schema"
    (is (= {:success {:validated-handle :string}
            :failure {:error :string}}
           (orch/parse-schema-output
             "{:input {:handle :string} :output {:success {:validated-handle :string} :failure {:error :string}}}"))))

  (testing "blank or unparseable returns nil"
    (is (nil? (orch/parse-schema-output nil)))
    (is (nil? (orch/parse-schema-output "")))
    (is (nil? (orch/parse-schema-output "}{garbage")))))

;; =============================================================
;; generate-test-contract
;; =============================================================

(deftest generate-test-contract-test
  (testing "generates a test contract for a cell"
    (store/create-run! *store*
      {:id "test-run-1" :spec-hash "" :manifest-id "" :status "running"})
    (let [events (atom [])
          contract
          (with-llm-mock
            (mock-llm-send-stream
              (fn [msg]
                (if (str/includes? (or msg "") "review")
                  "ALL TESTS VERIFIED"
                  "(deftest compute-tax-test\n  (testing \"basic tax computation\"\n    (is (= {:tax 10.0} (handler {} {:subtotal 100.0})))))\n")))
            (orch/generate-test-contract
              nil
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
          contract
          (with-llm-mock
            (mock-llm-send-stream
              (fn [_] "(deftest tax-test\n  (is (= {:tax 10.0} (handler {} {:subtotal 100.0}))))"))
            (orch/generate-test-contract
              nil
              {:brief    tax-cell-brief
               :base-ns  "test.implapp"
               :store    *store*
               :run-id   "impl-run-1"
               :on-event (fn [_])
               :on-chunk (fn [_])}))
          result
          (with-llm-mock
            (mock-agent-stream
              [(tc "write_file" "{\"path\":\"handler.clj\",\"content\":\"(fn [_ data] {:tax (* (:subtotal data) 0.1)})\"}")
               (tc "run_tests")
               (tc "complete")])
            (orch/implement-from-contract
              nil
              {:contract contract
               :store    *store*
               :run-id   "impl-run-1"
               :on-event (fn [e] (swap! events conj e))
               :on-chunk (fn [_])
               :max-attempts 5}))]
      (is (= :ok (:status result))))))

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
          (with-llm-mock
            (mock-orchestrator-stream
              (fn [msg]
                (swap! call-count inc)
                (cond
                  (and msg (str/includes? msg "test"))
                  "(deftest tax-test\n  (is (= {:tax 10.0} (handler {} {:subtotal 100.0}))))"
                  :else
                  "ALL TESTS VERIFIED"))
              [(tc "write_file" "{\"path\":\"handler.clj\",\"content\":\"(fn [_ data] {:tax (* (:subtotal data) 0.1)})\"}")
               (tc "run_tests")
               (tc "complete")])
            (orch/orchestrate!
              nil
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
               :max-attempts  5}))]
      (is (= "ok" (get result "status")))
      (is (pos? (count @events)))
      (is (some? (get result "run_id"))))))

;; =============================================================
;; Diff-aware orchestrate
;; =============================================================

(def ^:private tax-leaf
  {:cell-id   ":order/compute-tax"
   :step-name "compute-tax"
   :doc       "Computes tax"
   :input-schema  "{:subtotal :double}"
   :output-schema "{:tax :double}"
   :requires      []})

(def ^:private tax-manifest
  {:id :order/tax-flow
   :pipeline [:compute-tax]
   :cells {:compute-tax {:id :order/compute-tax
                         :doc "Computes tax"
                         :schema {:input  {:subtotal :double}
                                  :output {:tax :double}}
                         :on-error nil
                         :requires []}}})

(defn- run-tax-orchestration
  "Helper: drives orchestrate! with the tax cell mock through one full
   test+impl round, returning the result and the mock call counter."
  [{:keys [manifest manifest-id leaves prompt-counter]
    :or {manifest tax-manifest manifest-id "order/tax-flow"
         leaves [tax-leaf] prompt-counter (atom 0)}}]
  (let [result
        (with-llm-mock
          (mock-orchestrator-stream
            (fn [msg]
              (swap! prompt-counter inc)
              (cond
                (and msg (str/includes? msg "test"))
                "(deftest tax-test\n  (is (= {:tax 10.0} (handler {} {:subtotal 100.0}))))"
                :else "ALL TESTS VERIFIED"))
            [(tc "write_file" "{\"path\":\"handler.clj\",\"content\":\"(fn [_ data] {:tax (* (:subtotal data) 0.1)})\"}")
             (tc "run_tests")
             (tc "complete")])
          (orch/orchestrate! nil
            {:leaves        leaves
             :manifest      manifest
             :base-ns       "test.diff"
             :store         *store*
             :on-event      (fn [_])
             :on-chunk      (fn [_])
             :auto-approve? true
             :max-attempts  5
             :manifest-id   manifest-id}))]
    {:result result
     :prompt-count @prompt-counter}))

(deftest fresh-run-saves-green-snapshot-test
  (testing "first orchestration with a manifest-id records a green snapshot"
    (let [{:keys [result]} (run-tax-orchestration {})]
      (is (= "ok" (get result "status")))
      (is (= [:order/compute-tax] (vec (:added (:diff result)))))
      (let [snap (store/get-latest-green-snapshot *store* "order/tax-flow")]
        (is (some? snap))
        (is (str/includes? (:body snap) ":order/compute-tax"))))))

(deftest rerun-identical-manifest-carries-over-test
  (testing "re-orchestrating an unchanged manifest uses zero LLM calls"
    ;; First run lays down the snapshot + cells.
    (run-tax-orchestration {})
    (let [counter (atom 0)
          {:keys [result prompt-count]}
          (run-tax-orchestration {:prompt-counter counter})]
      (is (= "ok" (get result "status")))
      (is (zero? prompt-count) "no LLM prompts should be issued for an all-carry-over re-run")
      (is (= 1 (count (:unchanged (:diff result)))))
      (is (= [:order/compute-tax] (vec (:unchanged (:diff result)))))
      ;; carry-over result is included in :passed (as the keyword cell-id)
      (is (= [:order/compute-tax] (get result "passed"))))))

(deftest schema-change-triggers-regen-test
  (testing "after schema changes, the cell is regenerated even though it was previously green"
    ;; First run: baseline.
    (run-tax-orchestration {})
    ;; Second run: the cell's output schema moves.
    (let [new-manifest (assoc-in tax-manifest [:cells :compute-tax :schema :output]
                                  {:tax :double :rate :double})
          new-leaf (assoc tax-leaf :output-schema "{:tax :double :rate :double}")
          counter (atom 0)
          {:keys [result prompt-count]}
          (run-tax-orchestration {:manifest new-manifest
                                  :leaves [new-leaf]
                                  :prompt-counter counter})]
      (is (= "ok" (get result "status")))
      (is (pos? prompt-count) "schema-changed cell should drive LLM calls")
      (is (= [:order/compute-tax] (vec (:schema-changed (:diff result)))))
      (is (= [] (vec (:unchanged (:diff result))))))))

(deftest removed-cell-is-deprecated-test
  (testing "cells removed from the manifest are deprecated in the store"
    ;; First run: lays down cell + snapshot.
    (run-tax-orchestration {})
    (is (false? (store/cell-deprecated? *store* "order/compute-tax")))
    ;; Second run: empty manifest (cell removed).
    (let [empty-manifest (-> tax-manifest
                             (update :cells dissoc :compute-tax)
                             (assoc :pipeline []))
          counter (atom 0)
          {:keys [result prompt-count]}
          (run-tax-orchestration {:manifest empty-manifest
                                  :leaves []
                                  :prompt-counter counter})]
      (is (= "ok" (get result "status")))
      (is (zero? prompt-count) "no LLM calls when only deletes are pending")
      (is (= [:order/compute-tax] (vec (:removed (:diff result)))))
      (is (true? (store/cell-deprecated? *store* "order/compute-tax"))))))

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
          r (with-llm-mock
              (mock-llm-send-stream
                (fn [_msg]
                  (swap! call-count inc)
                  "```clojure\n(defn foo [x] (+ x 1))\n```"))
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
          contract (with-llm-mock
                     (mock-llm-send-stream
                       (fn [_] "(deftest t (is true))"))
                     (orch/generate-test-contract nil
                       {:brief    tax-cell-brief
                        :base-ns  "test.implrev"
                        :store    *store*
                        :run-id   "impl-rev-1"
                        :on-event (fn [_])
                        :on-chunk (fn [_])}))
          result (with-llm-mock
                   (mock-agent-stream
                     [(tc "write_file" "{\"path\":\"handler.clj\",\"content\":\"(fn [_ data] {:tax (* (:subtotal data) 0.1)})\"}")
                      (tc "run_tests")
                      (tc "complete")])
                   (orch/implement-from-contract nil
                     {:contract contract
                      :store    *store*
                      :run-id   "impl-rev-1"
                      :on-event (fn [e] (swap! events conj e))
                      :on-chunk (fn [_])
                      :max-attempts 5
                      :on-impl-review
                      (fn [impls]
                        (mapv (fn [i] {:cell-id (:cell-id i) :decision "approve"})
                              impls))}))]
      (is (= :ok (:status result))))))

;; =============================================================
;; resume! (orchestrator resume)
;; =============================================================

(deftest resume-orchestrator-test
  (testing "resumes from a previous run"
    (let [first-result
          (with-llm-mock
            (mock-orchestrator-stream
              (fn [msg]
                (cond
                  (and msg (str/includes? msg "test"))
                  "(deftest tax-test\n  (is (= {:tax 10.0} (handler {} {:subtotal 100.0}))))"
                  :else "ALL TESTS VERIFIED"))
              [(tc "write_file" "{\"path\":\"handler.clj\",\"content\":\"(fn [_ data] {:tax (* (:subtotal data) 0.1)})\"}")
               (tc "run_tests")
               (tc "complete")])
            (orch/orchestrate! nil
              {:leaves [{:cell-id ":order/compute-tax" :step-name "compute-tax"
                         :doc "Computes tax" :input-schema "{:subtotal :double}"
                         :output-schema "{:tax :double}" :requires []}]
               :base-ns "test.resume" :store *store*
               :manifest-id ":test-resume-app"
               :on-event (fn [_]) :on-chunk (fn [_])
               :auto-approve? true :max-attempts 5}))]
      (is (= "ok" (get first-result "status")))
      (let [resume-result
            (with-llm-mock
              (mock-llm-send-stream (fn [_] "ALL TESTS VERIFIED"))
              (orch/resume! nil
                {:manifest-id ":test-resume-app"
                 :store       *store*
                 :on-event    (fn [_])
                 :on-chunk    (fn [_])
                 :auto-approve? true}))]
        (is (some? resume-result))
        (is (= "ok" (get resume-result "status")))))))

;; =============================================================
;; feedback-loop
;; =============================================================

(deftest feedback-loop-test
  (testing "succeeds on first attempt when validation passes"
    (let [call-count (atom 0)
          result
          (with-llm-mock
            (mock-llm-send-stream (fn [_] (swap! call-count inc) "good-value"))
            (fb/feedback-loop
              {:client      nil
               :session     (llm/create-session "test-fb" "")
               :initial-msg "generate something"
               :validate-fn (fn [v] (if (= v "good-value") {:ok v} {:error "bad"}))
               :error-msg-fn (fn [v e] (str "Fix: " e))
               :max-attempts 3}))]
      (is (= :ok (:status result)))
      (is (= "good-value" (:result result)))
      (is (= 1 (:attempts result)))
      (is (= 1 @call-count))))

  (testing "retries and succeeds on second attempt"
    (let [call-count (atom 0)
          result
          (with-llm-mock
            (mock-llm-send-stream
              (fn [_]
                (swap! call-count inc)
                (if (= 1 @call-count) "bad-value" "good-value")))
            (fb/feedback-loop
              {:client      nil
               :session     (llm/create-session "test-fb2" "")
               :initial-msg "generate something"
               :validate-fn (fn [v] (if (= v "good-value") {:ok v} {:error "not good"}))
               :error-msg-fn (fn [v e] (str "You returned '" v "'. " e ". Try again."))
               :max-attempts 3}))]
      (is (= :ok (:status result)))
      (is (= "good-value" (:result result)))
      (is (= 2 (:attempts result)))))

  (testing "fails after max attempts"
    (let [result
          (with-llm-mock
            (mock-llm-send-stream (fn [_] "always-bad"))
            (fb/feedback-loop
              {:client      nil
               :session     (llm/create-session "test-fb3" "")
               :initial-msg "generate something"
               :validate-fn (fn [_] {:error "still wrong"})
               :error-msg-fn (fn [_ e] (str "Fix: " e))
               :max-attempts 2}))]
      (is (= :error (:status result)))
      (is (= "still wrong" (:error result)))
      (is (= "always-bad" (:last-value result)))
      (is (= 3 (:attempts result)))))

  (testing "extract-fn transforms LLM output before validation"
    (let [result
          (with-llm-mock
            (mock-llm-send-stream (fn [_] "```clojure\n42\n```"))
            (fb/feedback-loop
              {:client      nil
               :session     (llm/create-session "test-fb4" "")
               :initial-msg "give me a number"
               :extract-fn  (fn [raw] (str/trim (str/replace raw #"```\w*\n?|```" "")))
               :validate-fn (fn [v] (if (= v "42") {:ok 42} {:error "not 42"}))
               :error-msg-fn (fn [_ e] e)
               :max-attempts 1}))]
      (is (= :ok (:status result)))
      (is (= 42 (:result result))))))
