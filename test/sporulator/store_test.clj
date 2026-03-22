(ns sporulator.store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sporulator.store :as store]))

;; =============================================================
;; Fixture: in-memory SQLite store per test
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
;; Cell tests — translated from Go store_test.go
;; =============================================================

(deftest save-cell-auto-versions-test
  (testing "auto-increments version"
    (let [v1 (store/save-cell! *store*
               {:id ":order/compute-tax"
                :handler "(fn [_ data] {:tax (* (:subtotal data) 0.1)})"
                :schema "{:input {:subtotal :double} :output {:tax :double}}"})
          v2 (store/save-cell! *store*
               {:id ":order/compute-tax"
                :handler "(fn [_ data] {:tax (* (:subtotal data) (:tax-rate data))})"
                :schema "{:input {:subtotal :double :tax-rate :double} :output {:tax :double}}"})]
      (is (= 1 v1))
      (is (= 2 v2)))))

(deftest get-cell-test
  (testing "retrieves by id and version"
    (store/save-cell! *store*
      {:id ":math/double"
       :handler "(fn [_ data] {:result (* 2 (:x data))})"
       :schema "{:input {:x :int} :output {:result :int}}"
       :doc "Doubles the input"
       :created-by "human"})
    (let [cell (store/get-cell *store* ":math/double" 1)]
      (is (some? cell))
      (is (= "Doubles the input" (:doc cell)))
      (is (= "human" (:created-by cell)))))

  (testing "returns nil for missing cell"
    (is (nil? (store/get-cell *store* ":nope/missing" 1)))))

(deftest get-latest-cell-test
  (testing "returns highest version"
    (store/save-cell! *store* {:id ":app/step" :handler "v1"})
    (store/save-cell! *store* {:id ":app/step" :handler "v2"})
    (store/save-cell! *store* {:id ":app/step" :handler "v3"})
    (let [cell (store/get-latest-cell *store* ":app/step")]
      (is (= 3 (:version cell)))
      (is (= "v3" (:handler cell))))))

(deftest list-cells-test
  (testing "lists latest version per cell"
    (store/save-cell! *store* {:id ":app/a" :handler "a1" :doc "Cell A"})
    (store/save-cell! *store* {:id ":app/a" :handler "a2" :doc "Cell A v2"})
    (store/save-cell! *store* {:id ":app/b" :handler "b1" :doc "Cell B"})
    (let [cells (store/list-cells *store*)]
      (is (= 2 (count cells)))
      (is (= ":app/a" (:id (first cells))))
      (is (= 2 (:latest-version (first cells))))
      (is (= "Cell A v2" (:doc (first cells))))
      (is (= ":app/b" (:id (second cells))))
      (is (= 1 (:latest-version (second cells)))))))

(deftest get-cell-history-test
  (testing "returns all versions newest first"
    (store/save-cell! *store* {:id ":app/x" :handler "h1" :created-by "human"})
    (store/save-cell! *store* {:id ":app/x" :handler "h2" :created-by "deepseek"})
    (store/save-cell! *store* {:id ":app/x" :handler "h3" :created-by "human"})
    (let [history (store/get-cell-history *store* ":app/x")]
      (is (= 3 (count history)))
      (is (= 3 (:version (first history))))
      (is (= "human" (:created-by (first history))))
      (is (= 1 (:version (last history)))))))

;; =============================================================
;; Manifest tests
;; =============================================================

(deftest save-and-get-manifest-test
  (let [body "{:id :todo-app :cells {:start :todo/parse} :pipeline [:start]}"
        v (store/save-manifest! *store*
            {:id ":todo-app" :body body :created-by "graph-agent"})]
    (is (= 1 v))
    (let [m (store/get-manifest *store* ":todo-app" 1)]
      (is (= body (:body m)))
      (is (= "graph-agent" (:created-by m))))))

(deftest get-latest-manifest-test
  (store/save-manifest! *store* {:id ":app" :body "v1"})
  (store/save-manifest! *store* {:id ":app" :body "v2"})
  (let [m (store/get-latest-manifest *store* ":app")]
    (is (= 2 (:version m)))
    (is (= "v2" (:body m)))))

(deftest get-manifest-not-found-test
  (is (nil? (store/get-latest-manifest *store* ":nope"))))

(deftest list-manifests-test
  (store/save-manifest! *store* {:id ":app-a" :body "a"})
  (store/save-manifest! *store* {:id ":app-b" :body "b1"})
  (store/save-manifest! *store* {:id ":app-b" :body "b2"})
  (let [ms (store/list-manifests *store*)]
    (is (= 2 (count ms)))
    (is (= ":app-a" (:id (first ms))))
    (is (= 1 (:latest-version (first ms))))
    (is (= ":app-b" (:id (second ms))))
    (is (= 2 (:latest-version (second ms))))))

;; =============================================================
;; Cell pinning tests
;; =============================================================

(deftest pin-cell-version-test
  (store/save-cell! *store* {:id ":app/a" :handler "a1"})
  (store/save-cell! *store* {:id ":app/a" :handler "a2"})
  (store/save-cell! *store* {:id ":app/b" :handler "b1"})
  (store/save-manifest! *store* {:id ":workflow" :body "{}"})
  (store/pin-cell-version! *store* ":workflow" 1 ":app/a" 2)
  (store/pin-cell-version! *store* ":workflow" 1 ":app/b" 1)
  (let [pins (store/get-pinned-cells *store* ":workflow" 1)]
    (is (= 2 (count pins)))
    (is (= ":app/a" (:cell-id (first pins))))
    (is (= 2 (:cell-version (first pins))))
    (is (= ":app/b" (:cell-id (second pins))))
    (is (= 1 (:cell-version (second pins))))))

(deftest pin-cell-version-update-test
  (store/save-cell! *store* {:id ":app/a" :handler "a1"})
  (store/save-cell! *store* {:id ":app/a" :handler "a2"})
  (store/save-manifest! *store* {:id ":wf" :body "{}"})
  (store/pin-cell-version! *store* ":wf" 1 ":app/a" 1)
  (store/pin-cell-version! *store* ":wf" 1 ":app/a" 2)
  (let [pins (store/get-pinned-cells *store* ":wf" 1)]
    (is (= 1 (count pins)))
    (is (= 2 (:cell-version (first pins))))))

;; =============================================================
;; Test results
;; =============================================================

(deftest save-and-get-test-results-test
  (store/save-cell! *store* {:id ":math/add" :handler "h"})
  (store/save-test-result! *store*
    {:cell-id ":math/add" :cell-version 1
     :input "{:x 1 :y 2}" :expected "{:sum 3}" :actual "{:sum 3}" :passed? true})
  (store/save-test-result! *store*
    {:cell-id ":math/add" :cell-version 1
     :input "{:x 1 :y 2}" :expected "{:sum 3}" :actual "{:sum 4}" :passed? false
     :error "mismatch"})
  (let [results (store/get-test-results *store* ":math/add" 1)]
    (is (= 2 (count results)))
    ;; Newest first
    (is (not (:passed? (first results))))
    (is (= "mismatch" (:error (first results))))
    (is (:passed? (second results)))))

(deftest get-latest-test-results-test
  (store/save-cell! *store* {:id ":app/x" :handler "h1"})
  (store/save-cell! *store* {:id ":app/x" :handler "h2"})
  (store/save-test-result! *store*
    {:cell-id ":app/x" :cell-version 1 :passed? true})
  (store/save-test-result! *store*
    {:cell-id ":app/x" :cell-version 2 :passed? false :error "schema"})
  (let [results (store/get-latest-test-results *store* ":app/x" 10)]
    (is (= 2 (count results)))
    (is (= 2 (:cell-version (first results))))))

;; =============================================================
;; Orchestration runs
;; =============================================================

(deftest orchestration-run-crud-test
  (store/create-run! *store*
    {:id "run-1" :spec-hash "abc" :manifest-id ":order/placement" :status "running"})
  (let [run (store/get-run *store* "run-1")]
    (is (some? run))
    (is (= "running" (:status run)))
    (is (= ":order/placement" (:manifest-id run))))
  ;; Update status
  (store/update-run-status! *store* "run-1" "completed")
  (let [run (store/get-run *store* "run-1")]
    (is (= "completed" (:status run))))
  ;; Update tree
  (store/update-run-tree! *store* "run-1" "completed" "{\"step\":\"root\"}")
  (let [run (store/get-run *store* "run-1")]
    (is (= "{\"step\":\"root\"}" (:tree-json run)))))

(deftest get-run-not-found-test
  (is (nil? (store/get-run *store* "nope"))))

;; =============================================================
;; Cell attempts
;; =============================================================

(deftest cell-attempts-test
  (store/create-run! *store*
    {:id "run-2" :spec-hash "abc" :manifest-id ":wf" :status "running"})
  (store/save-cell-attempt! *store*
    {:run-id "run-2" :cell-id ":order/tax" :attempt-type "test"
     :attempt-number 1 :code "(fn [r d] {})" :output "FAIL" :passed? false})
  (store/save-cell-attempt! *store*
    {:run-id "run-2" :cell-id ":order/tax" :attempt-type "test"
     :attempt-number 2 :code "(fn [r d] {:tax 1})" :output "OK" :passed? true})
  (let [attempts (store/get-cell-attempts *store* "run-2" ":order/tax")]
    (is (= 2 (count attempts)))
    (is (= 1 (:attempt-number (first attempts))))
    (is (= 2 (:attempt-number (second attempts)))))
  ;; Run summary
  (let [summary (store/get-run-summary *store* "run-2")]
    (is (true? (get summary ":order/tax")))))

;; =============================================================
;; Test contracts
;; =============================================================

(deftest test-contract-crud-test
  (store/create-run! *store*
    {:id "run-tc1" :spec-hash "abc" :manifest-id ":order/placement" :status "running"})
  ;; Save
  (store/save-test-contract! *store*
    {:run-id "run-tc1" :cell-id ":order/compute-tax"
     :test-code "(ns test-ns ...)" :test-body "(deftest test-tax ...)"
     :review-notes "ALL TESTS VERIFIED" :status "pending" :revision 0})
  ;; Get
  (let [tc (store/get-test-contract *store* "run-tc1" ":order/compute-tax")]
    (is (some? tc))
    (is (= "pending" (:status tc)))
    (is (= "(deftest test-tax ...)" (:test-body tc)))
    (is (= 0 (:revision tc))))
  ;; Upsert
  (store/save-test-contract! *store*
    {:run-id "run-tc1" :cell-id ":order/compute-tax"
     :test-code "(ns test-ns-v2 ...)" :test-body "(deftest test-tax-v2 ...)"
     :review-notes "Fixed arithmetic" :status "pending" :revision 1
     :feedback "tax rate was wrong"})
  (let [tc (store/get-test-contract *store* "run-tc1" ":order/compute-tax")]
    (is (= 1 (:revision tc)))
    (is (= "(deftest test-tax-v2 ...)" (:test-body tc)))
    (is (= "tax rate was wrong" (:feedback tc)))))

(deftest update-test-contract-status-test
  (store/create-run! *store*
    {:id "run-tc2" :spec-hash "abc" :manifest-id ":wf" :status "running"})
  (store/save-test-contract! *store*
    {:run-id "run-tc2" :cell-id ":order/validate" :status "pending"})
  (store/update-test-contract-status! *store* "run-tc2" ":order/validate" "approved")
  (let [tc (store/get-test-contract *store* "run-tc2" ":order/validate")]
    (is (= "approved" (:status tc)))
    (is (not (clojure.string/blank? (:approved-at tc))))))

(deftest get-approved-test-contracts-test
  (store/create-run! *store*
    {:id "run-tc3" :spec-hash "abc" :manifest-id ":wf" :status "running"})
  (store/save-test-contract! *store*
    {:run-id "run-tc3" :cell-id ":order/a" :status "approved"
     :test-code "test-a" :test-body "body-a"})
  (store/save-test-contract! *store*
    {:run-id "run-tc3" :cell-id ":order/b" :status "pending"
     :test-code "test-b" :test-body "body-b"})
  (store/save-test-contract! *store*
    {:run-id "run-tc3" :cell-id ":order/c" :status "approved"
     :test-code "test-c" :test-body "body-c"})
  (store/save-test-contract! *store*
    {:run-id "run-tc3" :cell-id ":order/d" :status "skipped"})
  (let [approved (store/get-approved-test-contracts *store* "run-tc3")]
    (is (= 2 (count approved)))
    (is (= ":order/a" (:cell-id (first approved))))
    (is (= ":order/c" (:cell-id (second approved))))))

(deftest get-test-contract-not-found-test
  (is (nil? (store/get-test-contract *store* "no-run" ":no/cell"))))

(deftest get-test-contracts-test
  (store/create-run! *store*
    {:id "run-tc4" :spec-hash "abc" :manifest-id ":wf" :status "running"})
  (store/save-test-contract! *store*
    {:run-id "run-tc4" :cell-id ":order/x" :status "pending"})
  (store/save-test-contract! *store*
    {:run-id "run-tc4" :cell-id ":order/y" :status "approved"})
  (let [all (store/get-test-contracts *store* "run-tc4")]
    (is (= 2 (count all)))))

;; =============================================================
;; Chat sessions
;; =============================================================

(deftest create-and-get-chat-session-test
  (testing "creates a session and retrieves it"
    (store/create-chat-session! *store* "sess-1" "graph")
    (let [s (store/get-chat-session *store* "sess-1")]
      (is (some? s))
      (is (= "sess-1" (:id s)))
      (is (= "graph" (:agent-type s)))
      (is (some? (:created-at s)))
      (is (some? (:updated-at s)))))

  (testing "returns nil for missing session"
    (is (nil? (store/get-chat-session *store* "nope")))))

(deftest list-chat-sessions-test
  (testing "lists all sessions ordered by updated_at desc"
    (store/create-chat-session! *store* "s1" "graph")
    (store/create-chat-session! *store* "s2" "cell")
    (store/create-chat-session! *store* "s3" "graph")
    (let [sessions (store/list-chat-sessions *store*)]
      (is (= 3 (count sessions)))
      ;; All have agent-type
      (is (every? :agent-type sessions)))))

(deftest delete-chat-session-test
  (testing "deletes session and its messages"
    (store/create-chat-session! *store* "del-1" "graph")
    (store/save-chat-message! *store* "del-1" "user" "hello")
    (store/save-chat-message! *store* "del-1" "assistant" "hi")
    (is (some? (store/get-chat-session *store* "del-1")))
    (store/delete-chat-session! *store* "del-1")
    (is (nil? (store/get-chat-session *store* "del-1")))
    (is (empty? (store/load-chat-messages *store* "del-1")))))

;; =============================================================
;; Chat messages
;; =============================================================

(deftest save-and-load-chat-messages-test
  (testing "saves messages and loads them in order"
    (store/create-chat-session! *store* "msg-1" "graph")
    (store/save-chat-message! *store* "msg-1" "user" "design a todo app")
    (store/save-chat-message! *store* "msg-1" "assistant" "here is the manifest...")
    (store/save-chat-message! *store* "msg-1" "user" "add a delete step")
    (let [msgs (store/load-chat-messages *store* "msg-1")]
      (is (= 3 (count msgs)))
      (is (= "user" (:role (first msgs))))
      (is (= "design a todo app" (:content (first msgs))))
      (is (= "assistant" (:role (second msgs))))
      (is (= "user" (:role (nth msgs 2))))
      (is (some? (:created-at (first msgs)))))))

(deftest load-chat-messages-empty-test
  (testing "returns empty vec for session with no messages"
    (store/create-chat-session! *store* "empty-1" "graph")
    (is (empty? (store/load-chat-messages *store* "empty-1"))))

  (testing "returns empty vec for nonexistent session"
    (is (empty? (store/load-chat-messages *store* "no-such-session")))))

(deftest clear-chat-messages-test
  (testing "clears messages but keeps session"
    (store/create-chat-session! *store* "clr-1" "graph")
    (store/save-chat-message! *store* "clr-1" "user" "hello")
    (store/save-chat-message! *store* "clr-1" "assistant" "hi")
    (is (= 2 (count (store/load-chat-messages *store* "clr-1"))))
    (store/clear-chat-messages! *store* "clr-1")
    (is (empty? (store/load-chat-messages *store* "clr-1")))
    (is (some? (store/get-chat-session *store* "clr-1")))))

(deftest chat-session-message-count-test
  (testing "list-chat-sessions includes message count"
    (store/create-chat-session! *store* "cnt-1" "graph")
    (store/save-chat-message! *store* "cnt-1" "user" "hi")
    (store/save-chat-message! *store* "cnt-1" "assistant" "hello")
    (store/create-chat-session! *store* "cnt-2" "cell")
    (let [sessions (store/list-chat-sessions *store*)]
      (is (= 2 (count sessions)))
      (let [s1 (first (filter #(= "cnt-1" (:id %)) sessions))
            s2 (first (filter #(= "cnt-2" (:id %)) sessions))]
        (is (= 2 (:message-count s1)))
        (is (= 0 (:message-count s2)))))))
