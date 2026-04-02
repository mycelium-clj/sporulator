(ns sporulator.feedback
  "General-purpose LLM feedback loop.
   Sends a prompt, evaluates the response mechanically,
   and iterates with error feedback until success or max attempts."
  (:require [sporulator.llm :as llm]))

(defn feedback-loop
  "General-purpose LLM feedback loop.

   Options:
     :client       — LLM client
     :session      — LLM session
     :initial-msg  — first prompt to send
     :extract-fn   — (fn [raw-response] -> extracted-value) parse LLM output
     :validate-fn  — (fn [extracted] -> {:ok result} | {:error message}) mechanical check
     :error-msg-fn — (fn [extracted error] -> fix-prompt-string) builds feedback
     :on-chunk     — streaming callback
     :on-attempt   — (fn [{:keys [attempt extracted error ok?]}]) progress callback
     :max-attempts — max retries (default 3)

   Returns {:status :ok :result value :attempts n}
        or {:status :error :error message :last-value value :attempts n}."
  [{:keys [client session initial-msg extract-fn validate-fn error-msg-fn
           on-chunk on-attempt max-attempts]
    :or {max-attempts 3
         extract-fn identity
         on-chunk (fn [_])
         on-attempt (fn [_])}}]
  (loop [msg     initial-msg
         attempt 0]
    (let [raw       (llm/session-send-stream session client msg on-chunk)
          extracted (extract-fn raw)
          result    (validate-fn extracted)]
      (on-attempt {:attempt (inc attempt) :extracted extracted
                   :error (:error result) :ok? (contains? result :ok)})
      (if (contains? result :ok)
        {:status :ok :result (:ok result) :attempts (inc attempt)}
        (if (>= attempt max-attempts)
          {:status :error :error (:error result) :last-value extracted :attempts (inc attempt)}
          (recur (error-msg-fn extracted (:error result))
                 (inc attempt)))))))
