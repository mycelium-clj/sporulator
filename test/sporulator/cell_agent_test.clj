(ns sporulator.cell-agent-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
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
;; implement-with-feedback (using mock LLM)
;; =============================================================

(def ^:private good-cell-response
  "Here is your cell:
```clojure
(ns sporulator.test.cells.mock-impl
  (:require [mycelium.cell :as cell]))

(cell/defcell :test/mock-impl
  {:doc \"Doubles the input\"
   :input {:x :int}
   :output {:doubled :int}}
  (fn [_ data] {:doubled (* 2 (:x data))}))
```")

(def ^:private bad-then-good-responses (atom []))

(defn- make-mock-session
  "Creates a mock LLM session that returns canned responses in order."
  [responses]
  (let [idx (atom 0)
        msgs (atom [])]
    {:id "mock"
     :system-prompt ""
     :messages msgs
     :responses responses
     :response-idx idx}))

(deftest implement-with-feedback-test
  (testing "succeeds on first attempt with valid cell"
    (let [events   (atom [])
          ;; Mock the LLM: return a valid cell on first try
          result   (with-redefs [llm/session-send-stream
                                 (fn [session client msg on-chunk & _]
                                   (swap! (:messages session) conj {:role "user" :content msg})
                                   (let [resp good-cell-response]
                                     (on-chunk resp)
                                     (swap! (:messages session) conj {:role "assistant" :content resp})
                                     resp))]
                     (ca/implement-with-feedback
                       nil ;; client (mocked)
                       {:id     ":test/mock-impl"
                        :doc    "Doubles the input"
                        :schema "{:input {:x :int} :output {:doubled :int}}"}
                       (fn [_chunk])
                       :on-feedback (fn [e] (swap! events conj e))))]
      (is (= :ok (:status result)))
      (is (= ":test/mock-impl" (:cell-id result)))
      (is (some? (:code result)))
      ;; Should have a success event
      (is (some #(= "success" (:event-type %)) @events))))

  (testing "retries on eval error then succeeds"
    (let [events (atom [])
          ;; First response: broken code, second: fixed
          call-count (atom 0)
          result (with-redefs [llm/session-send-stream
                               (fn [session client msg on-chunk & _]
                                 (swap! (:messages session) conj {:role "user" :content msg})
                                 (let [resp (if (zero? @call-count)
                                              ;; First call: return code with undefined symbol
                                              "```clojure\n(ns sporulator.test.cells.retry1\n  (:require [mycelium.cell :as cell]))\n\n(cell/defcell :test/retry-cell\n  {:doc \"Test retry\"\n   :input {:x :int} :output {:y :int}}\n  (fn [_ data] {:y (UNDEFINED-FN (:x data))}))\n```"
                                              ;; Second call: return fixed code
                                              "```clojure\n(ns sporulator.test.cells.retry1\n  (:require [mycelium.cell :as cell]))\n\n(cell/defcell :test/retry-cell\n  {:doc \"Test retry\"\n   :input {:x :int} :output {:y :int}}\n  (fn [_ data] {:y (* 2 (:x data))}))\n```")]
                                   (swap! call-count inc)
                                   (on-chunk resp)
                                   (swap! (:messages session) conj {:role "assistant" :content resp})
                                   resp))]
                   (ca/implement-with-feedback
                     nil
                     {:id     ":test/retry-cell"
                      :doc    "Test retry"
                      :schema "{:input {:x :int} :output {:y :int}}"}
                     (fn [_chunk])
                     :on-feedback (fn [e] (swap! events conj e))
                     :max-attempts 3))]
      (is (= :ok (:status result)))
      ;; Should have error then success events
      (is (some #(= "error" (:event-type %)) @events))
      (is (some #(= "success" (:event-type %)) @events)))))

;; =============================================================
;; implement-cells (parallel)
;; =============================================================

(deftest implement-cells-test
  (testing "implements multiple cells in parallel"
    (let [results (with-redefs [llm/session-send-stream
                                (fn [session client msg on-chunk & _]
                                  (swap! (:messages session) conj {:role "user" :content msg})
                                  (let [cell-id (re-find #":test/parallel-\w+" msg)
                                        ns-name (str "sporulator.test.cells."
                                                     (last (str/split (or cell-id ":test/unknown") #"/")))
                                        resp (str "```clojure\n(ns " ns-name
                                                  "\n  (:require [mycelium.cell :as cell]))\n\n"
                                                  "(cell/defcell " cell-id
                                                  "\n  {:doc \"Parallel cell\""
                                                  "\n   :input {:x :int} :output {:y :int}}"
                                                  "\n  (fn [_ data] {:y (:x data)}))\n```")]
                                    (on-chunk resp)
                                    (swap! (:messages session) conj {:role "assistant" :content resp})
                                    resp))]
                    (ca/implement-cells
                      nil
                      [{:id ":test/parallel-a" :doc "Cell A" :schema "{:input {:x :int} :output {:y :int}}"}
                       {:id ":test/parallel-b" :doc "Cell B" :schema "{:input {:x :int} :output {:y :int}}"}]))]
      (is (= 2 (count results)))
      (is (every? #(= :ok (:status %)) results)))))
