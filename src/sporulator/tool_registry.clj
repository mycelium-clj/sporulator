(ns sporulator.tool-registry
  "Tool catalog for the agent loop. Tools are declared as OpenAI tool_use
   JSONSchema function definitions so the model can call them via the
   native tool-use protocol."
  (:require [clojure.string :as str]))

(defn- tool-fn
  [name description properties required]
  {:type "function"
   :function {:name        (clojure.core/name name)
              :description description
              :parameters  {:type       "object"
                            :properties (or properties {})
                            :required   (mapv clojure.core/name (or required []))}}})

(def tool-schemas
  "Maps tool keyword to its OpenAI tool_use function definition."
  {:get_spec
   (tool-fn :get_spec
     "Get the current cell's specification: doc, schema, required resources.
      The spec is the immutable contract this cell must satisfy."
     {} [])

   :get_task
   (tool-fn :get_task
     "Get the overall orchestration task description."
     {} [])

   :list_functions
   (tool-fn :list_functions
     "List all helper functions and the handler defined in this cell."
     {} [])

   :get_callers
   (tool-fn :get_callers
     "Get functions within this cell that call the given function (defaults to handler)."
     {:name {:type "string"
             :description "Function name; omit to default to the handler."}}
     [])

   :get_callees
   (tool-fn :get_callees
     "Get functions within this cell called by the given function (defaults to handler)."
     {:name {:type "string"
             :description "Function name; omit to default to the handler."}}
     [])

   :graph_impact
   (tool-fn :graph_impact
     "Get all functions in this cell transitively affected by changing the target."
     {:target {:type "string" :description "Function name to analyse."}}
     [:target])

   :graph_path
   (tool-fn :graph_path
     "Find the shortest call path between two functions in this cell."
     {:from {:type "string" :description "Caller name."}
      :to   {:type "string" :description "Callee name."}}
     [:from :to])

   :list_ns
   (tool-fn :list_ns
     "List all non-system namespaces loaded in the REPL."
     {} [])

   :inspect_ns
   (tool-fn :inspect_ns
     "List public vars in a loaded namespace."
     {:ns {:type "string" :description "Namespace name."}}
     [:ns])

   :eval
   (tool-fn :eval
     "Evaluate a Clojure expression in the REPL and return the result."
     {:code {:type "string" :description "A single Clojure form to evaluate."}}
     [:code])

   :read_file
   (tool-fn :read_file
     "Read the contents of one of the cell's source files. Returns the file
      with line numbers prefixed (1\\tline). Available files: handler.clj,
      helpers.clj, test.clj."
     {:path {:type "string"
             :enum ["handler.clj" "helpers.clj" "test.clj"]
             :description "Which file to read."}}
     [:path])

   :write_file
   (tool-fn :write_file
     "Overwrite a source file with the provided content. handler.clj must
      contain a single (fn [resources data] ...) form. helpers.clj contains
      top-level defn/def forms. test.clj contains deftest forms."
     {:path    {:type "string"
                :enum ["handler.clj" "helpers.clj" "test.clj"]
                :description "Which file to write."}
      :content {:type "string"
                :description "The new file contents (replaces everything)."}}
     [:path :content])

   :edit_file
   (tool-fn :edit_file
     "Edit a source file by replacing an exact substring. old_string must
      appear exactly once in the file unless replace_all is true."
     {:path        {:type "string"
                    :enum ["handler.clj" "helpers.clj" "test.clj"]
                    :description "Which file to edit."}
      :old_string  {:type "string"
                    :description "Exact substring to find. Must be unique."}
      :new_string  {:type "string"
                    :description "Replacement text."}
      :replace_all {:type "boolean"
                    :description "Replace every occurrence (default false)."}}
     [:path :old_string :new_string])

   :list_files
   (tool-fn :list_files
     "List the cell's source files with their sizes."
     {} [])

   :run_tests
   (tool-fn :run_tests
     "Assemble the cell from helpers.clj + handler.clj, evaluate, and run the
      tests in test.clj. Returns pass/fail with output. Tests passing here is
      a prerequisite for complete."
     {} [])

   :lint
   (tool-fn :lint
     "Run clj-kondo on handler.clj."
     {} [])

   :complete
   (tool-fn :complete
     "Signal that the implementation is finished. The harness re-runs the
      tests as a final check; if they pass, the cell is finalized. If they
      fail, the failure is reported and you stay in the working loop."
     {} [])

   :give_up
   (tool-fn :give_up
     "Give up on this cell with a reason. Use this when the task is
      genuinely impossible (bad contract, missing dependency, etc.)."
     {:reason {:type "string" :description "Why we are stopping."}}
     [:reason])})

(def all-tools
  "Set of every tool name the agent can call."
  (set (keys tool-schemas)))

(defn working-tools
  "Returns the JSONSchema vector to advertise to the LLM. The agent has a
   single working mode — all tools are always available."
  []
  (vec (vals tool-schemas)))

(defn known-tool?
  "True if `tool-name` is a tool this registry knows about."
  [tool-name]
  (contains? all-tools tool-name))

(defn normalize-tool-name
  "Canonicalises a tool name reported by the LLM (string or keyword) into a
   keyword with hyphens normalised to underscores so it matches our schema keys."
  [n]
  (let [s (cond
            (keyword? n) (name n)
            (string?  n) n
            :else        (str n))]
    (keyword (str/replace s #"-" "_"))))
