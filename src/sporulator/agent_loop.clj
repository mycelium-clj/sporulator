(ns sporulator.agent-loop
  "Interactive tool-use dispatch agent for cell implementation.
   Manages the DISPATCH → REVIEW phase machine, renders prompts,
   parses tool calls, dispatches execution, and assembles final results.
   Replaces the monolithic implement-from-contract loop."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [sporulator.codegen :as codegen]
            [sporulator.code-graph :as cg]
            [sporulator.eval :as ev]
            [sporulator.extract :as extract]
            [sporulator.llm :as llm]
            [sporulator.mcp-bridge :as mcp]
            [sporulator.prompts :as prompts]
            [sporulator.source-gen :as source-gen]
            [sporulator.store :as store]
            [sporulator.tool-registry :as tools]))

(def system-prompt-template
  "You are implementing a Mycelium cell in Clojure via TDD. You work step-by-step,
emitting exactly ONE tool call per response as a fenced JSON block.

## Protocol
On every turn, emit:
```tool-call
{\"name\": \"<tool>\", \"args\": {...}}
```

## Phases
- DISPATCH: Explore context, write tests, write handler, run tests.
- REVIEW: When tests pass, review the result. Approve, revise, or give_up.

## Rules
- NEVER write (ns ...) or (cell/defcell ...) — the harness assembles those.
- Write ONLY the handler body: (fn [resources data] ...)
- DO NOT read files or run shell commands — use the provided tools.
- The REPL evaluates your code; you inspect results via eval().
- Use eval() to experiment before committing to write_handler.")

(defn- ->keyword
  [id]
  (cond
    (keyword? id) id
    (and (string? id) (str/starts-with? id ":")) (keyword (subs id 1))
    (string? id) (keyword id)
    :else (keyword (str id))))

(defn init-session
  "Creates the initial session state from orchestration context."
  [{:keys [client cell-id cell-ns brief test-code schema-parsed
           graph mcp-ctx turn-budget on-event on-chunk
           store run-id project-path base-ns task]
    :or   {turn-budget 15
           on-event    (fn [_])
           on-chunk    (fn [_])}}]
  (let [cell-id-kw (->keyword (or cell-id (:id brief)))
        session    (llm/create-session (str "agent:" cell-id-kw) system-prompt-template)]
    {:phase              :dispatch
     :turn               0
     :turn-budget        turn-budget
     :session            session
     :cell-id            cell-id-kw
     :cell-ns            (or cell-ns "")
     :brief              brief
     :test-code          test-code
     :test-ns-name       (when test-code (second (re-find #"\(ns\s+(\S+)" test-code)))
     :schema-parsed      schema-parsed
     :graph              graph
     :mcp-ctx            mcp-ctx
     :green-handler      nil
     :green-tests        nil
     :current-handler    nil
     :current-tests      test-code
     :helpers            nil
     :tool-history       []
     :last-tests-green?  false
     :repair-attempts    0
     :client             client
     :on-event           on-event
     :on-chunk           on-chunk
     :store              store
     :run-id             run-id
     :project-path       project-path
     :base-ns            base-ns
     :task               task}))

(defn- emit
  [state phase status & {:as extra}]
  (when-let [f (:on-event state)]
    (let [base {"phase" phase "status" status}
          converted (reduce-kv (fn [m k v]
                                 (assoc m (str/replace (name k) "-" "_") v))
                               base extra)]
      (f converted))))

(defn- add-to-history
  "Appends a tool call + result pair to the conversation history."
  [state {:keys [name args] :as call} result]
  (update state :tool-history conj {:call call :result result}))

(defn- mcp-ctx
  "Returns the MCP context, enriched with live session state."
  [state]
  (assoc (:mcp-ctx state)
         :current-handler (:current-handler state)
         :current-tests   (:current-tests state)
         :green-handler   (:green-handler state)
         :green-tests     (:green-tests state)))

(defn- render-dispatch-prompt
  [state]
  (let [brief    (:brief state)
        cell-id  (:cell-id state)
        schema   (:schema brief)
        task     (:task state)
        requires (:requires brief)
        history  (:tool-history state)]
    (str "You are implementing cell `" cell-id "`.\n\n"
         "**Task:** " (or task "Implement the cell to pass all tests.") "\n\n"
         "**Schema:**\n" (or schema "{}") "\n\n"
         "**Resources:** "
         (if (seq requires) (str/join ", " (map name requires)) "none")
         "\n\n---\n\n"
         "Available tools:\n"
         (tools/render-tool-catalog)
         "\n\n---\n\n"
         (when (seq history)
           (str "--- Conversation so far ---\n"
                (str/join "\n\n"
                  (map-indexed
                    (fn [i {:keys [call result]}]
                      (str "Turn " (inc i) ":\n"
                           "Tool: " (name (:name call)) "\n"
                           "Result: " result))
                    history))
                "\n\n---\n\n"))
         "Your next tool call:")))

(defn- render-review-prompt
  [state]
  (let [cell-id        (:cell-id state)
        cell-ns        (:cell-ns state)
        brief          (:brief state)
        schema         (:schema brief)
        handler        (:green-handler state)
        tests          (:green-tests state)
        test-ns        (:test-ns-name state)]
    (str "REVIEW PHASE — tests just went green.\n\n"
         "Target: " cell-id "\n"
         "Cell NS: " cell-ns "\n\n"
         "SPEC:\n" (or schema "{}") "\n\n"
         "Final handler:\n```clojure\n"
         (or handler "(not recorded)")
         "\n```\n\n"
         "Final test file:\n```clojure\n"
         (or tests "(not recorded)")
         "\n```\n\n"
         "Available tools: approve(), revise({reason}), give_up({reason})\n\n"
         "Your move: approve(), revise({reason}), or give_up({reason}).\n\n"
         "Default to approve. Revise only when you spot a concrete spec-vs-tests\n"
         "mismatch. Do NOT revise for cosmetic reasons.")))

(defn- tool-result
  "Formats a tool execution result for conversation history."
  [success? payload]
  (if success?
    (str "ok — " payload)
    (str "ERROR — " payload)))

(defn- assemble-and-eval
  "Assembles the full cell source from current state, evals it + tests.
   Returns {:status :ok :passed? true|false :output str} or {:status :error}"
  [state]
  (let [cell-id        (:cell-id state)
        cell-ns        (:cell-ns state)
        brief          (:brief state)
        schema-parsed  (:schema-parsed state)
        handler        (:current-handler state)
        test-code      (:current-tests state)
        helpers        (:helpers state)]
    (if-not handler
      {:status :error :output "No handler written yet. Use write_handler first."}
      (let [;; Assemble cell source
            source (codegen/assemble-cell-source
                     {:cell-ns        cell-ns
                      :cell-id        cell-id
                      :doc            (or (:doc brief) "")
                      :schema         schema-parsed
                      :requires       (mapv keyword (or (:requires brief) []))
                      :extra-requires []
                      :helpers        (or helpers [])
                      :fn-body        handler})
            ;; Fix test require so it doesn't try to load from disk
            test-src-fixed (if cell-ns
                             (str/replace test-code
                               (str "[" cell-ns "]")
                               (str "[" cell-ns " :as _cell-ns]"))
                             test-code)
            combined (str source "\n\n" test-src-fixed)
            eval-res (ev/eval-code combined)]
        (if (not= :ok (:status eval-res))
          {:status :error :output (str "Eval error: " (:error eval-res))}
          (let [test-ns-name (:test-ns-name state)
                test-ns      (when test-ns-name (find-ns (symbol test-ns-name)))
                test-res     (if test-ns
                               (ev/run-cell-tests test-code)
                               {:status :error
                                :output (str "Test namespace not found: " test-ns-name)})]
            (if (not= :ok (:status test-res))
              {:status :error :output (:output test-res)}
              {:status   :ok
               :passed?  (:passed? test-res)
               :output   (str (:output eval-res) "\n" (:output test-res))
               :summary  (:summary test-res)})))))))

(defn- handle-dispatch-tool
  [state {:keys [name args] :as call}]
  (let [g    (:graph state)
        ctx   (mcp-ctx state)]
    (case name
      :get_spec
      (add-to-history state call (tool-result true (mcp/get-spec ctx)))

      :get_task
      (add-to-history state call (tool-result true (mcp/get-task ctx)))

      :get_context
      (add-to-history state call (tool-result true (mcp/get-context ctx)))

      :list_siblings
      (add-to-history state call (tool-result true (mcp/list-siblings ctx)))

      :get_sibling
      (let [sib-name (:name args)]
        (if sib-name
          (add-to-history state call (tool-result true (mcp/get-sibling ctx sib-name)))
          (add-to-history state call (tool-result false "Missing required arg: name"))))

      :get_callers
      (let [target (if-let [n (:name args)]
                     (->keyword n)
                     (:cell-id state))]
        (if g
          (add-to-history state call
            (tool-result true
              (let [callers (cg/callers g target)]
                (if (seq callers)
                  (str "Callers of " target ": " (str/join ", " callers))
                  (str "No callers of " target " found.")))))
          (add-to-history state call (tool-result false "No code graph available."))))

      :get_callees
      (let [target (if-let [n (:name args)]
                     (->keyword n)
                     (:cell-id state))]
        (if g
          (add-to-history state call
            (tool-result true
              (let [callees (cg/callees g target)]
                (if (seq callees)
                  (str "Called by " target ": " (str/join ", " callees))
                  (str "No callees from " target " found.")))))
          (add-to-history state call (tool-result false "No code graph available."))))

      :graph_impact
      (let [target (:target args)]
        (if (and g (or target (:cell-id state)))
          (let [t (or (some-> target ->keyword) (:cell-id state))
                impacted (cg/impact g t)]
            (add-to-history state call
              (tool-result true
                (if (seq impacted)
                  (str "Impact of " t ": " (str/join ", " impacted))
                  (str "No callers affected by " t ".")))))
          (add-to-history state call (tool-result false "No code graph or target."))))

      :graph_path
      (let [from (some-> (:from args) ->keyword)
            to   (some-> (:to args) ->keyword)]
        (if (and g from to)
          (if-let [p (cg/path g from to)]
            (add-to-history state call
              (tool-result true (str "Path " from " -> " to ": " (str/join " -> " p))))
            (add-to-history state call
              (tool-result true (str "No path from " from " to " to "."))))
          (add-to-history state call
            (tool-result false "Missing required args: from and to, or no graph."))))

      :list_ns
      (add-to-history state call (tool-result true (mcp/list-loaded-ns ctx)))

      :inspect_ns
      (let [ns-name (:ns args)]
        (if ns-name
          (add-to-history state call (tool-result true (mcp/inspect-ns ctx ns-name)))
          (add-to-history state call (tool-result false "Missing required arg: ns"))))

      :eval
      (let [code (:code args)]
        (if code
          (let [res (ev/eval-code code)]
            (if (= :ok (:status res))
              (add-to-history state call
                (tool-result true (str (pr-str (:result res))
                                       (when (seq (:output res))
                                         (str "\n" (:output res))))))
              (add-to-history state call
                (tool-result false (:error res)))))
          (add-to-history state call (tool-result false "Missing required arg: code"))))

      :define
      (let [code (:code args)]
        (if code
          (let [res (ev/eval-code code)]
            (if (= :ok (:status res))
              (do (swap! (:helpers state) (fnil conj []) (read-string code))
                  (add-to-history state call
                    (tool-result true (str "Defined: " (pr-str (:result res))))))
              (add-to-history state call
                (tool-result false (:error res)))))
          (add-to-history state call (tool-result false "Missing required arg: code"))))

      :write_handler
      (let [content (:content args)]
        (if content
          (let [;; Extract fn body from content — may be raw code or a wrapped fn
                forms (try (binding [*read-eval* false]
                             (read-string content))
                           (catch Exception _ nil))
                fn-body (if (and (seq? forms) (= 'fn (first forms)))
                          forms
                          (extract/extract-fn-body content))]
            (if fn-body
              (-> state
                  (assoc :current-handler fn-body
                         :last-tests-green? false)
                  (add-to-history call (tool-result true "wrote handler")))
              (add-to-history state call
                (tool-result false "Could not extract (fn [resources data] ...) form from content."))))
          (add-to-history state call (tool-result false "Missing required arg: content"))))

      :write_test
      (let [content (:content args)]
        (if content
          (let [body (or (extract/extract-first-code-block content) content)]
            (-> state
                (assoc :current-tests body
                       :last-tests-green? false)
                (add-to-history call (tool-result true (str "wrote test code (" (count body) " chars)")))))
          (add-to-history state call (tool-result false "Missing required arg: content"))))

      :patch_handler
      (let [search  (:search args)
            replace (:replace args)
            handler (:current-handler state)]
        (cond
          (not search)
          (add-to-history state call (tool-result false "Missing required arg: search"))
          (nil? replace)
          (add-to-history state call (tool-result false "Missing required arg: replace"))
          (not handler)
          (add-to-history state call (tool-result false "No handler to patch."))
          :else
          (let [handler-str (pr-str handler)
                idx (str/index-of handler-str search)]
            (if (nil? idx)
              (add-to-history state call
                (tool-result false (str "Search string not found in handler. Search must be unique and exact.")))
              (let [new-str (str/replace-first handler-str search replace)
                    new-handler (try (binding [*read-eval* false]
                                       (read-string new-str))
                                     (catch Exception _ nil))]
                (if new-handler
                  (-> state
                      (assoc :current-handler new-handler
                             :last-tests-green? false)
                      (add-to-history call (tool-result true "patched handler")))
                  (add-to-history state call
                    (tool-result false "Patched handler is not a valid Clojure form."))))))))

      :patch_test
      (let [search  (:search args)
            replace (:replace args)
            tests   (:current-tests state)]
        (cond
          (not search)
          (add-to-history state call (tool-result false "Missing required arg: search"))
          (nil? replace)
          (add-to-history state call (tool-result false "Missing required arg: replace"))
          (not tests)
          (add-to-history state call (tool-result false "No tests to patch."))
          :else
          (let [idx (str/index-of tests search)]
            (if (nil? idx)
              (add-to-history state call
                (tool-result false (str "Search string not found in tests. Search must be unique and exact.")))
              (let [new-tests (str/replace-first tests search replace)]
                (-> state
                    (assoc :current-tests new-tests
                           :last-tests-green? false)
                    (add-to-history call (tool-result true "patched tests"))))))))

      :run_tests
      (if-not (:current-handler state)
        (add-to-history state call (tool-result false "No handler written. Use write_handler first."))
        (let [result (assemble-and-eval state)]
          (if (not= :ok (:status result))
            (add-to-history state call (tool-result false (:output result)))
            (if (:passed? result)
              (-> state
                  (assoc :green-handler (:current-handler state)
                         :green-tests   (:current-tests state)
                         :last-tests-green? true
                         :phase :review)
                  (add-to-history call
                    (tool-result true (str "ALL TESTS PASSED\n"
                                           (or (:output result) "")))))
              (-> state
                  (assoc :last-tests-green? false)
                  (add-to-history call
                    (tool-result false (or (:output result) "Tests failed."))))))))

      :lint
      (if-let [handler (:current-handler state)]
        (let [;; Assemble for lint context
              source-str (pr-str handler)
              lint-res   (ev/lint-code source-str)]
          (if (seq (:errors lint-res))
            (add-to-history state call
              (tool-result false
                (str "Lint errors:\n" (str/join "\n"
                                        (map #(str "- Line " (:line %) ": " (:message %))
                                             (:errors lint-res))))))
            (add-to-history state call (tool-result true "No lint errors."))))
        (add-to-history state call (tool-result false "No handler to lint.")))

      :check_schema
      (add-to-history state call
        (tool-result true "Schema validation: no runtime schema checking configured."))

      :done
      (if (:last-tests-green? state)
        (assoc state :phase :review)
        (add-to-history state call
          (tool-result false "Tests are not green yet. Run run_tests first.")))

      :give_up
      (assoc state
             :phase :done
             :status :gave_up
             :give-up-reason (or (:reason args) "No reason given"))

      ;; Unknown tool
      (add-to-history state call
        (tool-result false
          (str "Unknown tool: " (name name)
               ". Valid tools for dispatch: "
               (str/join ", " (map name (keys tools/dispatch-phase-tools)))))))))

(defn- handle-review-tool
  [state {:keys [name args] :as call}]
  (case name
    :approve
    (assoc state :phase :done :status :ok)

    :revise
    (let [reason (:reason args)]
      (-> state
          (assoc :phase :dispatch
                 :last-tests-green? false)
          (add-to-history call
            (tool-result true
              (str "Revising implementation. Reason: " (or reason "unspecified")
                   ". Back to DISPATCH phase.")))))

    :give_up
    (assoc state :phase :done :status :gave_up
           :give-up-reason (or (:reason args) "No reason given"))

    ;; Unknown tool
    (add-to-history state call
      (tool-result false
        (str "Unknown tool: " (name name)
             ". Valid tools for review: approve, revise, give_up")))))

(defn- process-turn
  "Processes one turn: render prompt → LLM → parse → execute → update state."
  [state]
  (let [prompt   (if (= :review (:phase state))
                   (render-review-prompt state)
                   (render-dispatch-prompt state))
        client   (:client state)
        on-chunk (:on-chunk state)
        response (llm/session-send-stream (:session state) client prompt on-chunk)
        call     (tools/parse-tool-call response)]
    (cond
      (nil? call)
      (-> state
          (update :turn inc)
          (add-to-history {:name "__no_call__" :args {}}
            "No tool-call fence found. Emit exactly ONE fenced JSON block per turn:\n```tool-call\n{\"name\": \"<tool>\", \"args\": {...}}\n```"))

      (:parse-error call)
      (-> state
          (update :turn inc)
          (add-to-history call
            (str "Parse error: " (:parse-error call))))

      (not (tools/tool-allowed-in-phase? (:name call) (:phase state)))
      (-> state
          (update :turn inc)
          (add-to-history call
            (str "Tool '" (name (:name call)) "' not available in "
                 (name (:phase state)) " phase.")))

      (= :review (:phase state))
      (-> (handle-review-tool state call)
          (update :turn inc))

      :else
      (-> (handle-dispatch-tool state call)
          (update :turn inc)))))

(defn- finalize
  "Assembles the final result map from the completed state."
  [state]
  (let [cell-id      (:cell-id state)
        cell-ns      (:cell-ns state)
        brief        (:brief state)
        handler      (:green-handler state)
        schema-parsed (:schema-parsed state)
        store        (:store state)
        run-id       (:run-id state)
        project-path (:project-path state)
        base-ns      (:base-ns state)
        on-event     (:on-event state)]
    (case (:status state)
      :ok
      (let [source (codegen/assemble-cell-source
                     {:cell-ns        cell-ns
                      :cell-id        cell-id
                      :doc            (or (:doc brief) "")
                      :schema         schema-parsed
                      :requires       (mapv keyword (or (:requires brief) []))
                      :extra-requires []
                      :helpers        (or (:helpers state) [])
                      :fn-body        handler})]
        (when store
          (store/save-cell! store
            {:id         cell-id
             :handler    source
             :schema     (or (:schema brief) "")
             :doc        (or (:doc brief) "")
             :created-by "agent-loop-tdd"}))
        (when (and project-path base-ns)
          (source-gen/write-cell! project-path base-ns (str cell-id) source)
          (emit state "cell_implement" "file_written" :cell-id cell-id))
        (emit state "cell_implement" "done" :cell-id cell-id)
        {:status  :ok
         :cell-id cell-id
         :code    source
         :raw     (pr-str handler)
         :session (:session state)})

      :gave_up
      (emit state "cell_implement" "gave_up" :cell-id cell-id
            :reason (:give-up-reason state))
      {:status       :error
       :cell-id      cell-id
       :error        (str "Agent gave up: " (:give-up-reason state))
       :session      (:session state)}

      :stagnated
      (emit state "cell_implement" "stagnated" :cell-id cell-id)
      {:status       :error
       :cell-id      cell-id
       :error        (str "Turn budget exhausted in dispatch phase after "
                          (:turn state) " turns")
       :session      (:session state)}

      ;; default: error
      {:status       :error
       :cell-id      cell-id
       :error        (str "Agent ended with status: " (:status state) " after "
                          (:turn state) " turns")
       :session      (:session state)})))

(defn run!
  "Main entry point: runs the dispatch loop for a cell.
   Returns {:status :ok :cell-id :code :session}
        or {:status :error :cell-id :error :session}"
  [opts]
  (let [state (atom (init-session opts))]
    (emit @state "cell_implement" "started" :cell-id (:cell-id @state))
    (loop []
      (let [s @state]
        (cond
          (= :done (:phase s))
          (finalize s)

          (>= (:turn s) (:turn-budget s))
          (if (= :review (:phase s))
            ;; Auto-approve on budget exhaustion in review
            (do (swap! state assoc :phase :done :status :ok)
                (finalize @state))
            ;; Stagnated in dispatch
            (do (swap! state assoc :phase :done :status :stagnated)
                (finalize @state)))

          :else
          (do (swap! state process-turn)
              (recur)))))))

(defn reflect-on-stagnation
  "Sends a single-turn diagnostic prompt when stagnated.
   Returns a string with the LLM's analysis of why the cell wasn't implemented."
  [client {:keys [cell-id brief test-code tool-history session]}]
  (let [hist-lines (map-indexed
                     (fn [i {:keys [call result]}]
                       (str "Turn " (inc i) ": " (name (:name call)) " → " result))
                     tool-history)
        hist-str   (str/join "\n" hist-lines)
        prompt     (str "You attempted to implement cell `" cell-id "` but "
                        "exhausted the turn budget without getting tests to pass.\n\n"
                        "Cell spec:\n" (:doc brief) "\n"
                        "Schema: " (:schema brief) "\n\n"
                        "Your tool call history:\n" hist-str "\n\n"
                        "Diagnose why the cell wasn't implemented. Was it too complex? "
                        "Was the test contract wrong? Was there a missing dependency? "
                        "Be specific and actionable.")]
    (llm/session-send-stream session client prompt (fn [_]))))
