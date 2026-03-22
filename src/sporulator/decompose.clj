(ns sporulator.decompose
  "Workflow decomposition and graph context.
   Parses LLM decomposition responses into workflow trees,
   builds predecessor/successor context for cells.
   Uses Clojure reader for all EDN parsing."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [sporulator.extract :as extract]))

;; =============================================================
;; Decomposition node structure
;; =============================================================
;; A decomposition node is a map:
;; {:step-name    "validate-input"
;;  :cell-id      :order/validate-input
;;  :doc          "What this step does"
;;  :input-schema <data>  ;; parsed schema
;;  :output-schema <data>
;;  :requires     [:db :cache]
;;  :leaf?        true/false
;;  :children     [node ...]    ;; sub-steps if not leaf
;;  :manifest     "..."         ;; assembled EDN for non-leaf
;;  :walk-result  {:edges {...} :dispatches {...}}
;;  :depth        0}

;; =============================================================
;; Parse decomposition response (uses Clojure reader)
;; =============================================================

(defn parse-decomposition-response
  "Parses the LLM's decomposition response using Clojure EDN reader.
   Expects an EDN vector of maps in a code block.
   Returns a vector of decomposition node maps, or nil on parse failure."
  [response ns-prefix]
  (let [block (or (extract/extract-first-code-block response) response)]
    (try
      (let [steps (edn/read-string block)]
        (when (and (vector? steps) (seq steps))
          (->> steps
               (filter #(not (str/blank? (str (:name %)))))
               (mapv (fn [step]
                       {:step-name    (:name step)
                        :cell-id      (keyword ns-prefix (:name step))
                        :doc          (:doc step)
                        :input-schema (:input-schema step)
                        :output-schema (:output-schema step)
                        :requires     (vec (map keyword (:requires step)))
                        :leaf?        (:simple? step true)}))
               not-empty)))
      (catch Exception _ nil))))

;; =============================================================
;; Tree operations (pure functions)
;; =============================================================

(defn collect-leaves
  "Returns all leaf nodes in the decomposition tree (depth-first)."
  [root]
  (if (nil? root)
    []
    (if (:leaf? root)
      [root]
      (vec (mapcat collect-leaves (:children root))))))

(defn collect-sub-workflows
  "Returns non-leaf nodes in post-order (deepest first).
   This ordering ensures children are registered before parents."
  [root]
  (if (nil? root)
    []
    (vec
      (mapcat (fn [child]
                (if (:leaf? child)
                  []
                  (concat (collect-sub-workflows child) [child])))
              (:children root)))))

;; =============================================================
;; Edge parsing (uses Clojure reader)
;; =============================================================

(defn parse-edge-targets
  "Extracts target step keywords from an EDN edge string.
   Edge strings are either `:next-step` or `{:outcome1 :target1 :outcome2 :target2}`.
   Returns a vector of keywords, or nil for empty/invalid input."
  [edge-str]
  (when (and edge-str (not (str/blank? edge-str)))
    (try
      (let [parsed (edn/read-string edge-str)]
        (cond
          (keyword? parsed) [parsed]
          (map? parsed)     (vec (vals parsed))
          :else nil))
      (catch Exception _ nil))))

;; =============================================================
;; Graph context
;; =============================================================

(defn build-graph-context
  "Extracts predecessor and successor info for a target step
   from its parent node's walk result.
   Returns {:predecessors [...] :successors [...]}."
  [parent target-step-name]
  (if (or (nil? parent) (nil? (:walk-result parent)))
    {:predecessors [] :successors []}
    (let [walk (:walk-result parent)
          step-by-name (into {} (map (juxt :step-name identity) (:children parent)))
          ;; Find predecessors: steps whose edges point to target
          predecessors
          (for [[step-name edge-str] (:edges walk)
                :when (not= step-name target-step-name)
                :let [targets (parse-edge-targets edge-str)]
                :when (some #{(keyword target-step-name)} targets)
                :let [node (step-by-name step-name)]
                :when node]
            {:cell-id (:cell-id node)
             :doc (:doc node)
             :schema (:output-schema node)})
          ;; Find successors: steps that target's edges point to
          successors
          (when-let [edge-str (get (:edges walk) target-step-name)]
            (for [target (parse-edge-targets edge-str)
                  :when (not= target :end)
                  :let [node (step-by-name (name target))]
                  :when node]
              {:cell-id (:cell-id node)
               :doc (:doc node)
               :schema (:input-schema node)}))]
      {:predecessors (vec predecessors)
       :successors (vec (or successors []))})))

;; =============================================================
;; Find parent
;; =============================================================

(defn find-parent
  "Finds the parent DecompositionNode of a leaf with the given step-name.
   Searches the tree recursively."
  [tree target-step-name]
  (when tree
    (or (when (some #(= target-step-name (:step-name %)) (:children tree))
          tree)
        (some #(when (and (not (:leaf? %)) (seq (:children %)))
                 (find-parent % target-step-name))
              (:children tree)))))

;; =============================================================
;; Format for LLM prompts
;; =============================================================

(defn format-graph-context
  "Renders graph context as a human-readable string for prompts.
   Returns empty string if no neighbors."
  [{:keys [predecessors successors]}]
  (if (and (empty? predecessors) (empty? successors))
    ""
    (str "## Workflow Position\n"
         (when (seq predecessors)
           (str "\n**Receives data from:**\n"
                (str/join ""
                  (for [p predecessors]
                    (str "- " (pr-str (:cell-id p)) " — " (:doc p) "\n"
                         (when (and (:schema p) (not (str/blank? (str (:schema p)))))
                           (str "  Output schema: " (:schema p) "\n")))))))
         (when (seq successors)
           (str "\n**Feeds data to:**\n"
                (str/join ""
                  (for [s successors]
                    (str "- " (pr-str (:cell-id s)) " — " (:doc s) "\n"
                         (when (and (:schema s) (not (str/blank? (str (:schema s)))))
                           (str "  Input schema: " (:schema s) "\n"))))))))))

;; =============================================================
;; Serialization (JSON for persistence)
;; =============================================================

(defn serialize-tree
  "Marshals a decomposition tree to JSON string."
  [root]
  (json/write-str root))

(defn- restore-keywords
  "Restores keyword types in a deserialized tree node."
  [node]
  (when node
    (cond-> node
      (:cell-id node)  (update :cell-id keyword)
      (:requires node) (update :requires #(mapv keyword %))
      (:children node) (update :children #(mapv restore-keywords %)))))

(defn deserialize-tree
  "Unmarshals a JSON string into a decomposition tree."
  [json-str]
  (-> (json/read-str json-str :key-fn keyword)
      restore-keywords))
