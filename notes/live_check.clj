(ns sporulator.live-check
  "Live end-to-end check of the agent loop against a real LLM.
   Loaded into a running REPL via (load-file ...). Drives one cell
   implementation through agent-loop/run! and prints a compact summary.

   Usage from a REPL where (sporulator-go!) has been called:
     (load-file \"/Users/yogthos/src/mycelium-clj/sporulator/notes/live_check.clj\")
     (sporulator.live-check/check-fraud-check)"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [sporulator.agent-loop :as agent-loop]
            [sporulator.codegen :as codegen]))

;; -----------------------------------------------------------------
;; Test fixture: a small order-lifecycle cell
;; -----------------------------------------------------------------
;; :order/fraud-check
;;   input: {:total :double}
;;   semantics (per SPEC.md):
;;     total > 5000 → :reject
;;     total > 2000 → :review
;;     otherwise    → :approve
;;   output: {:status :keyword}

(def ^:private fraud-brief
  {:id       :order/fraud-check
   :doc      (str "Classifies an order total into a fraud-risk decision. "
                  "Totals greater than 5000 are :reject. Totals greater than "
                  "2000 (but not more than 5000) are :review. Everything else "
                  "is :approve.")
   :schema   "{:input [:map [:total :double]] :output [:map [:status :keyword]]}"
   :requires []
   :context  ""})

(def ^:private fraud-schema-parsed
  {:input  [:map [:total :double]]
   :output [:map [:status :keyword]]})

(def ^:private fraud-test-body
  (str "(deftest classifies-by-total\n"
       "  (testing \"low totals approve\"\n"
       "    (is (= {:status :approve} (handler {} {:total 100.0})))\n"
       "    (is (= {:status :approve} (handler {} {:total 1999.99}))))\n"
       "  (testing \"medium totals review\"\n"
       "    (is (= {:status :review}  (handler {} {:total 2000.01})))\n"
       "    (is (= {:status :review}  (handler {} {:total 4999.99}))))\n"
       "  (testing \"high totals reject\"\n"
       "    (is (= {:status :reject}  (handler {} {:total 5000.01})))))\n"))

(def ^:private fraud-test-code
  (codegen/assemble-test-source
    {:test-ns   "fraudcheck-test"
     :cell-ns   "fraudcheck"
     :cell-id   :order/fraud-check
     :test-body fraud-test-body}))

;; -----------------------------------------------------------------
;; Live tool-call tracing (so we can see what the model did)
;; -----------------------------------------------------------------

(defn- summarise-tool-calls
  "After a run, walks the LLM session messages and produces a compact
   per-turn record of [tool-name (key-args)]. Useful for verifying the
   agent worked the new file-shaped tools without phase rejection."
  [session]
  (let [msgs (-> session :messages deref)]
    (->> msgs
         (filter #(= "assistant" (:role %)))
         (mapcat :tool_calls)
         (mapv (fn [{:keys [function]}]
                 (let [nm   (:name function)
                       args (try (json/read-str (:arguments function) :key-fn keyword)
                                 (catch Exception _ {}))
                       blob (or (:content args) (:old_string args))]
                   {:tool nm
                    :path (:path args)
                    :preview (when blob
                               (-> blob
                                   (subs 0 (min 60 (count blob)))
                                   (str/replace #"\n" "\\\\n")))}))))))

;; -----------------------------------------------------------------
;; Driver
;; -----------------------------------------------------------------

(defn check-fraud-check
  "Runs the agent loop against a live deepseek client for the
   :order/fraud-check cell.  Reads (:cell-client @user/sporulator-server) so
   the running server's configured client is reused. Prints a summary and
   returns a map you can poke at."
  []
  (let [server (some-> (resolve 'user/sporulator-server) deref deref)
        client (or (:cell-client server) (:llm-client server))]
    (when-not client
      (throw (ex-info "No LLM client available — call (sporulator-go!) first" {})))
    (println "[live-check] starting agent loop for :order/fraud-check ...")
    (let [t0       (System/currentTimeMillis)
          events   (atom [])
          result   (agent-loop/run!
                     {:client        client
                      :cell-id       :order/fraud-check
                      :cell-ns       "fraudcheck"
                      :brief         fraud-brief
                      :test-code     fraud-test-code
                      :schema-parsed fraud-schema-parsed
                      :turn-budget   25
                      :on-event      (fn [e] (swap! events conj e))
                      :on-chunk      (fn [_])})
          elapsed  (- (System/currentTimeMillis) t0)
          tool-log (summarise-tool-calls (:session result))]
      (println)
      (println "─────────────────────────────────────────────")
      (println " agent loop finished in" (format "%.1fs" (/ elapsed 1000.0)))
      (println " status :" (:status result))
      (when (= :error (:status result))
        (println " error  :" (:error result)))
      (println " turns  :" (count tool-log))
      (println)
      (println " tool calls in order:")
      (doseq [[i c] (map-indexed vector tool-log)]
        (println (format "  %2d. %-12s %s%s"
                         (inc i)
                         (:tool c)
                         (or (:path c) "")
                         (if (:preview c)
                           (str "  «" (:preview c) "»")
                           ""))))
      (println)
      (when-let [code (:code result)]
        (println " final cell source:")
        (println "─────────────────────────────────────────────")
        (println code)
        (println "─────────────────────────────────────────────"))
      {:status   (:status result)
       :error   (:error result)
       :code    (:code result)
       :turns   (count tool-log)
       :elapsed elapsed
       :tools   (mapv :tool tool-log)})))

;; -----------------------------------------------------------------
;; Harder cell: per-state, per-category tax-rate calculation
;; -----------------------------------------------------------------
;; :order/item-tax-rate
;;   input:  {:state :string :category :keyword :item-price :double}
;;   output: {:rate :double}
;; Rules (from order-lifecycle SPEC.md):
;;   CA: base 7.25%; electronics → +1.5% surcharge (= 8.75%)
;;   NY: base 8.875%; clothing exempt if item-price < 110; books always exempt
;;   OR: 0% across the board
;;   TX: base 6.25%; digital exempt
;; This cell is structurally bigger than fraud-check — branching by state
;; AND by category, with price-dependent exemptions for NY. It's natural
;; territory for the agent to reach for helpers.clj.

(def ^:private tax-brief
  {:id       :order/item-tax-rate
   :doc      (str "Computes the applicable sales-tax rate (as a decimal) for "
                  "a single line item, given the destination state, the "
                  "item's category, and its price. Per-state rules:\n"
                  "  CA — base 7.25%; electronics get a +1.5% surcharge (final 8.75%).\n"
                  "  NY — base 8.875%; clothing items priced under $110 are "
                  "exempt; books are always exempt.\n"
                  "  OR — everything is 0%.\n"
                  "  TX — base 6.25%; digital items are exempt.\n"
                  "Output the rate as a decimal (e.g. 0.0875), not a percentage.")
   :schema   "{:input [:map [:state :string] [:category :keyword] [:item-price :double]] :output [:map [:rate :double]]}"
   :requires []
   :context  ""})

(def ^:private tax-schema-parsed
  {:input  [:map
            [:state :string]
            [:category :keyword]
            [:item-price :double]]
   :output [:map [:rate :double]]})

(def ^:private tax-test-body
  (str "(deftest california-rules\n"
       "  (testing \"electronics get the 8.75% surcharge\"\n"
       "    (is (= {:rate 0.0875} (handler {} {:state \"CA\" :category :electronics :item-price 999.99}))))\n"
       "  (testing \"non-electronics use the 7.25% base\"\n"
       "    (is (= {:rate 0.0725} (handler {} {:state \"CA\" :category :clothing :item-price 29.99})))\n"
       "    (is (= {:rate 0.0725} (handler {} {:state \"CA\" :category :books :item-price 14.99})))))\n\n"
       "(deftest new-york-rules\n"
       "  (testing \"clothing under $110 is exempt\"\n"
       "    (is (= {:rate 0.0} (handler {} {:state \"NY\" :category :clothing :item-price 29.99}))))\n"
       "  (testing \"clothing $110+ is taxed at base\"\n"
       "    (is (= {:rate 0.08875} (handler {} {:state \"NY\" :category :clothing :item-price 110.0})))\n"
       "    (is (= {:rate 0.08875} (handler {} {:state \"NY\" :category :clothing :item-price 200.0}))))\n"
       "  (testing \"books are always exempt\"\n"
       "    (is (= {:rate 0.0} (handler {} {:state \"NY\" :category :books :item-price 14.99})))\n"
       "    (is (= {:rate 0.0} (handler {} {:state \"NY\" :category :books :item-price 1000.0}))))\n"
       "  (testing \"electronics use the base rate\"\n"
       "    (is (= {:rate 0.08875} (handler {} {:state \"NY\" :category :electronics :item-price 999.99})))))\n\n"
       "(deftest oregon-rules\n"
       "  (testing \"OR is 0% across the board\"\n"
       "    (is (= {:rate 0.0} (handler {} {:state \"OR\" :category :electronics :item-price 999.99})))\n"
       "    (is (= {:rate 0.0} (handler {} {:state \"OR\" :category :clothing :item-price 200.0})))\n"
       "    (is (= {:rate 0.0} (handler {} {:state \"OR\" :category :books :item-price 14.99})))))\n\n"
       "(deftest texas-rules\n"
       "  (testing \"digital items are exempt\"\n"
       "    (is (= {:rate 0.0} (handler {} {:state \"TX\" :category :digital :item-price 9.99}))))\n"
       "  (testing \"other categories use the 6.25% base\"\n"
       "    (is (= {:rate 0.0625} (handler {} {:state \"TX\" :category :electronics :item-price 999.99})))\n"
       "    (is (= {:rate 0.0625} (handler {} {:state \"TX\" :category :clothing :item-price 29.99})))\n"
       "    (is (= {:rate 0.0625} (handler {} {:state \"TX\" :category :books :item-price 14.99})))))\n"))

(def ^:private tax-test-code
  (codegen/assemble-test-source
    {:test-ns   "itemtaxrate-test"
     :cell-ns   "itemtaxrate"
     :cell-id   :order/item-tax-rate
     :test-body tax-test-body}))

(defn check-item-tax-rate
  "Drives an agent run for the harder :order/item-tax-rate cell."
  []
  (let [server (some-> (resolve 'user/sporulator-server) deref deref)
        client (or (:cell-client server) (:llm-client server))]
    (when-not client
      (throw (ex-info "No LLM client available — call (sporulator-go!) first" {})))
    (println "[live-check] starting agent loop for :order/item-tax-rate ...")
    (let [t0       (System/currentTimeMillis)
          result   (agent-loop/run!
                     {:client        client
                      :cell-id       :order/item-tax-rate
                      :cell-ns       "itemtaxrate"
                      :brief         tax-brief
                      :test-code     tax-test-code
                      :schema-parsed tax-schema-parsed
                      :turn-budget   30
                      :on-event      (fn [_])
                      :on-chunk      (fn [_])})
          elapsed  (- (System/currentTimeMillis) t0)
          tool-log (summarise-tool-calls (:session result))]
      (println)
      (println "─────────────────────────────────────────────")
      (println " agent loop finished in" (format "%.1fs" (/ elapsed 1000.0)))
      (println " status :" (:status result))
      (when (= :error (:status result))
        (println " error  :" (:error result)))
      (println " turns  :" (count tool-log))
      (println " tools  :" (mapv :tool tool-log))
      (println)
      (when-let [code (:code result)]
        (println " final cell source:")
        (println "─────────────────────────────────────────────")
        (println code)
        (println "─────────────────────────────────────────────"))
      {:status   (:status result)
       :error   (:error result)
       :code    (:code result)
       :turns   (count tool-log)
       :elapsed elapsed
       :tools   (mapv :tool tool-log)
       :session (:session result)})))

(comment
  ;; from a REPL where the sporulator server is running:
  (check-fraud-check)
  (check-item-tax-rate)
  )
