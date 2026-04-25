(ns sporulator.tool-registry
  "Tool catalog, parsing, and dispatch for the agent loop.
   Defines the tool-to-LLM interface: catalog metadata, rendering for
   prompts, parsing tool-call fences from LLM responses, and routing
   execution to handler functions."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(def ^:private tool-call-fence-re
  #"(?i)```tool-call\n(.*?)\n```")

(def dispatch-phase-tools
  "Tools available during the DISPATCH phase."
  #{:get_spec :get_task :get_context :list_siblings :get_sibling
    :get_callers :get_callees :graph_impact :graph_path
    :list_ns :inspect_ns
    :eval :define :write_handler :write_test
    :patch_handler :patch_test
    :run_tests :lint :check_schema
    :give_up :done})

(def review-phase-tools
  "Tools available during the REVIEW phase."
  #{:approve :revise :give_up})

(def catalog
  "Full tool catalog with metadata for prompt rendering."
  [{:name        :get_spec
    :description "Get the current cell's specification: doc, schema, required resources"
    :args        {}
    :category    :context}
   {:name        :get_task
    :description "Get the overall orchestration task description"
    :args        {}
    :category    :context}
   {:name        :get_context
    :description "Get workflow position: predecessors, successors, consumed/produced keys"
    :args        {}
    :category    :context}
   {:name        :list_siblings
    :description "List other cells being implemented in this run"
    :args        {}
    :category    :context}
   {:name        :get_sibling
    :description "Get spec, handler, and tests for a sibling cell by name"
    :args        {:name :string}
    :category    :context}
   {:name        :get_callers
    :description "Get cells/functions that call the given cell (defaults to current)"
    :args        {:name :optional}
    :category    :graph}
   {:name        :get_callees
    :description "Get cells/functions called by the given cell (defaults to current)"
    :args        {:name :optional}
    :category    :graph}
   {:name        :graph_impact
    :description "Get all transitive callers affected by changing the target"
    :args        {:target :string}
    :category    :graph}
   {:name        :graph_path
    :description "Find the shortest call path from one cell to another"
    :args        {:from :string :to :string}
    :category    :graph}
   {:name        :list_ns
    :description "List all non-system namespaces loaded in the REPL"
    :args        {}
    :category    :inspect}
   {:name        :inspect_ns
    :description "List public vars in a loaded namespace"
    :args        {:ns :string}
    :category    :inspect}
   {:name        :eval
    :description "Evaluate a Clojure expression in the REPL and return the result"
    :args        {:code :string}
    :category    :edit}
   {:name        :define
    :description "Define a top-level var in the cell's namespace (defn, def, etc.)"
    :args        {:code :string}
    :category    :edit}
   {:name        :write_handler
    :description "Write/overwrite the cell handler body (fn [resources data] ...)"
    :args        {:content :string}
    :category    :edit}
   {:name        :write_test
    :description "Write/overwrite the test code for this cell"
    :args        {:content :string}
    :category    :edit}
   {:name        :patch_handler
    :description "Surgically replace a string in the current handler"
    :args        {:search :string :replace :string}
    :category    :edit}
   {:name        :patch_test
    :description "Surgically replace a string in the current test code"
    :args        {:search :string :replace :string}
    :category    :edit}
   {:name        :run_tests
    :description "Assemble source, evaluate, and run tests. Green -> REVIEW phase."
    :args        {}
    :category    :exec}
   {:name        :lint
    :description "Run clj-kondo on the current handler code"
    :args        {}
    :category    :exec}
   {:name        :check_schema
    :description "Validate the cell's handler against its declared schema"
    :args        {}
    :category    :validate}
   {:name        :done
    :description "Done with dispatch — transition to review (only valid if tests are green)"
    :args        {}
    :category    :control}
   {:name        :approve
    :description "Approve the current implementation (tests are green)"
    :args        {}
    :category    :control}
   {:name        :revise
    :description "Revise the implementation with a reason (goes back to DISPATCH)"
    :args        {:reason :string}
    :category    :control}
   {:name        :give_up
    :description "Give up on this cell with a reason"
    :args        {:reason :string}
    :category    :control}])

(def tool-by-name
  "Indexed catalog: keyword tool name -> tool definition."
  (into {} (map (juxt :name identity)) catalog))

(defn render-tool-catalog
  "Renders the tool catalog as a prompt string, grouped by category."
  []
  (let [by-category (group-by :category catalog)
        cat-order   [:context :graph :inspect :edit :exec :validate :control]
        cat-labels  {:context "CONTEXT" :graph "GRAPH" :inspect "INSPECT"
                     :edit "EDIT/EXPLORE" :exec "EXECUTE" :validate "VALIDATE"
                     :control "CONTROL"}]
    (str/join "\n"
      (for [cat cat-order
            :let [tools (get by-category cat)]
            :when (seq tools)]
        (str (get cat-labels cat) ":\n"
             (str/join "\n"
               (for [t tools]
                 (let [args (if (empty? (:args t))
                              ""
                              (str " " (str/join " "
                                        (for [[k v] (:args t)]
                                          (if (= v :optional)
                                            (str "[" (name k) "]")
                                            (name k))))))]
                   (str "  " (name (:name t)) args
                        " — " (:description t))))))))))

(defn parse-tool-call
  "Extracts a tool call from an LLM response.
   Parses the FIRST ```tool-call fenced JSON block.
   Returns {:name keyword :args map} on success,
           {:parse-error string} on JSON/structure error,
           nil if no fence found."
  [response]
  (when-let [[_ json-str] (re-find tool-call-fence-re (or response ""))]
    (let [trimmed (str/trim json-str)]
      (try
        (let [parsed (json/read-str trimmed :key-fn keyword)
              name   (:name parsed)
              args   (or (:args parsed) {})]
          (cond
            (nil? name)
            {:parse-error (str "Tool call missing 'name' field. Received: " trimmed)}
            (not (keyword? name))
            {:parse-error (str "Tool 'name' must be a string, got: " (pr-str name))}
            (not (map? args))
            {:parse-error (str "Tool 'args' must be a map, got: " (pr-str args))}
            :else
            {:name (keyword (str/replace (name name) #"-" "_"))
             :args args}))
        (catch Exception e
          {:parse-error (str "Invalid JSON in tool-call: " (.getMessage e)
                             " — received: " trimmed)})))))

(defn tool-allowed-in-phase?
  "Checks if a tool name is valid for the current phase.
   Returns true if allowed, false otherwise."
  [tool-name phase]
  (case phase
    :dispatch (contains? dispatch-phase-tools tool-name)
    :review   (contains? review-phase-tools tool-name)
    false))

(defn validate-tool-call
  "Validates a parsed tool call against the catalog.
   Returns nil if valid, or an error string."
  [{:keys [name args]}]
  (let [tool (get tool-by-name name)]
    (cond
      (nil? tool)
      (str "Unknown tool: " (name name)
           ". Available tools: " (str/join ", " (map name (keys tool-by-name))))
      (and (not (map? args)))
      (str "Args must be a map, got: " (pr-str args))
      :else
      nil)))
