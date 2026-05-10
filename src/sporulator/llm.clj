(ns sporulator.llm
  "OpenAI-compatible LLM client with SSE streaming and session management.
   Works with DeepSeek, OpenAI, OpenRouter, and other compatible providers."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.io BufferedReader InputStreamReader]))

;; =============================================================
;; Client
;; =============================================================

(defn create-client
  "Creates an OpenAI-compatible API client.
   base-url: API base URL (e.g. \"https://api.deepseek.com\")
   api-key:  API key
   model:    model name (e.g. \"deepseek-chat\")"
  [base-url api-key model]
  {:base-url (str/replace base-url #"/+$" "")
   :api-key api-key
   :model model
   :http-client (-> (HttpClient/newBuilder)
                    (.connectTimeout (Duration/ofMinutes 2))
                    (.build))})

(defn- build-request
  "Builds an HTTP request for the chat completions endpoint."
  [{:keys [base-url api-key http-client]} body]
  (let [json-body (json/write-str body)]
    (-> (HttpRequest/newBuilder)
        (.uri (URI/create (str base-url "/v1/chat/completions")))
        (.header "Content-Type" "application/json")
        (.header "Authorization" (str "Bearer " api-key))
        (.timeout (Duration/ofMinutes 10))
        (.POST (HttpRequest$BodyPublishers/ofString json-body))
        (.build))))

(defn- api-request-body
  "Constructs the API request JSON body."
  [model {:keys [messages temperature max-tokens tools tool-choice]} stream?]
  (cond-> {:model model
           :messages messages
           :stream stream?}
    temperature  (assoc :temperature temperature)
    max-tokens   (assoc :max_tokens max-tokens)
    (seq tools)  (assoc :tools tools)
    tool-choice  (assoc :tool_choice tool-choice)))

(defn- parse-tool-calls
  "Converts a raw `tool_calls` array from an API response into a vector with
   parsed JSON arguments. Returns nil for empty/missing input."
  [raw]
  (when (seq raw)
    (mapv (fn [tc]
            (let [args-json (or (get-in tc [:function :arguments]) "")
                  parsed    (try (json/read-str args-json :key-fn keyword)
                                 (catch Exception _ nil))]
              {:id             (:id tc)
               :name           (get-in tc [:function :name])
               :arguments-json args-json
               :arguments      (or parsed {})}))
          raw)))

;; =============================================================
;; Non-streaming chat
;; =============================================================

(defn chat
  "Sends a non-streaming chat completions request.
   Returns {:content string :prompt-tokens int :completion-tokens int}."
  [{:keys [model http-client] :as client} request]
  (let [body (api-request-body model request false)
        http-req (build-request client body)
        response (.send ^HttpClient http-client http-req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)]
    (when (not= 200 status)
      (throw (ex-info (str "LLM API error " status ": "
                           (subs (.body response) 0 (min 300 (count (.body response)))))
                      {:status status})))
    (let [parsed (json/read-str (.body response) :key-fn keyword)]
      (when (empty? (:choices parsed))
        (throw (ex-info "LLM: no choices in response" {:response parsed})))
      (let [choice (get-in parsed [:choices 0])
            msg    (:message choice)]
        {:content           (:content msg)
         :tool-calls        (parse-tool-calls (:tool_calls msg))
         :finish-reason     (:finish_reason choice)
         :prompt-tokens     (get-in parsed [:usage :prompt_tokens] 0)
         :completion-tokens (get-in parsed [:usage :completion_tokens] 0)}))))

;; =============================================================
;; Streaming chat (SSE)
;; =============================================================

(defn chat-stream
  "Sends a streaming chat completions request.
   on-chunk is called with each content fragment as it arrives.
   Returns the complete response after the stream ends."
  [{:keys [model http-client] :as client} request on-chunk]
  (let [body (api-request-body model request true)
        http-req (build-request client body)
        response (.send ^HttpClient http-client http-req (HttpResponse$BodyHandlers/ofInputStream))
        status (.statusCode response)]
    (when (not= 200 status)
      (let [error-body (slurp (.body response))]
        (throw (ex-info (str "LLM API error " status ": "
                             (subs error-body 0 (min 300 (count error-body))))
                        {:status status}))))
    (let [full-content (StringBuilder.)
          full-reasoning (StringBuilder.)
          prompt-tokens (atom 0)
          completion-tokens (atom 0)
          finish-reason (atom nil)
          ;; idx -> {:id :name :args-sb StringBuilder}
          tool-call-state (atom (sorted-map))]
      (with-open [reader (BufferedReader. (InputStreamReader. (.body response) "UTF-8"))]
        (loop []
          (when-let [line (.readLine reader)]
            (when (str/starts-with? line "data: ")
              (let [payload (subs line 6)]
                (when (not= "[DONE]" payload)
                  (try
                    (let [delta (json/read-str payload :key-fn keyword)]
                      (when-let [choices (seq (:choices delta))]
                        (let [d (:delta (first choices))]
                          (when-let [content (:content d)]
                            (when (not= "" content)
                              (.append full-content content)
                              (when on-chunk (on-chunk content))))
                          (when-let [reasoning (:reasoning_content d)]
                            (when (not= "" reasoning)
                              (.append full-reasoning reasoning)))
                          (when-let [tcs (:tool_calls d)]
                            (doseq [tc tcs]
                              (let [idx (or (:index tc) 0)]
                                (swap! tool-call-state
                                  (fn [m]
                                    (let [entry (or (get m idx)
                                                    {:args-sb (StringBuilder.)})
                                          entry (cond-> entry
                                                  (:id tc)
                                                  (assoc :id (:id tc))
                                                  (get-in tc [:function :name])
                                                  (assoc :name
                                                         (get-in tc [:function :name])))]
                                      (when-let [frag (get-in tc [:function :arguments])]
                                        (.append ^StringBuilder (:args-sb entry) frag))
                                      (assoc m idx entry))))))))
                        (when-let [fr (:finish_reason (first choices))]
                          (reset! finish-reason fr)))
                      (when-let [usage (:usage delta)]
                        (reset! prompt-tokens (:prompt_tokens usage 0))
                        (reset! completion-tokens (:completion_tokens usage 0))))
                    (catch Exception _ nil))))) ;; skip malformed chunks
            (when (and line (not= "data: [DONE]" line))
              (recur)))))
      ;; Fallback: if content empty but reasoning has data (DeepSeek reasoner)
      (let [content (str full-content)
            tool-calls
            (->> @tool-call-state
                 vals
                 (mapv (fn [{:keys [id name args-sb]}]
                         (let [args-json (str args-sb)
                               parsed    (try (json/read-str args-json :key-fn keyword)
                                              (catch Exception _ nil))]
                           {:id             id
                            :name           name
                            :arguments-json args-json
                            :arguments      (or parsed {})}))))]
        {:content (if (and (str/blank? content) (pos? (.length full-reasoning)))
                    (str full-reasoning)
                    content)
         :tool-calls (when (seq tool-calls) tool-calls)
         :finish-reason @finish-reason
         :prompt-tokens @prompt-tokens
         :completion-tokens @completion-tokens}))))

;; =============================================================
;; Session (multi-turn conversation)
;; =============================================================

(defn create-session
  "Creates a session with a system prompt for multi-turn conversations.
   State is stored in an atom."
  [id system-prompt]
  {:id id
   :system-prompt system-prompt
   :messages (atom [])})

(defn session-messages
  "Returns the full message list including system prompt."
  [{:keys [system-prompt messages]}]
  (let [msgs @messages]
    (if (not (str/blank? system-prompt))
      (into [{:role "system" :content system-prompt}] msgs)
      (vec msgs))))

(defn session-history
  "Returns a copy of conversation messages (excluding system prompt)."
  [{:keys [messages]}]
  @messages)

(defn session-reset!
  "Clears conversation history, keeping the system prompt."
  [{:keys [messages]}]
  (reset! messages []))

(defn session-set-messages!
  "Replaces conversation history. Used to restore from storage."
  [{:keys [messages]} msgs]
  (reset! messages (vec msgs)))

(defn- assistant-msg-from-resp
  "Builds the assistant message to persist into history from a response.
   Includes structured tool_calls when present so subsequent tool result
   messages can reference them by id."
  [resp]
  (cond-> {:role "assistant" :content (:content resp)}
    (seq (:tool-calls resp))
    (assoc :tool_calls
           (mapv (fn [tc]
                   {:id       (:id tc)
                    :type     "function"
                    :function {:name      (:name tc)
                               :arguments (:arguments-json tc)}})
                 (:tool-calls resp)))))

(defn session-send
  "Sends a user message and appends both turns to session history.
   Returns the full response map: {:content :tool-calls :finish-reason
   :prompt-tokens :completion-tokens}.

   Optional kwargs:
     :tools        — vector of OpenAI-style tool function defs
     :tool-choice  — \"auto\" / \"none\" / specific tool selector
     :temperature  — default 0.3
     :max-tokens   — default 8192"
  [{:keys [messages] :as session} client user-message
   & {:keys [temperature max-tokens tools tool-choice]
      :or   {temperature 0.3 max-tokens 8192}}]
  (swap! messages conj {:role "user" :content user-message})
  (try
    (let [resp (chat client {:messages    (session-messages session)
                             :temperature temperature
                             :max-tokens  max-tokens
                             :tools       tools
                             :tool-choice tool-choice})]
      (swap! messages conj (assistant-msg-from-resp resp))
      resp)
    (catch Exception e
      (swap! messages (fn [msgs] (if (seq msgs) (pop msgs) msgs)))
      (throw e))))

(defn session-send-stream
  "Streaming counterpart to session-send. on-chunk is called with each text
   fragment as it arrives. Tool-call deltas don't stream to on-chunk — they
   are surfaced in the returned :tool-calls.

   Returns the full response map: {:content :tool-calls :finish-reason
   :prompt-tokens :completion-tokens}."
  [{:keys [messages] :as session} client user-message on-chunk
   & {:keys [temperature max-tokens tools tool-choice]
      :or   {temperature 0.3 max-tokens 8192}}]
  (swap! messages conj {:role "user" :content user-message})
  (try
    (let [resp (chat-stream client {:messages    (session-messages session)
                                    :temperature temperature
                                    :max-tokens  max-tokens
                                    :tools       tools
                                    :tool-choice tool-choice}
                            on-chunk)]
      (swap! messages conj (assistant-msg-from-resp resp))
      resp)
    (catch Exception e
      (swap! messages (fn [msgs] (if (seq msgs) (pop msgs) msgs)))
      (throw e))))

(defn session-append-tool-result!
  "Appends a tool result message to session history.
   `tool-call-id` must match the id of a tool_call from the prior assistant
   turn; `content` is the (string) result of executing that call."
  [{:keys [messages]} tool-call-id content]
  (swap! messages conj {:role         "tool"
                        :tool_call_id tool-call-id
                        :content      (str content)}))

(defn session-continue-stream
  "Resumes a session by sending the current message history without
   prepending a new user message. Used to drive the next assistant turn
   after appending tool results via session-append-tool-result!.

   Returns the same response map as session-send-stream."
  [{:keys [messages] :as session} client on-chunk
   & {:keys [temperature max-tokens tools tool-choice]
      :or   {temperature 0.3 max-tokens 8192}}]
  (let [resp (chat-stream client {:messages    (session-messages session)
                                  :temperature temperature
                                  :max-tokens  max-tokens
                                  :tools       tools
                                  :tool-choice tool-choice}
                          on-chunk)]
    (swap! messages conj (assistant-msg-from-resp resp))
    resp))
