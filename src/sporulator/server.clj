(ns sporulator.server
  "HTTP + WebSocket server for the sporulator UI."
  (:require [cljfmt.core :as cljfmt]
            [clojure.data.json :as json]
            [zprint.core :as zp]
            [clojure.string :as str]
            [sporulator.bootstrap :as bootstrap]
            [sporulator.cell-agent :as cell-agent]
            [sporulator.eval :as ev]
            [sporulator.graph-agent :as graph-agent]
            [sporulator.llm :as llm]
            [sporulator.manifest-diff :as manifest-diff]
            [sporulator.manifest-validate :as mv]
            [sporulator.orchestrator :as orchestrator :refer [read-system-edn]]
            [sporulator.source-gen :as source-gen]
            [sporulator.store :as store]
            [sporulator.tools :as tools]
            [org.httpkit.server :as http])
  (:import [java.net URLDecoder]))

;; ── Key transform (kebab-case → PascalCase for JSON) ──────────

(defn- kebab->pascal [k]
  (let [n (name k)
        n (cond-> n (str/ends-with? n "?") (subs 0 (dec (count n))))
        parts (str/split n #"-")]
    (apply str (map (fn [part]
                      (if (= part "id") "ID"
                          (str (str/upper-case (subs part 0 1))
                               (subs part 1))))
                    parts))))

(defn- transform-keys
  "Converts a map with kebab-case keyword keys to PascalCase string keys.
   Boolean :foo? keys shadow integer :foo keys."
  [m]
  (when m
    (reduce-kv
      (fn [acc k v]
        (let [pascal (kebab->pascal k)
              bool-key? (str/ends-with? (name k) "?")]
          (if (or bool-key? (not (contains? acc pascal)))
            (assoc acc pascal v)
            acc)))
      {} m)))

;; ── HTTP helpers ───────────────────────────────────────────────

(defn- json-response
  ([data] (json-response data 200))
  ([data status]
   {:status  status
    :headers {"Content-Type" "application/json"
              "Access-Control-Allow-Origin" "*"}
    :body    (json/write-str data)}))

(defn- query-param [request param]
  (when-let [qs (:query-string request)]
    (some (fn [pair]
            (let [[k v] (str/split pair #"=" 2)]
              (when (= k param)
                (URLDecoder/decode v "UTF-8"))))
          (str/split qs #"&"))))

(defn- parse-query-params
  "Parses all query parameters into a keyword map."
  [request]
  (when-let [qs (:query-string request)]
    (into {}
      (keep (fn [pair]
              (let [[k v] (str/split pair #"=" 2)]
                (when (and k v)
                  [(keyword k) (URLDecoder/decode v "UTF-8")])))
            (str/split qs #"&")))))

(defn- read-json-body [request]
  ;; code-mode sends POST args as query params, not JSON body.
  ;; Try query params first, fall back to JSON body.
  (let [qp (parse-query-params request)]
    (if (seq qp)
      qp
      (when-let [body (:body request)]
        (try
          (let [parsed (json/read (java.io.InputStreamReader. body "UTF-8") :key-fn keyword)]
            (if (and (map? parsed) (contains? parsed :body) (map? (:body parsed)))
              (:body parsed)
              parsed))
          (catch Exception _ nil))))))

;; ── WebSocket hub ──────────────────────────────────────────────

(defn- send-ws! [ch msg]
  (try
    (http/send! ch (json/write-str msg))
    (catch Exception _)))

(defn- broadcast! [clients msg]
  (let [text (json/write-str msg)]
    (doseq [ch @clients]
      (try (http/send! ch text)
           (catch Exception _
             (swap! clients disj ch))))))

;; ── Review gates (pending reviews keyed by run-id) ─────────────

(defonce ^:private review-gates (atom {}))

;; ── WebSocket message handling ─────────────────────────────────

(defn- handle-graph-chat [{:keys [llm-client store]} ch msg]
  (let [sid      (get-in msg [:payload :session_id])
        message  (get-in msg [:payload :message])
        manifest (get-in msg [:payload :manifest])
        full-msg (if manifest
                   (str message "\n\nCurrent manifest:\n```edn\n" manifest "\n```\n\n"
                        "IMPORTANT: When modifying the manifest, output the COMPLETE updated manifest. "
                        "Every cell MUST have :doc and :schema with :input and :output using lite Malli syntax "
                        "(e.g. {:handle :string, :message :string}). "
                        "New cells must have schemas compatible with their neighbors in the pipeline/edges.")
                   message)]
    (if-not llm-client
      (do
        (send-ws! ch {:type "stream_chunk" :id sid
                      :payload {:chunk "LLM not configured. Set GRAPH_API_KEY environment variable."}})
        (send-ws! ch {:type "stream_end" :id sid
                      :payload {:content "LLM not configured. Set GRAPH_API_KEY environment variable."}}))
      (future
        (try
          (let [content (graph-agent/chat-stream-with-feedback
                          llm-client sid full-msg
                          (fn [chunk]
                            (send-ws! ch {:type "stream_chunk"
                                          :id sid
                                          :payload {:chunk chunk}}))
                          :store store
                          :on-feedback
                          (fn [event]
                            (send-ws! ch {:type "feedback_event"
                                          :id sid
                                          :payload event})))]
            (send-ws! ch {:type "stream_end"
                          :id sid
                          :payload {:content content}}))
          (catch Exception e
            (send-ws! ch {:type "stream_error"
                          :id sid
                          :payload (.getMessage e)})))))))

(defn- handle-graph-decompose [{:keys [llm-client store]} ch msg]
  (let [sid     (get-in msg [:payload :session_id])
        message (get-in msg [:payload :message])]
    (if-not llm-client
      (do
        (send-ws! ch {:type "stream_chunk" :id sid
                      :payload {:chunk "LLM not configured." :phase "decompose"}})
        (send-ws! ch {:type "decompose_end" :id sid
                      :payload {:content "LLM not configured."}}))
      (future
        (try
          (let [steps (graph-agent/decompose-requirements
                        llm-client sid message
                        (fn [chunk]
                          (send-ws! ch {:type "stream_chunk" :id sid
                                        :payload {:chunk chunk :phase "decompose"}}))
                        :store store)]
            (send-ws! ch {:type "decompose_end" :id sid
                          :payload {:content steps}}))
          (catch Exception e
            (send-ws! ch {:type "stream_error" :id sid
                          :payload (.getMessage e)})))))))

(defn- handle-graph-approve [{:keys [llm-client store]} ch msg]
  (let [sid      (get-in msg [:payload :session_id])
        feedback (get-in msg [:payload :feedback])]
    (if-not llm-client
      (send-ws! ch {:type "stream_end" :id sid
                    :payload {:content "LLM not configured."}})
      (future
        (try
          (let [response (graph-agent/build-manifest
                           llm-client sid
                           (fn [chunk]
                             (send-ws! ch {:type "stream_chunk" :id sid
                                           :payload {:chunk chunk :phase "manifest"}}))
                           :store store
                           :feedback (when (seq feedback) feedback))]
            (send-ws! ch {:type "stream_end" :id sid
                          :payload {:content response}}))
          (catch Exception e
            (send-ws! ch {:type "stream_error" :id sid
                          :payload (.getMessage e)})))))))

(defn- handle-graph-design [{:keys [llm-client store]} ch msg]
  (let [sid     (get-in msg [:payload :session_id])
        message (get-in msg [:payload :message])]
    (if-not llm-client
      (do
        (send-ws! ch {:type "stream_chunk" :id sid
                      :payload {:chunk "LLM not configured." :phase "decompose"}})
        (send-ws! ch {:type "stream_end" :id sid
                      :payload {:content "LLM not configured."}}))
      (future
        (try
          (let [result (graph-agent/design-workflow
                         llm-client sid message
                         ;; Phase 1: decompose chunks
                         (fn [chunk]
                           (send-ws! ch {:type "stream_chunk" :id sid
                                         :payload {:chunk chunk :phase "decompose"}}))
                         :store store
                         :on-chunk-manifest
                         ;; Phase 2: manifest chunks
                         (fn [chunk]
                           (send-ws! ch {:type "stream_chunk" :id sid
                                         :payload {:chunk chunk :phase "manifest"}}))
                         :on-feedback
                         (fn [event]
                           (send-ws! ch {:type "design_event" :id sid
                                         :payload event})))]
            (send-ws! ch {:type "stream_end" :id sid
                          :payload {:content (:manifest-response result)
                                    :steps   (:steps result)}}))
          (catch Exception e
            (send-ws! ch {:type "stream_error" :id sid
                          :payload (.getMessage e)})))))))

(defn- cell-ns-from-id
  "Derives a namespace name from base-ns and cell-id.
   :guestbook/collect with base-ns \"app\" → \"app.cells.collect\""
  [base-ns cell-id]
  (let [id-str (if (str/starts-with? (str cell-id) ":")
                 (subs (str cell-id) 1)
                 (str cell-id))
        suffix (last (str/split id-str #"/"))]
    (str base-ns ".cells." (str/replace suffix "_" "-"))))

(defn- parse-brief-schema
  "Parses a brief's :schema (an EDN string) into {:input ... :output ...}.
   Tolerant: returns {:input [:map] :output [:map]} on parse failure."
  [schema-str]
  (try
    (let [raw (binding [*read-eval* false]
                (clojure.edn/read-string schema-str))
          fix-keys (fn fix-keys [v]
                     (cond
                       (and (map? v) (seq v)
                            (every? #(or (string? %) (symbol? %)) (keys v)))
                       (into {} (map (fn [[k vv]] [(keyword k) (fix-keys vv)])) v)
                       (and (map? v) (seq v) (every? keyword? (keys v))) v
                       (and (vector? v) (= "map" (first v)))
                       (into [:map] (map (fn [entry]
                                            (if (vector? entry)
                                              [(keyword (first entry)) (keyword (second entry))]
                                              (keyword entry)))
                                          (rest v)))
                       (string? v) (keyword v)
                       (symbol? v) (keyword v)
                       :else v))]
      {:input  (fix-keys (:input raw))
       :output (fix-keys (:output raw))})
    (catch Exception _ {:input [:map] :output [:map]})))

(defn- handle-cell-implement [{:keys [cell-client llm-client store project-path]} ch msg]
  (let [sid      (get-in msg [:payload :session_id])
        brief    (get-in msg [:payload :brief])
        base-ns  (or (get-in msg [:payload :base_ns]) "app")
        client   (or cell-client llm-client)
        cell-id  (str (:id brief))
        cell-id-key (cond-> cell-id (str/starts-with? cell-id ":") (subs 1))
        ;; Pull the latest approved test contract for this cell, if any.
        contracts (when store (store/get-test-contracts-for-cell store cell-id-key))
        contract  (last contracts)
        test-code (:test-code contract)
        cell-ns   (cell-ns-from-id base-ns cell-id-key)
        schema-parsed (parse-brief-schema (or (:schema brief) ""))]
    (cond
      (not client)
      (send-ws! ch {:type "stream_error" :id sid
                    :payload "LLM not configured."})

      (str/blank? test-code)
      (send-ws! ch {:type "stream_error" :id sid
                    :payload (str "No test contract found for " cell-id
                                  ". Generate tests first.")})

      :else
      (future
        (try
          ;; The agent loop's finalize already writes to :store and disk,
          ;; so we don't double-save here.
          (let [result (cell-agent/implement-with-feedback
                         client brief
                         (fn [chunk]
                           (send-ws! ch {:type "stream_chunk" :id sid
                                         :payload {:chunk chunk}}))
                         :on-feedback
                         (fn [event]
                           (send-ws! ch {:type "feedback_event" :id sid
                                         :payload event}))
                         :test-code     test-code
                         :cell-ns       cell-ns
                         :schema-parsed schema-parsed
                         :base-ns       base-ns
                         :project-path  project-path
                         :store         store
                         :run-id        (:run-id contract))]
            (send-ws! ch {:type "cell_result" :id sid
                          :payload {:cell_id (:cell-id result)
                                    :status  (name (:status result))
                                    :code    (:code result)}}))
          (catch Exception e
            (send-ws! ch {:type "stream_error" :id sid
                          :payload (.getMessage e)})))))))

(defn- handle-test-regenerate [{:keys [cell-client llm-client store]} ch msg]
  (let [sid      (get-in msg [:payload :session_id])
        brief    (get-in msg [:payload :brief])
        feedback (get-in msg [:payload :feedback])
        base-ns  (get-in msg [:payload :base_ns] "app")
        client   (or cell-client llm-client)]
    (if-not client
      (send-ws! ch {:type "stream_error" :id sid :payload "LLM not configured."})
      (future
        (try
          (let [;; Add feedback to the brief doc so the agent knows what to change
                brief-with-feedback (if (seq feedback)
                                     (update brief :doc str "\n\nTest feedback: " feedback)
                                     brief)
                run-id (str "test-regen-" (System/nanoTime))
                contract (orchestrator/generate-test-contract client
                           {:brief    brief-with-feedback
                            :base-ns  base-ns
                            :store    store
                            :run-id   run-id
                            :on-event (fn [event]
                                        (send-ws! ch {:type "orchestrator_event"
                                                      :id sid :payload event}))
                            :on-chunk (fn [chunk]
                                        (send-ws! ch {:type "stream_chunk"
                                                      :id sid
                                                      :payload {:chunk chunk}}))})]
            (send-ws! ch {:type "test_result" :id sid
                          :payload {"cell_id"   (:cell-id contract)
                                    "test_code" (:test-code contract)
                                    "test_body" (:test-body contract)
                                    "status"    "ok"}}))
          (catch Exception e
            (send-ws! ch {:type "stream_error" :id sid
                          :payload (.getMessage e)})))))))

(defn- handle-cell-iterate [{:keys [cell-client llm-client]} ch msg]
  (let [sid      (get-in msg [:payload :session_id])
        feedback (get-in msg [:payload :feedback])
        client   (or cell-client llm-client)]
    (if-not client
      (send-ws! ch {:type "stream_error" :id sid :payload "LLM not configured."})
      (future
        (try
          (let [session (llm/create-session (str "iterate:" sid) "")
                content (:content
                          (llm/session-send-stream session client feedback
                            (fn [chunk]
                              (send-ws! ch {:type "stream_chunk" :id sid
                                            :payload {:chunk chunk}}))))]
            (send-ws! ch {:type "stream_end" :id sid
                          :payload {:content content}}))
          (catch Exception e
            (send-ws! ch {:type "stream_error" :id sid
                          :payload (.getMessage e)})))))))

(defn- handle-orchestrate [{:keys [cell-client llm-client store project-path]} ch msg]
  (let [sid         (get-in msg [:payload :session_id])
        leaves      (get-in msg [:payload :leaves])
        base-ns     (get-in msg [:payload :base_ns] "app")
        manifest-id (get-in msg [:payload :manifest_id])
        client      (or cell-client llm-client)
        ;; Load manifest from store for graph context + validation
        manifest    (when (and store manifest-id)
                      (when-let [m (store/get-latest-manifest store manifest-id)]
                        (mv/parse-manifest (:body m))))]
    (if-not client
      (send-ws! ch {:type "stream_error" :id sid :payload "LLM not configured."})
      (future
        (try
          (let [gate   (orchestrator/create-review-gate)
                run-id (str "run-" (System/nanoTime))
                _      (swap! review-gates assoc run-id gate)
                result (orchestrator/orchestrate!
                         client
                         {:leaves       leaves
                          :manifest     (when (and manifest (not (:error manifest)))
                                          manifest)
                          :manifest-id  (or manifest-id "")
                          :base-ns      base-ns
                          :store        store
                          :project-path project-path
                          :auto-approve? true
                          :on-event     (fn [event]
                                          (send-ws! ch {:type "orchestrator_event"
                                                        :id sid
                                                        :payload event}))
                          :on-chunk     (fn [chunk]
                                          (send-ws! ch {:type "stream_chunk"
                                                        :id sid
                                                        :payload {:chunk chunk}}))
                          :max-attempts 3})]
            (swap! review-gates dissoc run-id)
            (send-ws! ch {:type (if (= "ok" (get result "status"))
                                  "orchestrator_complete"
                                  "orchestrator_error")
                          :id sid
                          :payload result}))
          (catch Exception e
            (send-ws! ch {:type "orchestrator_error" :id sid
                          :payload (.getMessage e)})))))))

(defn- handle-test-review [_ctx _ch msg]
  (let [run-id    (get-in msg [:payload :run_id])
        responses (get-in msg [:payload :responses])]
    (when-let [gate (get @review-gates run-id)]
      (orchestrator/deliver-review gate responses))))

(defn- handle-graph-review [_ctx _ch msg]
  (let [run-id   (get-in msg [:payload :run_id])
        response (get-in msg [:payload :response])]
    (when-let [gate (get @review-gates (str run-id ":graph"))]
      (orchestrator/deliver-review gate response))))

(defn- handle-impl-review [_ctx _ch msg]
  (let [run-id    (get-in msg [:payload :run_id])
        responses (get-in msg [:payload :responses])]
    (when-let [gate (get @review-gates (str run-id ":impl"))]
      (orchestrator/deliver-review gate responses))))

;; ── Interactive orchestration handlers ─────────────────────────

(defn- handle-start-orchestration [{:keys [cell-client llm-client store project-path]} ch msg]
  (let [sid         (get-in msg [:payload :session_id])
        leaves      (get-in msg [:payload :leaves])
        base-ns     (get-in msg [:payload :base_ns] "app")
        manifest-id (get-in msg [:payload :manifest_id])
        ;; Implementor strategy. JSON arrives as a string; coerce to a
        ;; keyword the orchestrator's case statement understands. Default
        ;; is :flat (existing behaviour). Pass "bottom-up" to opt into
        ;; the recursive-decomposer + parallel-leaf path.
        strategy    (let [raw (get-in msg [:payload :strategy])]
                      (case raw
                        ("bottom-up" "bottom_up" :bottom-up) :bottom-up
                        :flat))
        client      (or cell-client llm-client)
        manifest    (when (and store manifest-id)
                      (when-let [m (store/get-latest-manifest store manifest-id)]
                        (mv/parse-manifest (:body m))))]
    (if-not client
      (send-ws! ch {:type "stream_error" :id sid :payload "LLM not configured."})
      (let [run-id (orchestrator/start-orchestration! client
                     {:leaves       leaves
                      :manifest     (when (and manifest (not (:error manifest))) manifest)
                      :manifest-id  (or manifest-id "")
                      :base-ns      base-ns
                      :store        store
                      :project-path project-path
                      :strategy     strategy
                      :on-event     (fn [event]
                                      (send-ws! ch {:type "orchestrator_event"
                                                    :id sid
                                                    :payload event}))
                      :on-chunk     (fn [chunk]
                                      (send-ws! ch {:type "stream_chunk"
                                                    :id sid
                                                    :payload {:chunk chunk}}))})]
        (send-ws! ch {:type "orchestration_started" :id sid
                      :payload {"run_id"   run-id
                                "strategy" (name strategy)}})))))

(defn- handle-approve-tests [_ctx ch msg]
  (let [sid     (get-in msg [:payload :session_id])
        run-id  (get-in msg [:payload :run_id])
        cell-id (get-in msg [:payload :cell_id])]
    (orchestrator/approve-tests! run-id cell-id)
    (send-ws! ch {:type "ack" :id sid :payload {"action" "approve_tests" "cell_id" cell-id}})))

(defn- handle-reject-tests [_ctx ch msg]
  (let [sid      (get-in msg [:payload :session_id])
        run-id   (get-in msg [:payload :run_id])
        cell-id  (get-in msg [:payload :cell_id])
        feedback (get-in msg [:payload :feedback] "")]
    (orchestrator/reject-tests! run-id cell-id feedback)
    (send-ws! ch {:type "ack" :id sid :payload {"action" "reject_tests" "cell_id" cell-id}})))

(defn- handle-save-tests [_ctx ch msg]
  (let [sid       (get-in msg [:payload :session_id])
        run-id    (get-in msg [:payload :run_id])
        cell-id   (get-in msg [:payload :cell_id])
        test-code (get-in msg [:payload :test_code])]
    (orchestrator/save-tests! run-id cell-id test-code)
    (send-ws! ch {:type "ack" :id sid :payload {"action" "save_tests" "cell_id" cell-id}})))

(defn- handle-approve-impl [_ctx ch msg]
  (let [sid     (get-in msg [:payload :session_id])
        run-id  (get-in msg [:payload :run_id])
        cell-id (get-in msg [:payload :cell_id])]
    (orchestrator/approve-impl! run-id cell-id)
    (send-ws! ch {:type "ack" :id sid :payload {"action" "approve_impl" "cell_id" cell-id}})))

(defn- handle-reject-impl [_ctx ch msg]
  (let [sid      (get-in msg [:payload :session_id])
        run-id   (get-in msg [:payload :run_id])
        cell-id  (get-in msg [:payload :cell_id])
        feedback (get-in msg [:payload :feedback] "")]
    (orchestrator/reject-impl! run-id cell-id feedback)
    (send-ws! ch {:type "ack" :id sid :payload {"action" "reject_impl" "cell_id" cell-id}})))

(defn- handle-save-impl [_ctx ch msg]
  (let [sid     (get-in msg [:payload :session_id])
        run-id  (get-in msg [:payload :run_id])
        cell-id (get-in msg [:payload :cell_id])
        source  (get-in msg [:payload :source])]
    (orchestrator/save-impl! run-id cell-id source)
    (send-ws! ch {:type "ack" :id sid :payload {"action" "save_impl" "cell_id" cell-id}})))

;; ── WS message router ────────────────────────────────────────

(defn- handle-ws-message [ctx ch msg]
  (case (:type msg)
    "graph_chat"             (handle-graph-chat ctx ch msg)
    "graph_decompose"        (handle-graph-decompose ctx ch msg)
    "graph_approve"          (handle-graph-approve ctx ch msg)
    "graph_design"           (handle-graph-design ctx ch msg)
    "cell_implement"         (handle-cell-implement ctx ch msg)
    "cell_iterate"           (handle-cell-iterate ctx ch msg)
    "test_regenerate"        (handle-test-regenerate ctx ch msg)
    "orchestrate"            (handle-orchestrate ctx ch msg)
    "test_review"            (handle-test-review ctx ch msg)
    "graph_review"           (handle-graph-review ctx ch msg)
    "impl_review"            (handle-impl-review ctx ch msg)
    ;; Interactive orchestration
    "start_orchestration"    (handle-start-orchestration ctx ch msg)
    "approve_tests"          (handle-approve-tests ctx ch msg)
    "reject_tests"           (handle-reject-tests ctx ch msg)
    "save_tests"             (handle-save-tests ctx ch msg)
    "approve_impl"           (handle-approve-impl ctx ch msg)
    "reject_impl"            (handle-reject-impl ctx ch msg)
    "save_impl"              (handle-save-impl ctx ch msg)
    ;; default
    (send-ws! ch {:type "error"
                  :payload (str "Unknown message type: " (:type msg))})))

(defn- ws-handler [{:keys [clients] :as ctx} request]
  (http/with-channel request ch
    (swap! clients conj ch)
    (http/on-receive ch
      (fn [data]
        (try
          (let [msg (json/read-str data :key-fn keyword)]
            (handle-ws-message ctx ch msg))
          (catch Exception e
            (send-ws! ch {:type "error" :payload (.getMessage e)})))))
    (http/on-close ch
      (fn [_status]
        (swap! clients disj ch)))))

;; ── Manifest summary formatting ───────────────────────────────

(defn- format-manifest-summary [m]
  (-> m
      (assoc :updated-at (:created-at m))
      (dissoc :created-at)
      transform-keys))

;; ── REST routes ────────────────────────────────────────────────

(defn- api-handler [{:keys [store project-path]} request]
  (let [uri    (:uri request)
        method (:request-method request)]
    (cond
      ;; Cells
      (and (= method :get) (= uri "/api/cells"))
      (json-response (mapv transform-keys (store/list-latest-cells store)))

      (and (= method :get) (= uri "/api/cell"))
      (if-let [id (query-param request "id")]
        (if-let [cell (store/get-latest-cell store id)]
          (json-response (transform-keys
                           (update cell :handler
                                   (fn [h] (if (seq h)
                                             (try (zp/zprint-str h {:parse-string? true
                                                                        :parse-string-all? true
                                                                        :width 80})
                                                  (catch Exception _ h))
                                             h)))))
          (json-response {"error" "not found"} 404))
        (json-response {"error" "missing id"} 400))

      (and (= method :post) (= uri "/api/cell"))
      (let [{:keys [id handler schema doc requires]} (read-json-body request)]
        (if-not id
          (json-response {"error" "missing id"} 400)
          (let [version (store/save-cell! store
                          {:id id :handler (or handler "")
                           :schema (or schema "") :doc (or doc "")
                           :requires (or requires "") :created-by "api"})]
            (json-response {"ID" id "Version" version}))))

      (and (= method :get) (= uri "/api/cell/history"))
      (if-let [id (query-param request "id")]
        (json-response (mapv transform-keys (store/get-cell-history store id)))
        (json-response {"error" "missing id"} 400))

      (and (= method :get) (= uri "/api/cell/tests"))
      (if-let [id (query-param request "id")]
        (json-response (mapv transform-keys (store/get-latest-test-results store id 50)))
        (json-response {"error" "missing id"} 400))

      ;; Test contracts for a cell (from any run)
      (and (= method :get) (= uri "/api/cell/test-contract"))
      (let [cell-id (query-param request "id")
            run-id  (query-param request "run_id")]
        (if-not cell-id
          (json-response {"error" "missing id"} 400)
          (if run-id
            ;; Specific run
            (if-let [tc (store/get-test-contract store run-id cell-id)]
              (json-response (transform-keys tc))
              (json-response {"error" "not found"} 404))
            ;; Latest run with this cell
            (let [contracts (store/get-test-contracts-for-cell store cell-id)]
              (if (seq contracts)
                (json-response (transform-keys (last contracts)))
                (json-response {"error" "not found"} 404))))))

      ;; Run tests for a cell
      (and (= method :post) (= uri "/api/cell/run-tests"))
      (let [{:keys [handler test-code test_code]} (read-json-body request)
            test-src (or test-code test_code)]
        (if-not (and handler test-src)
          (json-response {"error" "missing handler or test-code"} 400)
          ;; Combine handler + test into one source and eval together.
          ;; This avoids the issue where require can't find the cell namespace
          ;; on disk since it was only created via eval.
          (let [cell-ns-match (re-find #"\[([a-z][a-z0-9._-]+)\]" test-src)
                cell-ns-name  (when cell-ns-match (second cell-ns-match))
                ;; Wrap bare defcell in a namespace if needed
                handler-src   (if (re-find #"^\s*\(ns\s" handler)
                                handler
                                (str "(ns " (or cell-ns-name "sporulator.tmp-cell")
                                     "\n  (:require [mycelium.cell :as cell]))\n\n"
                                     handler))
                ;; Replace the cell require in test source with a direct namespace ref
                ;; since the cell ns was created via eval, not from a file
                test-src-fixed (if cell-ns-name
                                 (str/replace test-src
                                   (str "[" cell-ns-name "]")
                                   (str "[" cell-ns-name " :as _cell-ns]"))
                                 test-src)
                ;; Eval handler + tests together in one load-string call
                combined      (str handler-src "\n\n" test-src-fixed)
                eval-res      (ev/eval-code combined)]
            (if (not= :ok (:status eval-res))
              ;; Eval failed — try to give a useful error
              (json-response {"status" "error"
                              "error" (or (:error eval-res) "Eval failed")
                              "output" (or (:output eval-res) "")})
              ;; Eval succeeded — now run the tests
              (let [test-ns-name (second (re-find #"\(ns\s+(\S+)" test-src))
                    test-ns      (when test-ns-name (find-ns (symbol test-ns-name)))]
                (if-not test-ns
                  (json-response {"status" "error"
                                  "error" (str "Test namespace not found: " test-ns-name)
                                  "output" (or (:output eval-res) "")})
                  (let [out       (java.io.StringWriter.)
                        counters  (atom {:test 0 :pass 0 :fail 0 :error 0})
                        reporter  (fn [m]
                                    (case (:type m)
                                      :begin-test-ns nil
                                      :end-test-ns nil
                                      :begin-test-var (swap! counters update :test inc)
                                      :pass (swap! counters update :pass inc)
                                      :fail (do (swap! counters update :fail inc)
                                                (.write out
                                                  (str "\nFAIL in " (clojure.test/testing-vars-str m) "\n"
                                                       (:message m "")
                                                       "\nexpected: " (pr-str (:expected m))
                                                       "\n  actual: " (pr-str (:actual m)) "\n")))
                                      :error (do (swap! counters update :error inc)
                                                 (.write out
                                                   (str "\nERROR in " (clojure.test/testing-vars-str m) "\n"
                                                        (:message m "")
                                                        "\n  actual: " (pr-str (:actual m)) "\n")))
                                      :summary nil
                                      nil))
                        _         (binding [*out* out
                                            clojure.test/report reporter]
                                    (clojure.test/run-tests test-ns))
                        summary   @counters]
                    (json-response {"status" "ok"
                                    "passed" (and (zero? (:fail summary))
                                                  (zero? (:error summary)))
                                    "summary" summary
                                    "output" (str (:output eval-res) (str out))}))))))))

      ;; Format code
      (and (= method :post) (= uri "/api/format"))
      (let [{:keys [code]} (read-json-body request)]
        (if-not code
          (json-response {"error" "missing code"} 400)
          (json-response {"formatted" (try (zp/zprint-str code {:parse-string? true
                                                                 :parse-string-all? true
                                                                 :width 80})
                                           (catch Exception _ code))})))

      ;; Resources discovery
      ;; Discovers resources from system.edn using :mycelium/doc metadata.
      ;; Components WITH :mycelium/doc are exposed as resources.
      ;; Components without it are treated as infrastructure.
      (and (= method :get) (= uri "/api/resources"))
      (let [sys-edn (when project-path
                      (read-system-edn (str project-path "/resources/system.edn")))
            ;; Resources are integrant components with :mycelium/doc metadata
            ;; Map through routes config to get injection key names
            routes-cfg (when (map? sys-edn) (get sys-edn :reitit.routes/pages))
            ig->inject (when (map? routes-cfg)
                         (into {} (keep (fn [[ik igr]] (when (keyword? igr) [igr ik])))
                               routes-cfg))
            available  (when sys-edn
                         (->> sys-edn
                              (keep (fn [[k v]]
                                      (when-let [doc (and (map? v) (:mycelium/doc v))]
                                        (let [inject-key (or (get ig->inject k)
                                                             (keyword (name k)))]
                                          {"key"          (str k)
                                           "resource_key" (name inject-key)
                                           "doc"          doc
                                           "config"       (pr-str (dissoc v :mycelium/doc))}))))
                              vec))
            available-keys (set (map #(keyword (get % "resource_key")) (or available [])))
            ;; Cross-reference with manifest cell :requires
            manifest  (when store
                        (when-let [m (first (store/list-manifests store))]
                          (when-let [full (store/get-latest-manifest store (:id m))]
                            (try (binding [*read-eval* false]
                                   (read-string (:body full)))
                                 (catch Exception _ nil)))))
            usage     (when manifest
                        (reduce (fn [acc [_cell-name cell-def]]
                                  (reduce (fn [a r]
                                            (update a (name r) (fnil conj [])
                                                    (str (:id cell-def))))
                                          acc
                                          (or (:requires cell-def) [])))
                                {} (:cells manifest)))
            required  (set (map keyword (keys (or usage {}))))
            missing   (vec (map name (clojure.set/difference required available-keys)))]
        (json-response {"available" (or available [])
                        "usage" (or usage {})
                        "missing" missing}))

      ;; Manifests
      (and (= method :get) (= uri "/api/manifests"))
      (json-response (mapv format-manifest-summary (store/list-manifests store)))

      (and (= method :get) (= uri "/api/manifest"))
      (if-let [id (query-param request "id")]
        (if-let [manifest (store/get-latest-manifest store id)]
          (json-response (transform-keys manifest))
          (json-response {"error" "not found"} 404))
        (json-response {"error" "missing id"} 400))

      (and (= method :post) (= uri "/api/manifest"))
      (let [{:keys [id body]} (read-json-body request)]
        (if-not id
          (json-response {"error" "missing id"} 400)
          (let [version (store/save-manifest! store
                          {:id id :body (or body "") :created-by "api"})]
            (json-response {"ID" id "Version" version}))))

      ;; Diff between the latest manifest and the latest green snapshot
      (and (= method :get) (= uri "/api/manifest/diff"))
      (if-let [raw-id (query-param request "id")]
        (let [;; Normalise the manifest-id so callers can pass either form
              ;; (":foo/bar" or "foo/bar") and lookups all hit the same row.
              id (manifest-diff/normalize-manifest-id raw-id)
              m-row (store/get-latest-manifest store id)
              parsed-cur (when m-row (mv/parse-manifest (:body m-row)))
              cur-manifest (when (and parsed-cur (not (:error parsed-cur))) parsed-cur)
              snap (store/get-latest-green-snapshot store id)
              parsed-prev (when snap (mv/parse-manifest (:body snap)))
              prev-manifest (when (and parsed-prev (not (:error parsed-prev))) parsed-prev)
              d (manifest-diff/diff prev-manifest cur-manifest)
              ->ids (fn [ks] (mapv str ks))]
          (json-response
            {"manifest_id"     id
             "manifest_version" (when m-row (:version m-row))
             "snapshot_version" (when snap (:manifest-version snap))
             "snapshot_run_id"  (when snap (:run-id snap))
             "added"           (->ids (:added d))
             "removed"         (->ids (:removed d))
             "schema_changed"  (->ids (:schema-changed d))
             "doc_changed"     (->ids (:doc-changed d))
             "unchanged"       (->ids (:unchanged d))
             "empty"           (manifest-diff/empty-diff? d)
             "summary"         (manifest-diff/format-diff d)}))
        (json-response {"error" "missing id"} 400))

      ;; Manifest export
      (and (= method :post) (= uri "/api/manifest/export"))
      (let [{:keys [project_path manifest_id body]} (read-json-body request)
            manifest-body (or body
                             (when manifest_id
                               (:body (store/get-latest-manifest store manifest_id))))
            path (str (or project_path project-path) "/resources/manifest.edn")]
        (when manifest-body
          (spit path manifest-body))
        (json-response {"path" path}))

      ;; REPL (in-process eval)
      (and (= method :get) (= uri "/api/repl/status"))
      (json-response {"connected" true})

      (and (= method :get) (= uri "/api/repl/project-path"))
      (json-response {"path" (or project-path (System/getProperty "user.dir"))})

      (and (= method :post) (= uri "/api/repl/eval"))
      (let [{:keys [code]} (read-json-body request)
            result (ev/eval-code (or code "nil"))]
        (json-response {"result" (pr-str (:result result))
                        "output" (:output result)
                        "error"  (:error result)}))

      (and (= method :post) (= uri "/api/repl/instantiate"))
      (let [{:keys [cell_id]} (read-json-body request)]
        (if-not cell_id
          (json-response {"error" "missing cell_id"} 400)
          (let [cell (store/get-latest-cell store cell_id)]
            (if-not cell
              (json-response {"error" "cell not found"} 404)
              (let [result (ev/eval-code (:handler cell))]
                (json-response {"status" (name (:status result))
                                "output" (:output result)
                                "error"  (:error result)}))))))

      ;; Source generation
      (and (= method :post) (= uri "/api/source/generate"))
      (let [{:keys [output_dir base_namespace]} (read-json-body request)]
        (cond
          (not output_dir)
          (json-response {"error" "output_dir is required"} 400)
          (not base_namespace)
          (json-response {"error" "base_namespace is required"} 400)
          :else
          (try
            (let [result (source-gen/generate store
                           {:output-dir     output_dir
                            :base-namespace base_namespace})]
              (json-response result))
            (catch Exception e
              (json-response {"error" (.getMessage e)} 500)))))

      ;; Sessions
      (and (= method :get) (= uri "/api/sessions"))
      (json-response (mapv transform-keys (store/list-chat-sessions store)))

      (and (= method :get) (= uri "/api/session"))
      (if-let [id (query-param request "id")]
        (if-let [session (store/get-chat-session store id)]
          (json-response {"session"  (transform-keys session)
                          "messages" (mapv transform-keys
                                          (store/load-chat-messages store id))})
          (json-response {"error" "not found"} 404))
        (json-response {"error" "missing id"} 400))

      (and (= method :delete) (= uri "/api/session"))
      (if-let [id (query-param request "id")]
        (do (store/delete-chat-session! store id)
            (graph-agent/reset-session! id)
            (json-response {} 200))
        (json-response {"error" "missing id"} 400))

      (and (= method :post) (= uri "/api/session/clear"))
      (if-let [id (query-param request "id")]
        (do (store/clear-chat-messages! store id)
            (graph-agent/reset-session! id)
            (json-response {} 200))
        (json-response {"error" "missing id"} 400))

      ;; Tools manifest (UTCP for code-mode registration)
      (and (= method :get) (= uri "/api/tools/manifest"))
      (json-response (tools/tool-definitions))

      ;; Not found
      :else
      (json-response {"error" "not found"} 404))))

;; ── Main handler ───────────────────────────────────────────────

(defn- handler [ctx request]
  (if (= (:uri request) "/ws")
    (ws-handler ctx request)
    (api-handler ctx request)))

;; ── LLM client from config ────────────────────────────────────

(defn- create-llm-client
  "Creates an LLM client from explicit config or environment variables."
  [prefix config]
  (let [base-url (or (:base-url config)
                     (System/getenv (str prefix "_BASE_URL"))
                     "https://api.deepseek.com")
        api-key  (or (:api-key config)
                     (System/getenv (str prefix "_API_KEY")))
        model    (or (:model config)
                     (System/getenv (str prefix "_MODEL"))
                     "deepseek-chat")]
    (when api-key
      (llm/create-client base-url api-key model))))

;; ── Lifecycle ──────────────────────────────────────────────────

(defn start!
  "Starts the sporulator HTTP + WebSocket server. Returns a server map.
   Options:
     :port         - port to listen on (default 8420)
     :store        - sporulator store (from sporulator.store/open)
     :project-path - project directory path
     :graph-llm    - graph agent LLM config {:base-url, :api-key, :model}
                     Falls back to GRAPH_BASE_URL, GRAPH_API_KEY, GRAPH_MODEL env vars
     :cell-llm     - cell agent LLM config {:base-url, :api-key, :model}
                     Falls back to CELL_BASE_URL, CELL_API_KEY, CELL_MODEL env vars"
  [{:keys [port store project-path graph-llm cell-llm base-ns]
    :or   {port 8420 base-ns "app"}}]
  (let [graph-client (create-llm-client "GRAPH" graph-llm)
        cell-client  (create-llm-client "CELL" cell-llm)
        clients      (atom #{})
        ;; If the store is empty for this project but the project has
        ;; an existing manifest + cells on disk, seed them as the
        ;; baseline so orchestrate!'s diff sees them as carry-over
        ;; rather than re-implementing every cell from scratch.
        boot-result  (when (and store project-path)
                       (try (bootstrap/bootstrap-from-project!
                              {:store store
                               :project-path project-path
                               :base-ns base-ns})
                            (catch Throwable t
                              (println "Sporulator bootstrap warning:"
                                       (.getMessage t))
                              nil)))
        ctx          {:store store :project-path project-path
                      :clients clients
                      :llm-client graph-client
                      :cell-client cell-client}
        stop-fn      (http/run-server (fn [req] (handler ctx req)) {:port port})]
    (println (str "Sporulator server started on port " port))
    (when boot-result
      (println (str "  Bootstrap: "
                    (cond-> []
                      (:manifest-saved? boot-result)
                      (conj "manifest seeded")
                      (pos? (:cells-saved boot-result))
                      (conj (str (:cells-saved boot-result) " cell(s) seeded"))
                      (pos? (:cells-skipped boot-result))
                      (conj (str (:cells-skipped boot-result) " carry-over")))
                    " (manifest-id: " (:manifest-id boot-result) ")"
                    (when (and (not (:manifest-saved? boot-result))
                               (zero? (:cells-saved boot-result))
                               (zero? (:cells-skipped boot-result)))
                      "no project files found"))))
    (doseq [[label client prefix] [["Graph" graph-client "GRAPH"]
                                    ["Cell"  cell-client  "CELL"]]]
      (if client
        (println (str "  " label " LLM: "
                      (or (some-> (if (= prefix "GRAPH") graph-llm cell-llm) :model)
                          (System/getenv (str prefix "_MODEL"))
                          "deepseek-chat")))
        (println (str "  " label " LLM: not configured (set " prefix "_API_KEY)"))))
    {:stop-fn stop-fn :port port :store store :clients clients
     :project-path project-path
     :llm-client graph-client :cell-client cell-client}))

(defn stop!
  "Stops the sporulator HTTP + WebSocket server."
  [{:keys [stop-fn clients]}]
  (when stop-fn
    (when clients (reset! clients #{}))
    (stop-fn :timeout 500)
    (println "Sporulator server stopped")))
