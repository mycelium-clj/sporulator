(ns sporulator.mcp-bridge
  "Context-exploration tools for the model.
   Pure data accessors that bridge between the REPL state and the tool
   catalog without exposing raw filesystem operations.
   All functions take a context map and return formatted strings."
  (:require [clojure.string :as str]
            [sporulator.code-graph :as cg]))

(defn build-context
  "Builds the MCP context map from orchestration data."
  [{:keys [cell-id cell-ns brief test-code schema-parsed
           graph task siblings sibling-data]
    :or   {siblings     []
           sibling-data {}}}]
  {:cell-id       cell-id
   :cell-ns       cell-ns
   :brief         brief
   :test-code     test-code
   :test-ns-name  (when test-code (second (re-find #"\(ns\s+(\S+)" test-code)))
   :schema-parsed schema-parsed
   :graph         graph
   :task          task
   :siblings      siblings
   :sibling-data  sibling-data})

(defn- format-spec [brief]
  (str "Cell ID: " (:id brief) "\n"
       "Namespace: " (:cell-ns brief) "\n"
       "Doc: " (or (:doc brief) "(none)") "\n"
       "Schema: " (or (:schema brief) "{}") "\n"
       "Resources required: " (if (seq (:requires brief))
                                (str/join ", " (map name (:requires brief)))
                                "none")
       (when (seq (:resource-docs brief))
         (str "\nResource docs:\n"
              (str/join "\n"
                (for [[k v] (:resource-docs brief)]
                  (str "  " (name k) ": " v)))))
       (when (:context brief)
         (str "\nWorkflow context:\n" (:context brief)))))

(defn get-spec
  "Returns the current cell's spec: doc, schema, requires, resource docs."
  [ctx]
  (let [brief (:brief ctx)]
    (format-spec brief)))

(defn get-task
  "Returns the overall orchestration task description."
  [ctx]
  (or (:task ctx) "No task description available."))

(defn get-context
  "Returns workflow position context: predecessors, successors, graph context."
  [ctx]
  (let [brief    (:brief ctx)
        graph    (:graph ctx)
        cell-id  (:cell-id ctx)
        context  (:context brief)]
    (str (when context
           (str "Workflow context:\n" context "\n\n"))
         (when graph
           (let [callers (cg/callers graph cell-id)
                 callees (cg/callees graph cell-id)
                 prod-keys (cg/produces-keys graph cell-id)
                 cons-keys (cg/consumes-keys graph cell-id)
                 resources (cg/resource-requires graph cell-id)]
             (str "Graph context:\n"
                  "  Called by: " (if (seq callers) (str/join ", " callers) "none (entry point)") "\n"
                  "  Calls: " (if (seq callees) (str/join ", " callees) "none (leaf node)") "\n"
                  "  Consumes keys: " (if (seq cons-keys) (str/join ", " cons-keys) "none") "\n"
                  "  Produces keys: " (if (seq prod-keys) (str/join ", " prod-keys) "none") "\n"
                  "  Resources: " (if (seq resources) (str/join ", " resources) "none")))))))

(defn list-siblings
  "Lists other cells being implemented in this run."
  [ctx]
  (let [siblings (:siblings ctx)]
    (if (seq siblings)
      (str/join "\n"
        (map (fn [s]
               (str "- " (:cell-id s) ": " (or (:doc s) "(no doc)")))
             siblings))
      "No sibling cells in this run.")))

(defn get-sibling
  "Returns spec + handler + tests for a sibling cell by name.
   name can be a keyword, string, or symbol."
  [ctx name]
  (let [sibling-data (:sibling-data ctx)
        cell-id      (if (keyword? name)
                       name
                       (keyword (str name)))
        data         (get sibling-data cell-id)]
    (if-not data
      (str "No sibling found for: " cell-id
           ". Available: " (str/join ", " (keys sibling-data)))
      (str "Sibling: " cell-id "\n"
           "Spec:\n" (format-spec (:spec data)) "\n\n"
           "Handler:\n" (or (:handler data) "(not yet implemented)") "\n\n"
           "Tests:\n" (or (:tests data) "(not yet generated)")))))

(defn list-loaded-ns
  "Lists namespace names loaded in the REPL.
   Excludes Clojure core, Java interop, and sporulator internals."
  [ctx]
  (let [all-ns (clojure.core/all-ns)
        names  (->> all-ns
                    (map ns-name)
                    (remove #(or (str/starts-with? (str %) "clojure.")
                                 (str/starts-with? (str %) "java.")
                                 (str/starts-with? (str %) "sporulator.")
                                 (str/starts-with? (str %) "mycelium.")))
                    (sort-by str))]
    (if (seq names)
      (str "Loaded namespaces:\n"
           (str/join "\n" (map #(str "  " %) names))
           "\n\nTotal: " (count names) " namespaces")
      "No custom namespaces loaded.")))

(defn inspect-ns
  "Returns public vars declared in a loaded namespace.
   ns-name can be a string, symbol, or keyword."
  [ctx ns-name]
  (let [sym (if (symbol? ns-name)
              ns-name
              (symbol (str ns-name)))
        ns-obj (find-ns sym)]
    (if-not ns-obj
      (str "Namespace " sym " not found in REPL.")
      (let [vars (ns-publics ns-obj)]
        (if (empty? vars)
          (str "Namespace " sym " has no public vars.")
          (str "Namespace: " sym "\n"
               "Public vars:\n"
               (str/join "\n"
                 (for [[vname vobj] (sort-by (comp str key) vars)]
                   (let [m (meta vobj)]
                     (str "  " vname
                          (when (:arglists m)
                            (str " " (pr-str (:arglists m))))
                          (when (:doc m)
                            (str " — " (subs (:doc m)
                                             0 (min 80 (count (:doc m))))))))))))))))

(defn get-cell-handler
  "Returns the current handler source code for this cell."
  [ctx]
  (let [source (:current-handler ctx)]
    (if source
      (str "Current handler:\n```clojure\n" source "\n```")
      "No handler written yet. Use write_handler to create one.")))

(defn get-test-code
  "Returns the current test code for this cell."
  [ctx]
  (let [test-code (or (:current-tests ctx) (:test-code ctx))]
    (if test-code
      (str "Current test code:\n```clojure\n" test-code "\n```")
      "No test code available.")))

(defn get-green-handler
  "Returns the handler code from the last green test run."
  [ctx]
  (if-let [source (:green-handler ctx)]
    (str "Green handler (last passing run):\n```clojure\n" source "\n```")
    "No green handler yet. Tests haven't passed."))
