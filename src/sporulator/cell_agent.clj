(ns sporulator.cell-agent
  "Cell agent for implementing Mycelium cells via LLM with eval feedback loops."
  (:require [clojure.string :as str]
            [sporulator.agent-loop :as agent-loop]
            [sporulator.extract :as extract]
            [sporulator.llm :as llm]
            [sporulator.mcp-bridge :as mcp]
            [sporulator.prompts :as prompts]
            [sporulator.store :as store]))

;; ── Helpers ────────────────────────────────────────────────────

(defn- ->keyword
  "Converts a cell-id string like \":order/compute-tax\" to keyword :order/compute-tax.
   If already a keyword, returns as-is."
  [id]
  (cond
    (keyword? id) id
    (and (string? id) (str/starts-with? id ":")) (keyword (subs id 1))
    (string? id) (keyword id)
    :else id))

;; ── Prompt construction ────────────────────────────────────────

(defn build-cell-prompt
  "Constructs an implementation prompt from a cell brief map.
   Brief keys: :id, :doc, :schema, :requires, :context, :resource-docs"
  [{:keys [id doc schema requires context resource-docs]}]
  (str "Implement the following Mycelium cell.\n\n"
       (when id
         (str "**Cell ID:** `" id "`\n"))
       (when doc
         (str "\n**Implementation Requirements:**\n" doc "\n"))
       (when schema
         (str "\n**Contract (input/output schema):**\n```\n" schema "\n```\n"))
       (if (seq requires)
         (str "\n**Required resources:**\n"
              (str/join "\n"
                (map (fn [r]
                       (let [r-name (str r)
                             doc (get resource-docs (keyword r-name)
                                     (get resource-docs r-name))]
                         (if doc
                           (str "- `" r-name "` — " doc)
                           (str "- `" r-name "`"))))
                     requires))
              "\n\nAccess resources in the handler: `(let [{:keys ["
              (str/join " " (map #(str (name %)) requires))
              "]} resources] ...)`\n")
         "\n**Required resources:** none\n")
       (when context
         (str "\n**Workflow position (predecessors/successors):**\n" context "\n"))
       "\nReturn a single ```clojure fenced code block with the complete source: "
       "`(ns ...)` with `[mycelium.cell :as cell]` require, "
       "then `(cell/defcell ...)` with a `:doc` string in the opts map."))

;; ── Result extraction ──────────────────────────────────────────

(defn build-result
  "Builds a cell result map from an LLM response.
   Extracts the defcell form from the response text."
  [cell-id response]
  {:cell-id cell-id
   :code    (extract/extract-defcell response)
   :raw     response})

;; ── Persistence ────────────────────────────────────────────────

(defn save-cell!
  "Saves a cell result to the store. Returns the version number."
  [store result {:keys [schema doc requires created-by]
                 :or   {created-by "cell-agent"}}]
  (store/save-cell! store
    {:id         (:cell-id result)
     :handler    (:code result)
     :schema     (or schema "")
     :doc        (or doc "")
     :requires   (or requires "")
     :created-by created-by}))

;; ── Session management ─────────────────────────────────────────

(defn- create-cell-session
  "Creates a new LLM session for a cell agent."
  [cell-id]
  (llm/create-session (str "cell:" cell-id) prompts/cell-prompt))

;; ── Implementation ─────────────────────────────────────────────

(defn implement-stream
  "Generates a cell implementation with streaming.
   Returns a cell result map."
  [client brief on-chunk]
  (let [session (create-cell-session (:id brief))
        prompt  (build-cell-prompt brief)
        content (llm/session-send-stream session client prompt on-chunk)]
    (assoc (build-result (:id brief) content)
           :session session)))

(defn implement-with-feedback
  "Generates a cell using the interactive agent loop.
   The agent loop uses tool-call dispatch (DISPATCH -> REVIEW phases)
   for exploration and incremental implementation.

   on-chunk    — called with each token fragment
   on-feedback — called with {:event-type :attempt :code :output :message}
   code-graph  — code graph facts set (from build-cell-graph)
   mcp-ctx     — mcp-bridge context map

   Returns {:status :ok :cell-id :code :raw :session}
        or {:status :error :error msg}"
  [client brief on-chunk
   & {:keys [on-feedback max-attempts code-graph mcp-ctx]
      :or   {max-attempts 3}}]
  (let [cell-kw  (->keyword (:id brief))
        feedback (or on-feedback (fn [_]))
        result   (agent-loop/run!
                   {:client      client
                    :cell-id     cell-kw
                    :brief       brief
                    :graph       code-graph
                    :mcp-ctx     (or mcp-ctx
                                   (mcp/build-context
                                     {:cell-id   cell-kw
                                      :brief     brief
                                      :graph     code-graph}))
                    :turn-budget max-attempts
                    :on-chunk    on-chunk
                    :on-event    (fn [event]
                                  (feedback {:event-type (get event "status" "event")
                                             :message    (get event "message" "")
                                             :code       (get event "code" "")
                                             :output     (get event "output" "")}))})]
    (if (= :ok (:status result))
      {:status  :ok
       :cell-id (:id brief)
       :code    (:code result)
       :raw     (:raw result)
       :session (:session result)}
      {:status  :error
       :cell-id (:id brief)
       :error   (:error result)
       :session (:session result)})))

;; ── Parallel implementation ────────────────────────────────────

(defn implement-cells
  "Implements multiple cells in parallel using futures.
   Returns a vector of result maps."
  [client briefs & {:keys [on-chunk on-feedback]
                    :or   {on-chunk (fn [_])}}]
  (let [futures (mapv (fn [brief]
                        (future
                          (try
                            (implement-with-feedback
                              client brief on-chunk
                              :on-feedback on-feedback)
                            (catch Exception e
                              {:status  :error
                               :cell-id (:id brief)
                               :error   (.getMessage e)}))))
                      briefs)]
    (mapv deref futures)))
