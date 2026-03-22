(ns sporulator.server
  "HTTP + WebSocket server for the sporulator UI."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [sporulator.cell-agent :as cell-agent]
            [sporulator.eval :as ev]
            [sporulator.graph-agent :as graph-agent]
            [sporulator.llm :as llm]
            [sporulator.orchestrator :as orchestrator]
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
                   (str message "\n\nCurrent manifest:\n```edn\n" manifest "\n```")
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

(defn- handle-cell-implement [{:keys [cell-client llm-client store]} ch msg]
  (let [sid      (get-in msg [:payload :session_id])
        brief    (get-in msg [:payload :brief])
        client   (or cell-client llm-client)]
    (if-not client
      (send-ws! ch {:type "stream_error" :id sid
                    :payload "LLM not configured."})
      (future
        (try
          (let [result (cell-agent/implement-with-feedback
                         client brief
                         (fn [chunk]
                           (send-ws! ch {:type "stream_chunk" :id sid
                                         :payload {:chunk chunk}}))
                         :on-feedback
                         (fn [event]
                           (send-ws! ch {:type "feedback_event" :id sid
                                         :payload event})))]
            (when (and store (= :ok (:status result)))
              (cell-agent/save-cell! store result
                {:schema (:schema brief) :doc (:doc brief)}))
            (send-ws! ch {:type "cell_result" :id sid
                          :payload {:cell_id (:cell-id result)
                                    :status  (name (:status result))
                                    :code    (:code result)}}))
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
                content (llm/session-send-stream session client feedback
                          (fn [chunk]
                            (send-ws! ch {:type "stream_chunk" :id sid
                                          :payload {:chunk chunk}})))]
            (send-ws! ch {:type "stream_end" :id sid
                          :payload {:content content}}))
          (catch Exception e
            (send-ws! ch {:type "stream_error" :id sid
                          :payload (.getMessage e)})))))))

(defn- handle-orchestrate [{:keys [cell-client llm-client store]} ch msg]
  (let [sid    (get-in msg [:payload :session_id])
        leaves (get-in msg [:payload :leaves])
        base-ns (get-in msg [:payload :base_ns] "app")
        client (or cell-client llm-client)]
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
                          :base-ns      base-ns
                          :store        store
                          :on-event     (fn [event]
                                          (send-ws! ch {:type "orchestrator_event"
                                                        :id sid
                                                        :payload event}))
                          :on-chunk     (fn [chunk]
                                          (send-ws! ch {:type "stream_chunk"
                                                        :id sid
                                                        :payload {:chunk chunk}}))
                          :on-test-review
                          (fn [contracts]
                            ;; Send contracts to UI for review
                            (send-ws! ch {:type "test_review_contracts"
                                          :id sid
                                          :payload {:run_id run-id
                                                    :contracts (mapv (fn [c]
                                                                       {:cell_id      (:cell-id c)
                                                                        :test_code    (:test-code c)
                                                                        :test_body    (:test-body c)
                                                                        :review_notes (:review-notes c)
                                                                        :revision     (:revision c 0)
                                                                        :cell_brief   (select-keys (:brief c)
                                                                                        [:id :doc :schema :requires])})
                                                                     contracts)}})
                            ;; Block until user responds
                            (or (orchestrator/await-review gate 300000) []))
                          :max-attempts 3})]
            (swap! review-gates dissoc run-id)
            (send-ws! ch {:type (if (= :ok (:status result))
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

(defn- handle-ws-message [ctx ch msg]
  (case (:type msg)
    "graph_chat"             (handle-graph-chat ctx ch msg)
    "cell_implement"         (handle-cell-implement ctx ch msg)
    "cell_iterate"           (handle-cell-iterate ctx ch msg)
    "orchestrate"            (handle-orchestrate ctx ch msg)
    "test_review"            (handle-test-review ctx ch msg)
    "graph_review"           (handle-graph-review ctx ch msg)
    "impl_review"            (handle-impl-review ctx ch msg)
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
          (json-response (transform-keys cell))
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
  [{:keys [port store project-path graph-llm cell-llm] :or {port 8420}}]
  (let [graph-client (create-llm-client "GRAPH" graph-llm)
        cell-client  (create-llm-client "CELL" cell-llm)
        clients      (atom #{})
        ctx          {:store store :project-path project-path
                      :clients clients
                      :llm-client graph-client
                      :cell-client cell-client}
        stop-fn      (http/run-server (fn [req] (handler ctx req)) {:port port})]
    (println (str "Sporulator server started on port " port))
    (doseq [[label client prefix] [["Graph" graph-client "GRAPH"]
                                    ["Cell"  cell-client  "CELL"]]]
      (if client
        (println (str "  " label " LLM: "
                      (or (some-> (if (= prefix "GRAPH") graph-llm cell-llm) :model)
                          (System/getenv (str prefix "_MODEL"))
                          "deepseek-chat")))
        (println (str "  " label " LLM: not configured (set " prefix "_API_KEY)"))))
    {:stop-fn stop-fn :port port :store store :clients clients
     :llm-client graph-client :cell-client cell-client}))

(defn stop!
  "Stops the sporulator HTTP + WebSocket server."
  [{:keys [stop-fn clients]}]
  (when stop-fn
    (when clients (reset! clients #{}))
    (stop-fn :timeout 500)
    (println "Sporulator server stopped")))
