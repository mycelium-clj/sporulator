(ns sporulator.graph-agent
  "Graph agent for designing Mycelium workflow manifests via LLM."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [sporulator.extract :as extract]
            [sporulator.llm :as llm]
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
  "Parses an EDN string and checks it looks like a valid manifest.
   Returns {:status :ok :manifest parsed-map}
        or {:status :parse-error :error message}
        or {:status :invalid-manifest :error message}."
  [edn-str]
  (try
    (let [parsed (edn/read-string edn-str)]
      (if (and (map? parsed)
               (contains? parsed :id)
               (contains? parsed :cells)
               (or (contains? parsed :edges)
                   (contains? parsed :pipeline)))
        {:status :ok :manifest parsed}
        {:status :invalid-manifest
         :error  "Manifest must contain :id, :cells, and :edges or :pipeline"}))
    (catch Exception e
      {:status :parse-error
       :error  (.getMessage e)})))

(defn extract-manifest
  "Extracts the first manifest from an LLM response.
   Looks for fenced code blocks, parses as EDN, validates structure.
   Returns the parsed manifest map or nil."
  [response]
  (when-let [block (extract/extract-first-code-block response)]
    (when (looks-like-manifest? block)
      (try
        (let [parsed (edn/read-string block)]
          (when (and (map? parsed)
                     (contains? parsed :id)
                     (contains? parsed :cells)
                     (or (contains? parsed :edges)
                         (contains? parsed :pipeline)))
            parsed))
        (catch Exception _ nil)))))

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
                  (let [fix-msg (str "The manifest failed to parse:\n"
                                     (:error validation)
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
