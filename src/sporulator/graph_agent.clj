(ns sporulator.graph-agent
  "Graph agent for designing Mycelium workflow manifests via LLM."
  (:require [clojure.string :as str]
            [sporulator.extract :as extract]
            [sporulator.llm :as llm]
            [sporulator.manifest-validate :as mv]
            [sporulator.prompts :as prompts]
            [sporulator.store :as store]))

;; ── Manifest detection & validation ────────────────────────────

(defn looks-like-manifest?
  "Returns true if text contains the structural markers of a Mycelium manifest:
   :id, :cells, and either :edges or :pipeline."
  [text]
  (and (string? text)
       (str/includes? text ":id")
       (str/includes? text ":cells")
       (or (str/includes? text ":edges")
           (str/includes? text ":pipeline"))))

(defn validate-manifest-edn
  "Parses an EDN string and validates it as a manifest.
   Uses read-string (not edn/read-string) to handle (fn ...) in dispatches.
   Runs structural validation (reachability, schemas, edges) and schema
   compatibility checking between connected cells.
   Returns {:status :ok :manifest normalized-map}
        or {:status :parse-error :error message}
        or {:status :invalid-manifest :error message :issues [...] :mismatches [...]}."
  [edn-str]
  (let [parsed (mv/parse-manifest edn-str)]
    (if (:error parsed)
      {:status :parse-error :error (:error parsed)}
      (if-not (and (map? parsed)
                   (contains? parsed :id)
                   (contains? parsed :cells)
                   (or (contains? parsed :edges)
                       (contains? parsed :pipeline)))
        {:status :invalid-manifest
         :error  "Manifest must contain :id, :cells, and :edges or :pipeline"}
        ;; Run full programmatic validation
        (let [result (mv/validate-manifest parsed)]
          (case (:status result)
            :ok      {:status :ok :manifest (:manifest result)}
            :warning {:status     :ok
                      :manifest   (:manifest result)
                      :mismatches (:mismatches result)}
            :error   {:status :invalid-manifest
                      :error  (mv/format-issues result)
                      :issues (:issues result)
                      :mismatches (:mismatches result)}))))))

(defn extract-manifest
  "Extracts the first manifest from an LLM response.
   Looks for fenced code blocks, parses with read-string (handles fn forms),
   validates structure. Returns the parsed manifest map or nil."
  [response]
  (when-let [block (extract/extract-first-code-block response)]
    (when (looks-like-manifest? block)
      (let [parsed (mv/parse-manifest block)]
        (when (and (map? parsed)
                   (not (:error parsed))
                   (contains? parsed :id)
                   (contains? parsed :cells)
                   (or (contains? parsed :edges)
                       (contains? parsed :pipeline)))
          parsed)))))

;; ── Session management ─────────────────────────────────────────

(defonce ^:private sessions (atom {}))

(defn reset-all-sessions!
  "Clears all in-memory sessions. For testing."
  []
  (reset! sessions {}))

(defn reset-session!
  "Clears a single session from memory."
  [session-id]
  (swap! sessions dissoc session-id))

(defn get-or-create-session
  "Returns an existing session or creates a new one for the given ID.
   When a store is provided and the session is new, restores history from DB.
   Thread-safe: uses swap! to atomically check-and-create."
  ([session-id]
   (get-or-create-session session-id nil))
  ([session-id store]
   (let [existing (get @sessions session-id)]
     (if existing
       existing
       ;; Build the session before swapping to avoid doing work inside swap!
       (let [s (llm/create-session session-id prompts/graph-prompt)]
         (when store
           (when-let [msgs (seq (store/load-chat-messages store session-id))]
             (llm/session-set-messages!
               s (mapv (fn [m] {:role (:role m) :content (:content m)}) msgs))))
         ;; Atomically insert only if still absent
         (-> (swap! sessions (fn [m] (if (contains? m session-id) m (assoc m session-id s))))
             (get session-id)))))))

;; ── Persistence ────────────────────────────────────────────────

(defn persist-turn!
  "Saves a single conversation turn to the store.
   Creates the chat session if it doesn't exist yet."
  [store session-id role content]
  (when store
    (when-not (store/get-chat-session store session-id)
      (store/create-chat-session! store session-id "graph"))
    (store/save-chat-message! store session-id role content)))

(defn save-response-manifest!
  "Extracts a manifest from an LLM response and saves it to the store.
   Returns {:version n} on success, or nil if no manifest found."
  [store response]
  (when-let [manifest (extract-manifest response)]
    (let [manifest-id (str (:id manifest))
          version (store/save-manifest! store
                    {:id   manifest-id
                     :body (pr-str manifest)
                     :created-by "graph-agent"})]
      {:version version :manifest-id manifest-id})))

;; ── Decomposition ──────────────────────────────────────────────

(defn build-decompose-prompt
  "Builds a prompt that asks the LLM to break requirements into clear steps
   before designing the manifest."
  [requirements]
  (str "Before designing a workflow manifest, break down the following requirements "
       "into clear, sequential processing steps. For each step:\n"
       "1. Give it a short name\n"
       "2. Describe what it does in one sentence\n"
       "3. List its input and output data\n\n"
       "Focus on the data transformation pipeline — what goes in, what comes out "
       "at each stage. Do NOT write the manifest yet, just the step breakdown.\n\n"
       "**Requirements:**\n" requirements))

(defn- build-manifest-from-steps-prompt
  "Builds a prompt that asks the LLM to create a manifest from the decomposed steps."
  [steps-response]
  (str "Now create the Mycelium workflow manifest based on the steps you identified above. "
       "Include proper schemas for each cell based on the input/output data you described.\n\n"
       "Remember:\n"
       "- Wrap the manifest in a fenced ```edn code block\n"
       "- Output the COMPLETE manifest\n"
       "- Every cell needs :id, :doc, and :schema with :input and :output\n"
       "- Use :pipeline for linear flows, :edges for branching\n"))

(defn decompose-requirements
  "Step 1 only: decomposes requirements into processing steps.
   Returns the steps response string. Session is preserved for step 2."
  [client session-id requirements on-chunk & {:keys [store]}]
  (let [session (get-or-create-session session-id store)
        msg     (build-decompose-prompt requirements)]
    (persist-turn! store session-id "user" msg)
    (let [response (llm/session-send-stream session client msg on-chunk)]
      (persist-turn! store session-id "assistant" response)
      response)))

(defn build-manifest
  "Step 2: builds manifest from the decomposed steps (already in session).
   If feedback is provided, sends it to the LLM first to revise the steps,
   then builds the manifest from the revised understanding."
  [client session-id on-chunk & {:keys [store feedback]}]
  (let [session (get-or-create-session session-id store)]
    ;; If user gave feedback, send it first so LLM adjusts
    (when feedback
      (let [fb-msg (str "Before building the manifest, incorporate this feedback on the steps:\n\n"
                        feedback "\n\nNow adjust your understanding accordingly.")]
        (persist-turn! store session-id "user" fb-msg)
        (let [response (llm/session-send-stream session client fb-msg on-chunk)]
          (persist-turn! store session-id "assistant" response))))
    ;; Build the manifest
    (let [manifest-msg (build-manifest-from-steps-prompt nil)]
      (persist-turn! store session-id "user" manifest-msg)
      (let [response (llm/session-send-stream session client manifest-msg on-chunk)]
        (persist-turn! store session-id "assistant" response)
        (when store
          (save-response-manifest! store response))
        response))))

(defn design-workflow
  "Two-step workflow design: first decomposes requirements into steps,
   then creates the manifest from those steps.

   on-chunk-decompose — called with each token during decomposition phase
   on-chunk-manifest  — called with each token during manifest phase
   (If only on-chunk-decompose is provided, it's used for both phases.)

   Returns {:steps decomposition-response :manifest-response manifest-response}."
  [client session-id requirements on-chunk-decompose
   & {:keys [store on-feedback on-chunk-manifest]}]
  (let [session (get-or-create-session session-id store)
        on-chunk-manifest (or on-chunk-manifest on-chunk-decompose)]
    ;; Step 1: Decompose requirements into steps
    (let [decompose-msg (build-decompose-prompt requirements)]
      (persist-turn! store session-id "user" decompose-msg)
      (let [steps-response (llm/session-send-stream session client decompose-msg on-chunk-decompose)]
        (persist-turn! store session-id "assistant" steps-response)
        (when on-feedback
          (on-feedback {:event-type "steps_complete"
                        :steps      steps-response}))

        ;; Step 2: Build manifest from the decomposed steps
        (let [manifest-msg (build-manifest-from-steps-prompt steps-response)]
          (persist-turn! store session-id "user" manifest-msg)
          (let [manifest-response (llm/session-send-stream session client manifest-msg on-chunk-manifest)]
            (persist-turn! store session-id "assistant" manifest-response)
            (when store
              (save-response-manifest! store manifest-response))
            (when on-feedback
              (on-feedback {:event-type "design_complete"}))
            {:steps             steps-response
             :manifest-response manifest-response}))))))

;; ── Chat ───────────────────────────────────────────────────────

(defn chat-stream
  "Sends a user message via the graph agent and streams the response.
   on-chunk is called with each token fragment.
   Persists both turns to the store when provided.
   Returns the full response content."
  [client session-id message on-chunk & {:keys [store]}]
  (let [session (get-or-create-session session-id store)]
    (persist-turn! store session-id "user" message)
    (let [content (llm/session-send-stream session client message on-chunk)]
      (persist-turn! store session-id "assistant" content)
      (when store
        (save-response-manifest! store content))
      content)))

(defn chat-stream-with-feedback
  "Sends a user message, streams the response, and validates any manifest
   in the response. If the manifest has parse errors, sends the error back
   to the LLM for correction (up to max-retries attempts).

   on-chunk    — called with each token fragment
   on-feedback — called with {:event-type :attempt :code :output :message}

   Returns the final response content."
  [client session-id message on-chunk
   & {:keys [store on-feedback max-retries]
      :or {max-retries 3}}]
  (let [session (get-or-create-session session-id store)]
    (loop [msg     message
           attempt 0]
      (persist-turn! store session-id "user" msg)
      (let [content (llm/session-send-stream session client msg on-chunk)]
        (persist-turn! store session-id "assistant" content)
        ;; Check for manifest in response
        (let [block (extract/extract-first-code-block content)]
          (if (and block (looks-like-manifest? block))
            ;; Validate the manifest EDN
            (let [validation (validate-manifest-edn block)]
              (if (= :ok (:status validation))
                (do
                  (when store
                    (save-response-manifest! store content))
                  (when on-feedback
                    (on-feedback {:event-type "success"
                                  :attempt    (inc attempt)
                                  :code       block
                                  :output     ""
                                  :message    "Manifest validated"}))
                  content)
                ;; Validation failed — retry if we have attempts left
                (if (< attempt max-retries)
                  (let [fix-msg (str "The manifest has validation issues:\n"
                                     (:error validation)
                                     (when (:mismatches validation)
                                       (str "\n\n" (mv/format-issues
                                                     {:mismatches (:mismatches validation)})))
                                     "\n\nPlease fix the manifest and return the corrected EDN.")]
                    (when on-feedback
                      (on-feedback {:event-type "error"
                                    :attempt    (inc attempt)
                                    :code       block
                                    :output     (:error validation)
                                    :message    "Manifest validation failed, retrying"}))
                    (recur fix-msg (inc attempt)))
                  ;; Out of retries — return as-is
                  (do
                    (when on-feedback
                      (on-feedback {:event-type "error"
                                    :attempt    (inc attempt)
                                    :code       block
                                    :output     (:error validation)
                                    :message    "Max retries reached"}))
                    content))))
            ;; No manifest in response — just return
            content))))))
