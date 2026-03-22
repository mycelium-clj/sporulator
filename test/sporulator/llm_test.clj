(ns sporulator.llm-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [sporulator.llm :as llm])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

;; =============================================================
;; Mock HTTP server for testing
;; =============================================================

(defn- write-response [^HttpExchange exchange status body & {:keys [content-type] :or {content-type "application/json"}}]
  (.add (.getResponseHeaders exchange) "Content-Type" content-type)
  (let [bytes (.getBytes ^String body "UTF-8")]
    (.sendResponseHeaders exchange status (count bytes))
    (with-open [os (.getResponseBody exchange)]
      (.write os bytes))))

(defn- read-request-body [^HttpExchange exchange]
  (json/read-str (slurp (.getRequestBody exchange)) :key-fn keyword))

(defn- start-mock-server
  "Starts a mock HTTP server with the given handler fn.
   Handler receives [exchange request-body] and should call write-response."
  [handler-fn]
  (let [server (HttpServer/create (InetSocketAddress. 0) 0)]
    (.createContext server "/v1/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (handler-fn exchange (read-request-body exchange))
            (catch Exception e
              (write-response exchange 500 (str "{\"error\":\"" (.getMessage e) "\"}"))
              (.close exchange))))))
    (.setExecutor server nil)
    (.start server)
    {:server server
     :port (.getPort (.getAddress server))
     :base-url (str "http://localhost:" (.getPort (.getAddress server)))}))

(defn- stop-mock-server [{:keys [^HttpServer server]}]
  (.stop server 0))

;; =============================================================
;; Client tests — translated from Go client_test.go
;; =============================================================

(deftest chat-non-streaming-test
  (let [mock (start-mock-server
               (fn [exchange body]
                 ;; Verify request
                 (is (= "POST" (.getRequestMethod exchange)))
                 (is (= "/v1/chat/completions" (.getPath (.getRequestURI exchange))))
                 (is (str/includes? (first (.get (.getRequestHeaders exchange) "Authorization")) "Bearer test-key"))
                 (is (= "test-model" (:model body)))
                 (is (= false (:stream body)))
                 (write-response exchange 200
                   (json/write-str
                     {:choices [{:message {:content "Hello, world!"}}]
                      :usage {:prompt_tokens 10 :completion_tokens 5}}))))]
    (try
      (let [client (llm/create-client (:base-url mock) "test-key" "test-model")
            resp (llm/chat client
                   {:messages [{:role "user" :content "Hi"}]
                    :temperature 0.3
                    :max-tokens 100})]
        (is (= "Hello, world!" (:content resp)))
        (is (= 10 (:prompt-tokens resp)))
        (is (= 5 (:completion-tokens resp))))
      (finally (stop-mock-server mock)))))

(deftest chat-streaming-test
  (let [mock (start-mock-server
               (fn [exchange body]
                 (is (= true (:stream body)))
                 (.add (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
                 (.sendResponseHeaders exchange 200 0)
                 (with-open [os (.getResponseBody exchange)]
                   (doseq [chunk ["Hello" ", " "world" "!"]]
                     (let [data (json/write-str {:choices [{:delta {:content chunk}}]})]
                       (.write os (.getBytes (str "data: " data "\n\n") "UTF-8"))
                       (.flush os)))
                   ;; Final chunk with usage
                   (let [data (json/write-str {:choices [{:delta {} :finish_reason "stop"}]
                                              :usage {:prompt_tokens 10 :completion_tokens 4}})]
                     (.write os (.getBytes (str "data: " data "\n\n") "UTF-8"))
                     (.write os (.getBytes "data: [DONE]\n\n" "UTF-8"))
                     (.flush os)))))]
    (try
      (let [client (llm/create-client (:base-url mock) "test-key" "test-model")
            chunks (atom [])
            resp (llm/chat-stream client
                   {:messages [{:role "user" :content "Hi"}]
                    :temperature 0.3
                    :max-tokens 100}
                   (fn [chunk] (swap! chunks conj chunk)))]
        (is (= "Hello, world!" (:content resp)))
        (is (= ["Hello" ", " "world" "!"] @chunks))
        (is (= 10 (:prompt-tokens resp)))
        (is (= 4 (:completion-tokens resp))))
      (finally (stop-mock-server mock)))))

(deftest chat-api-error-test
  (let [mock (start-mock-server
               (fn [exchange _body]
                 (write-response exchange 429
                   "{\"error\": {\"message\": \"rate limited\"}}")))]
    (try
      (let [client (llm/create-client (:base-url mock) "test-key" "test-model")]
        (is (thrown-with-msg? Exception #"429"
              (llm/chat client {:messages [{:role "user" :content "Hi"}]}))))
      (finally (stop-mock-server mock)))))

(deftest chat-empty-choices-test
  (let [mock (start-mock-server
               (fn [exchange _body]
                 (write-response exchange 200
                   (json/write-str {:choices []}))))]
    (try
      (let [client (llm/create-client (:base-url mock) "test-key" "test-model")]
        (is (thrown? Exception
              (llm/chat client {:messages [{:role "user" :content "Hi"}]}))))
      (finally (stop-mock-server mock)))))

;; =============================================================
;; Session tests
;; =============================================================

(deftest session-send-test
  (let [call-count (atom 0)
        mock (start-mock-server
               (fn [exchange body]
                 (swap! call-count inc)
                 (let [msgs (:messages body)]
                   ;; First call: system + user = 2
                   ;; Second call: system + user + assistant + user = 4
                   (if (= 1 @call-count)
                     (is (= 2 (count msgs)))
                     (is (= 4 (count msgs)))))
                 (write-response exchange 200
                   (json/write-str
                     {:choices [{:message {:content (str "reply-" @call-count)}}]}))))]
    (try
      (let [client (llm/create-client (:base-url mock) "test-key" "test-model")
            session (llm/create-session "test" "You are a helper.")]
        (let [reply1 (llm/session-send session client "Hello")]
          (is (= "reply-1" reply1)))
        (let [reply2 (llm/session-send session client "Follow up")]
          (is (= "reply-2" reply2)))
        ;; Verify history
        (let [history (llm/session-history session)]
          (is (= 4 (count history)))
          (is (= {:role "user" :content "Hello"} (first history)))
          (is (= {:role "assistant" :content "reply-1"} (second history)))))
      (finally (stop-mock-server mock)))))

(deftest session-reset-test
  (let [mock (start-mock-server
               (fn [exchange _body]
                 (write-response exchange 200
                   (json/write-str {:choices [{:message {:content "ok"}}]}))))]
    (try
      (let [client (llm/create-client (:base-url mock) "test-key" "test-model")
            session (llm/create-session "test" "system")]
        (llm/session-send session client "msg1")
        (llm/session-send session client "msg2")
        (is (= 4 (count (llm/session-history session))))
        (llm/session-reset! session)
        (is (= 0 (count (llm/session-history session))))
        ;; System prompt still works
        (let [msgs (llm/session-messages session)]
          (is (= 1 (count msgs)))
          (is (= "system" (:role (first msgs))))))
      (finally (stop-mock-server mock)))))

(deftest session-send-error-rollback-test
  (let [mock (start-mock-server
               (fn [exchange _body]
                 (write-response exchange 500 "error")))]
    (try
      (let [client (llm/create-client (:base-url mock) "test-key" "test-model")
            session (llm/create-session "test" "system")]
        (is (thrown? Exception (llm/session-send session client "will fail")))
        ;; User message should have been rolled back
        (is (= 0 (count (llm/session-history session)))))
      (finally (stop-mock-server mock)))))

(deftest session-send-stream-test
  (let [mock (start-mock-server
               (fn [exchange _body]
                 (.add (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
                 (.sendResponseHeaders exchange 200 0)
                 (with-open [os (.getResponseBody exchange)]
                   (doseq [chunk ["streamed" " " "reply"]]
                     (let [data (json/write-str {:choices [{:delta {:content chunk}}]})]
                       (.write os (.getBytes (str "data: " data "\n\n") "UTF-8"))
                       (.flush os)))
                   (.write os (.getBytes "data: [DONE]\n\n" "UTF-8"))
                   (.flush os))))]
    (try
      (let [client (llm/create-client (:base-url mock) "test-key" "test-model")
            session (llm/create-session "test" "system")
            chunks (atom [])
            reply (llm/session-send-stream session client "stream me"
                    (fn [chunk] (swap! chunks conj chunk)))]
        (is (= "streamed reply" reply))
        (is (= 3 (count @chunks)))
        ;; History should contain the full reply
        (let [history (llm/session-history session)]
          (is (= 2 (count history)))
          (is (= "streamed reply" (:content (second history))))))
      (finally (stop-mock-server mock)))))
