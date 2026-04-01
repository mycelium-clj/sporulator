(ns sporulator.cell-agent
  "Cell agent for implementing Mycelium cells via LLM with eval feedback loops."
  (:require [clojure.string :as str]
            [sporulator.codegen :as codegen]
            [sporulator.eval :as ev]
            [sporulator.extract :as extract]
            [sporulator.llm :as llm]
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
  "Generates a cell, evaluates it in-process, and auto-fixes errors.

   on-chunk    — called with each token fragment
   on-feedback — called with {:event-type :attempt :code :output :message}

   Returns {:status :ok :cell-id :code :raw :session}
        or {:status :error :error msg}"
  [client brief on-chunk
   & {:keys [on-feedback max-attempts]
      :or   {max-attempts 3}}]
  (let [session  (create-cell-session (:id brief))
        prompt   (build-cell-prompt brief)
        feedback (or on-feedback (fn [_]))]
    (loop [msg     prompt
           attempt 0]
      (let [content (llm/session-send-stream session client msg on-chunk)
            result  (build-result (:id brief) content)
            code    (:code result)]
        (if-not code
          (do
            (feedback {:event-type "error"
                       :attempt    (inc attempt)
                       :message    "No defcell form found in LLM response"})
            (assoc result :status :error
                          :error "No defcell form found"
                          :session session))

          ;; Try to eval the cell
          (let [source   (or (extract/extract-first-code-block content) code)
                eval-res (ev/eval-code source)]
            (if (= :ok (:status eval-res))
              ;; Eval succeeded — verify cell registered
              (let [verify (ev/verify-cell-contract
                             (->keyword (:id brief)))]
                (if (= :ok (:status verify))
                  (do
                    (feedback {:event-type "success"
                               :attempt    (inc attempt)
                               :code       source
                               :output     (:output eval-res)
                               :message    "Cell loaded successfully"})
                    (assoc result :status :ok :session session))
                  ;; Cell not in registry
                  (do
                    (feedback {:event-type "error"
                               :attempt    (inc attempt)
                               :code       source
                               :output     (:error verify)
                               :message    "Cell not registered after eval"})
                    (if (< attempt max-attempts)
                      (recur (str "The code evaluated without error but the cell "
                                  (:id brief) " was not found in the registry.\n\n"
                                  "Please fix and return the complete source including "
                                  "the `(ns ...)` form and `(cell/defcell ...)` form.")
                             (inc attempt))
                      (assoc result :status :error
                                    :error (:error verify)
                                    :session session)))))

              ;; Eval failed — ask LLM to fix
              (do
                (feedback {:event-type "error"
                           :attempt    (inc attempt)
                           :code       source
                           :output     (:error eval-res)
                           :message    "Eval error, requesting fix"})
                (if (< attempt max-attempts)
                  (do
                    (feedback {:event-type "fix"
                               :attempt    (inc attempt)
                               :message    "Requesting fix from LLM"})
                    (recur (str "The code produced this error when evaluated:\n\n```\n"
                                (:error eval-res)
                                "\n```\n\nPlease fix the issue and return the corrected "
                                "`cell/defcell` form with the full `(ns ...)` declaration.")
                           (inc attempt)))
                  (assoc result :status :error
                                :error (:error eval-res)
                                :session session))))))))))

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
