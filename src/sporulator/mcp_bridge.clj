(ns sporulator.mcp-bridge
  "Context-exploration tools for the model.
   Pure data accessors that bridge local cell state to the tool
   catalog.  Each cell is isolated — it only sees its own spec,
   handlers, helpers, and call graph."
  (:require [clojure.string :as str]
            [sporulator.code-graph :as cg]))

(defn build-context
  "Builds the MCP context map from local cell data.
   No graph, siblings, or sibling-data — cells are isolated."
  [{:keys [cell-id cell-ns brief test-code schema-parsed task]}]
  {:cell-id       cell-id
   :cell-ns       cell-ns
   :brief         brief
   :test-code     test-code
   :test-ns-name  (when test-code (second (re-find #"\(ns\s+(\S+)" test-code)))
   :schema-parsed schema-parsed
   :task          task})

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

(defn list-functions
  "Lists all helper functions defined in this cell so far.
   Returns the handler name plus each defined helper with its arity."
  [ctx]
  (let [helpers (get ctx :helpers [])
        handler-name "<handler>"]
    (if (seq helpers)
      (str "Defined in this cell:\n"
           "  " handler-name " (handler)\n"
           (str/join "\n"
             (map (fn [h]
                    (when (and (seq? h) (= 'defn (first h)))
                      (str "  " (second h) " " (pr-str (nth h 2 "args")))))
                  helpers)))
      (str "No helper functions defined yet. The handler (" handler-name ") "
           "is always present once write_handler is called."))))

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
