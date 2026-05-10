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
          (is (= "reply-1" (:content reply1))))
        (let [reply2 (llm/session-send session client "Follow up")]
          (is (= "reply-2" (:content reply2))))
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
        (is (= "streamed reply" (:content reply)))
        (is (= 3 (count @chunks)))
        ;; History should contain the full reply
        (let [history (llm/session-history session)]
          (is (= 2 (count history)))
          (is (= "streamed reply" (:content (second history))))))
      (finally (stop-mock-server mock)))))

;; =============================================================
;; Tool-use protocol tests
;; =============================================================

(def ^:private edit-tool-def
  {:type "function"
   :function {:name        "edit"
              :description "Edit a virtual file"
              :parameters  {:type "object"
                            :properties {:path        {:type "string"}
                                         :old_string  {:type "string"}
                                         :new_string  {:type "string"}}
                            :required ["path" "old_string" "new_string"]}}})

(def ^:private read-tool-def
  {:type "function"
   :function {:name        "read"
              :description "Read a virtual file"
              :parameters  {:type "object"
                            :properties {:path {:type "string"}}
                            :required ["path"]}}})

(deftest chat-forwards-tools-test
  (testing "chat passes :tools and :tool-choice into the request body"
    (let [seen-body (atom nil)
          mock (start-mock-server
                 (fn [exchange body]
                   (reset! seen-body body)
                   (write-response exchange 200
                     (json/write-str
                       {:choices [{:message      {:content "ok"}
                                   :finish_reason "stop"}]}))))]
      (try
        (let [client (llm/create-client (:base-url mock) "test-key" "test-model")]
          (llm/chat client {:messages    [{:role "user" :content "hi"}]
                            :tools       [edit-tool-def read-tool-def]
                            :tool-choice "auto"}))
        (is (= 2 (count (:tools @seen-body))))
        (is (= "edit" (get-in @seen-body [:tools 0 :function :name])))
        (is (= "auto" (:tool_choice @seen-body)))
        (finally (stop-mock-server mock))))))

(deftest chat-parses-single-tool-call-test
  (testing "chat surfaces a tool_call from the response"
    (let [mock (start-mock-server
                 (fn [exchange _body]
                   (write-response exchange 200
                     (json/write-str
                       {:choices [{:message
                                   {:content nil
                                    :tool_calls
                                    [{:id   "call_1"
                                      :type "function"
                                      :function
                                      {:name      "edit"
                                       :arguments (json/write-str
                                                    {:path "handler.clj"
                                                     :old_string "x"
                                                     :new_string "y"})}}]}
                                   :finish_reason "tool_calls"}]}))))]
      (try
        (let [client (llm/create-client (:base-url mock) "test-key" "test-model")
              resp   (llm/chat client {:messages [{:role "user" :content "edit it"}]
                                       :tools    [edit-tool-def]})]
          (is (= "tool_calls" (:finish-reason resp)))
          (is (nil? (:content resp)))
          (is (= 1 (count (:tool-calls resp))))
          (let [tc (first (:tool-calls resp))]
            (is (= "call_1" (:id tc)))
            (is (= "edit" (:name tc)))
            (is (= {:path "handler.clj" :old_string "x" :new_string "y"}
                   (:arguments tc)))))
        (finally (stop-mock-server mock))))))

(deftest chat-parses-multiple-tool-calls-test
  (testing "chat returns multiple parallel tool_calls in order"
    (let [mock (start-mock-server
                 (fn [exchange _body]
                   (write-response exchange 200
                     (json/write-str
                       {:choices [{:message
                                   {:content nil
                                    :tool_calls
                                    [{:id "call_a" :type "function"
                                      :function {:name "read"
                                                 :arguments (json/write-str {:path "a.clj"})}}
                                     {:id "call_b" :type "function"
                                      :function {:name "read"
                                                 :arguments (json/write-str {:path "b.clj"})}}]}
                                   :finish_reason "tool_calls"}]}))))]
      (try
        (let [client (llm/create-client (:base-url mock) "test-key" "test-model")
              resp   (llm/chat client {:messages [{:role "user" :content "read both"}]
                                       :tools    [read-tool-def]})]
          (is (= 2 (count (:tool-calls resp))))
          (is (= ["call_a" "call_b"] (mapv :id (:tool-calls resp))))
          (is (= "a.clj" (get-in (:tool-calls resp) [0 :arguments :path])))
          (is (= "b.clj" (get-in (:tool-calls resp) [1 :arguments :path]))))
        (finally (stop-mock-server mock))))))

(deftest chat-stream-fragmented-tool-call-test
  (testing "chat-stream accumulates tool_call args from delta fragments"
    (let [mock
          (start-mock-server
            (fn [exchange _body]
              (.add (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
              (.sendResponseHeaders exchange 200 0)
              (with-open [os (.getResponseBody exchange)]
                ;; Frame 1: announce the tool call (id + name, empty args)
                (let [d (json/write-str
                          {:choices [{:delta
                                      {:tool_calls
                                       [{:index 0
                                         :id    "call_x"
                                         :type  "function"
                                         :function {:name "edit" :arguments ""}}]}}]})]
                  (.write os (.getBytes (str "data: " d "\n\n") "UTF-8")))
                ;; Frames 2-4: arguments arrive fragmented
                (doseq [frag ["{\"path\":\"" "handler.clj\",\"old_string\":\"a\","
                              "\"new_string\":\"b\"}"]]
                  (let [d (json/write-str
                            {:choices [{:delta
                                        {:tool_calls
                                         [{:index 0
                                           :function {:arguments frag}}]}}]})]
                    (.write os (.getBytes (str "data: " d "\n\n") "UTF-8"))))
                ;; Frame 5: finish
                (let [d (json/write-str
                          {:choices [{:delta {} :finish_reason "tool_calls"}]
                           :usage {:prompt_tokens 5 :completion_tokens 9}})]
                  (.write os (.getBytes (str "data: " d "\n\n") "UTF-8")))
                (.write os (.getBytes "data: [DONE]\n\n" "UTF-8"))
                (.flush os))))]
      (try
        (let [client (llm/create-client (:base-url mock) "test-key" "test-model")
              resp   (llm/chat-stream client
                       {:messages [{:role "user" :content "edit"}]
                        :tools    [edit-tool-def]}
                       (fn [_]))]
          (is (= "tool_calls" (:finish-reason resp)))
          (is (= 1 (count (:tool-calls resp))))
          (let [tc (first (:tool-calls resp))]
            (is (= "call_x" (:id tc)))
            (is (= "edit" (:name tc)))
            (is (= {:path "handler.clj" :old_string "a" :new_string "b"}
                   (:arguments tc)))))
        (finally (stop-mock-server mock))))))

(deftest session-continue-stream-test
  (testing "session-continue-stream resumes without adding a user message"
    (let [seen-messages (atom nil)
          mock
          (start-mock-server
            (fn [exchange body]
              (reset! seen-messages (:messages body))
              (.add (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
              (.sendResponseHeaders exchange 200 0)
              (with-open [os (.getResponseBody exchange)]
                (let [d (json/write-str
                          {:choices [{:delta {:content "ok"} :finish_reason "stop"}]})]
                  (.write os (.getBytes (str "data: " d "\n\n") "UTF-8")))
                (.write os (.getBytes "data: [DONE]\n\n" "UTF-8"))
                (.flush os))))]
      (try
        (let [client  (llm/create-client (:base-url mock) "test-key" "test-model")
              session (llm/create-session "test" "system")]
          ;; Pre-seed history with a tool round
          (swap! (:messages session) conj
                 {:role "user" :content "edit it"}
                 {:role "assistant" :content nil
                  :tool_calls [{:id "call_1" :type "function"
                                :function {:name "edit" :arguments "{}"}}]})
          (llm/session-append-tool-result! session "call_1" "edit applied")
          ;; Continue without adding a user message
          (let [resp (llm/session-continue-stream session client (fn [_]))]
            (is (= "ok" (:content resp))))
          ;; Verify the request sent included system, user, assistant(tool_calls), tool — no extra user
          (is (= ["system" "user" "assistant" "tool"]
                 (mapv :role @seen-messages)))
          ;; Local history grew by one assistant turn
          (is (= 4 (count (llm/session-history session))))
          (is (= "assistant" (:role (last (llm/session-history session))))))
        (finally (stop-mock-server mock))))))

(deftest session-tool-roundtrip-test
  (testing "session-send with :tools persists tool_calls; tool result feeds next turn"
    (let [call-count (atom 0)
          seen-msgs  (atom nil)
          mock
          (start-mock-server
            (fn [exchange body]
              (swap! call-count inc)
              (case @call-count
                1 (write-response exchange 200
                    (json/write-str
                      {:choices [{:message
                                  {:content nil
                                   :tool_calls
                                   [{:id   "call_1"
                                     :type "function"
                                     :function
                                     {:name "read"
                                      :arguments (json/write-str {:path "handler.clj"})}}]}
                                  :finish_reason "tool_calls"}]}))
                2 (do (reset! seen-msgs (:messages body))
                      (write-response exchange 200
                        (json/write-str
                          {:choices [{:message {:content "saw the file"}
                                      :finish_reason "stop"}]}))))))]
      (try
        (let [client  (llm/create-client (:base-url mock) "test-key" "test-model")
              session (llm/create-session "test" "system")
              resp1   (llm/session-send session client
                        "what's in handler?" :tools [read-tool-def])]
          (is (= "tool_calls" (:finish-reason resp1)))
          (is (= "call_1" (-> resp1 :tool-calls first :id)))

          ;; Append tool result, then send the follow-up turn.
          (llm/session-append-tool-result! session "call_1" "(fn [r d] {:ok true})")
          (let [resp2 (llm/session-send session client
                        "summarise" :tools [read-tool-def])]
            (is (= "saw the file" (:content resp2))))

          ;; Verify the second request carried the assistant tool_calls + tool result.
          (let [msgs @seen-msgs
                roles (mapv :role msgs)]
            ;; system, user1, assistant(tool_calls), tool(result), user2
            (is (= ["system" "user" "assistant" "tool" "user"] roles))
            (let [asst (nth msgs 2)
                  tool (nth msgs 3)]
              (is (seq (:tool_calls asst)))
              (is (= "call_1" (-> asst :tool_calls first :id)))
              (is (= "call_1" (:tool_call_id tool)))
              (is (= "(fn [r d] {:ok true})" (:content tool))))))
        (finally (stop-mock-server mock))))))
