(ns sporulator.graph-agent-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [sporulator.graph-agent :as ga]
            [sporulator.llm :as llm]
            [sporulator.store :as store]))

;; =============================================================
;; Fixture: in-memory store per test, reset sessions
;; =============================================================

(def ^:dynamic *store* nil)

(defn with-store [f]
  (let [s (store/open ":memory:")]
    (try
      (binding [*store* s]
        (ga/reset-all-sessions!)
        (f))
      (finally
        (store/close s)))))

(use-fixtures :each with-store)

;; =============================================================
;; looks-like-manifest?
;; =============================================================

(deftest looks-like-manifest?-test
  (testing "detects manifest with :edges"
    (is (ga/looks-like-manifest?
          "{:id :todo-app :cells {:start {:id :todo/parse}} :edges {:start :end}}")))

  (testing "detects manifest with :pipeline"
    (is (ga/looks-like-manifest?
          "{:id :simple :cells {:start {:id :app/a}} :pipeline [:start :process]}")))

  (testing "rejects text without :cells"
    (is (not (ga/looks-like-manifest?
               "{:id :foo :edges {:a :b}}"))))

  (testing "rejects text without :id at top level (best-effort heuristic)"
    ;; Note: the Go version also uses simple string matching, so
    ;; {:cells {:start {:id :x}}} would pass. This is by design —
    ;; the real validation happens in validate-manifest-edn.
    (is (not (ga/looks-like-manifest?
               "{:cells {:start {:name :x}} :edges {:start :end}}"))))

  (testing "rejects text without :edges or :pipeline"
    (is (not (ga/looks-like-manifest?
               "{:id :foo :cells {:start {:id :x}}}"))))

  (testing "rejects non-manifest text"
    (is (not (ga/looks-like-manifest? "hello world")))
    (is (not (ga/looks-like-manifest? "")))))

;; =============================================================
;; extract-manifest
;; =============================================================

(deftest extract-manifest-test
  (testing "extracts from fenced edn block"
    (let [response "Here is your workflow:\n```edn\n{:id :todo-app\n :cells {:start {:id :todo/parse}}\n :pipeline [:start]}\n```\nLet me know if you want changes."
          result (ga/extract-manifest response)]
      (is (some? result))
      (is (= :todo-app (:id result)))
      (is (contains? result :cells))))

  (testing "extracts from fenced clojure block"
    (let [response "```clojure\n{:id :my-app :cells {:start {:id :x/y}} :edges {:start :end}}\n```"
          result (ga/extract-manifest response)]
      (is (some? result))
      (is (= :my-app (:id result)))))

  (testing "returns nil for response without manifest"
    (is (nil? (ga/extract-manifest "No manifest here, just some text."))))

  (testing "returns nil for malformed EDN"
    (is (nil? (ga/extract-manifest "```edn\n{:id :broken :cells\n```"))))

  (testing "returns nil when code block is not a manifest"
    (is (nil? (ga/extract-manifest "```clojure\n(defn foo [] 42)\n```")))))

;; =============================================================
;; validate-manifest-edn
;; =============================================================

(deftest validate-manifest-edn-test
  (testing "valid manifest returns parsed map"
    (let [edn (str "{:id :todo"
                   " :cells {:start {:id :todo/parse"
                   "                 :doc \"Parses input\""
                   "                 :schema {:input [:map] :output [:map [:result :string]]}}}"
                   " :pipeline [:start]}")
          result (ga/validate-manifest-edn edn)]
      (is (= :ok (:status result)))
      (is (= :todo (get-in result [:manifest :id])))))

  (testing "unparseable EDN returns error"
    (let [result (ga/validate-manifest-edn "{:id :broken :cells")]
      (is (= :parse-error (:status result)))
      (is (some? (:error result)))))

  (testing "parseable but not a manifest returns error"
    (let [result (ga/validate-manifest-edn "{:foo :bar}")]
      (is (= :invalid-manifest (:status result)))
      (is (some? (:error result))))))

;; =============================================================
;; Session persistence
;; =============================================================

(deftest persist-and-restore-session-test
  (testing "persists conversation turns to DB"
    (let [session (ga/get-or-create-session "persist-1" *store*)]
      ;; Simulate turns by directly manipulating session messages
      (swap! (:messages session) conj
             {:role "user" :content "design a todo app"})
      (swap! (:messages session) conj
             {:role "assistant" :content "here is the manifest"})
      (ga/persist-turn! *store* "persist-1" "user" "design a todo app")
      (ga/persist-turn! *store* "persist-1" "assistant" "here is the manifest")
      ;; Verify in DB
      (let [msgs (store/load-chat-messages *store* "persist-1")]
        (is (= 2 (count msgs)))
        (is (= "user" (:role (first msgs))))
        (is (= "design a todo app" (:content (first msgs)))))))

  (testing "restores session history from DB"
    ;; Clear in-memory sessions
    (ga/reset-all-sessions!)
    ;; Create a new session — should restore from DB
    (let [session (ga/get-or-create-session "persist-1" *store*)]
      (is (= 2 (count @(:messages session))))
      (is (= "user" (:role (first @(:messages session))))))))

(deftest save-response-manifest-test
  (testing "extracts manifest from response and saves to store"
    (let [response "Here:\n```edn\n{:id :my-wf :cells {:start {:id :x/y}} :pipeline [:start]}\n```"
          result (ga/save-response-manifest! *store* response)]
      (is (some? result))
      (is (= 1 (:version result)))
      ;; Verify in store
      (let [m (store/get-latest-manifest *store* ":my-wf")]
        (is (some? m))
        (is (= 1 (:version m))))))

  (testing "returns nil when response has no manifest"
    (is (nil? (ga/save-response-manifest! *store* "just some text")))))

;; =============================================================
;; decompose-requirements
;; =============================================================

(deftest decompose-requirements-test
  (testing "decomposes requirements into numbered steps before manifest"
    (let [call-count (atom 0)
          result
          (with-redefs [llm/session-send-stream
                        (fn [session _client msg on-chunk & _]
                          (swap! (:messages session) conj {:role "user" :content msg})
                          (let [resp (if (zero? @call-count)
                                       ;; First call: decomposition
                                       "## Steps\n1. Parse order items from input\n2. Apply promotional discounts\n3. Compute tax per item\n4. Calculate shipping by warehouse"
                                       ;; Second call: manifest from steps
                                       "```edn\n{:id :order-flow\n :cells {:start {:id :order/parse}}\n :pipeline [:start]}\n```")]
                            (swap! call-count inc)
                            (when on-chunk (on-chunk resp))
                            (swap! (:messages session) conj {:role "assistant" :content resp})
                            resp))]
            (ga/design-workflow nil "decomp-1"
              "Build an order processing system"
              (fn [_]) :store *store*))]
      (is (= 2 @call-count) "Should make two LLM calls: decompose then manifest")
      (is (some? (:steps result)))
      (is (some? (:manifest-response result)))))

  (testing "build-decompose-prompt includes requirements"
    (let [prompt (ga/build-decompose-prompt "Build a todo app with CRUD")]
      (is (str/includes? prompt "Build a todo app"))
      (is (str/includes? prompt "step")))))
