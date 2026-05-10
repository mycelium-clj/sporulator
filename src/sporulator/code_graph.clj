(ns sporulator.code-graph
  "Code graph analysis for Mycelium workflows.
   Builds a graph of facts (calls, defines, imports, etc.) from
   loaded cells and manifests, then provides relational queries
   for callers, callees, reachability, impact, dead code, cycles,
   and data flow.

   Uses materialized transitive closure for performance rather than
   core.logic's relational search. The graph is small (workflow-level,
   dozens of nodes), so this approach is simpler and avoids tabled
   execution concerns."
  (:require [clojure.set :as set]))

;; ── Fact set operations ─────────────────────────────────────────

(defn facts-of-type
  "Filters facts by the given type (first element of each vector)."
  [facts kind]
  (into #{} (filter #(= kind (first %))) facts))

(defn callers
  "Returns the set of functions that directly call the target."
  [facts target]
  (into #{}
        (comp (filter #(= (last %) target))
              (map second))
        (facts-of-type facts :calls)))

(defn callees
  "Returns the set of functions directly called by the source."
  [facts source]
  (into #{}
        (comp (filter #(= (second %) source))
              (map #(nth % 2)))
        (facts-of-type facts :calls)))

;; ── Transitive closure ───────────────────────────────────────────

(defn- direct-calls-map
  "Builds adjacency map: {caller #{callee ...}} from :calls facts."
  [facts]
  (reduce (fn [m [_ caller callee :as fct]]
            (update m caller (fnil conj #{}) callee))
          {}
          (facts-of-type facts :calls)))

(defn- transitive-callers-map
  "Builds reverse adjacency map with full transitive closure.
   Returns {callee #{all-callers ...}} including indirect callers."
  [facts]
  (let [rev-map (reduce (fn [m [_ caller callee]]
                          (update m callee (fnil conj #{}) caller))
                        {}
                        (facts-of-type facts :calls))
        ;; Compute transitive closure: BFS from each node backwards
        all-nodes (into #{} (keys rev-map))]
    (into {} (map (fn [node]
                    (loop [queue (vec (get rev-map node #{}))
                           visited (get rev-map node #{})]
                      (if-let [current (first queue)]
                        (let [parents (get rev-map current #{})
                              new-parents (set/difference parents visited)]
                          (recur (into (vec (rest queue)) new-parents)
                                 (into visited new-parents)))
                        [node visited])))
                  all-nodes))))

(defn- transitive-callees-map
  "Builds forward adjacency map with full transitive closure."
  [facts]
  (let [fwd-map (direct-calls-map facts)
        all-nodes (into #{} (keys fwd-map))]
    (into {} (map (fn [node]
                    (loop [queue (vec (get fwd-map node #{}))
                           visited (get fwd-map node #{})]
                      (if-let [current (first queue)]
                        (let [children (get fwd-map current #{})
                              new-children (set/difference children visited)]
                          (recur (into (vec (rest queue)) new-children)
                                 (into visited new-children)))
                        [node visited])))
                  all-nodes))))

(def reachable?
  "Returns true if there is a call path from source to target."
  (memoize
   (fn [facts source target]
     (if (some #{[:calls source target]} facts)
       true
       (let [tc (transitive-callees-map facts)]
         (contains? (get tc source #{}) target))))))

(defn impact
  "Returns the set of all functions (transitively) that call target."
  [facts target]
  (let [tc (transitive-callers-map facts)]
    (get tc target #{})))

;; ── Graph queries ────────────────────────────────────────────────

(defn defined-names
  "Returns all defined function names from :defines facts."
  [facts]
  (into #{}
        (map second)
        (facts-of-type facts :defines)))

(defn dead-code
  "Returns the set of defined function names that have no callers
   and are not callers of anything."
  [facts]
  (let [defined (defined-names facts)
        all-callees (into #{} (map #(nth % 2)) (facts-of-type facts :calls))
        all-callers (into #{} (map second) (facts-of-type facts :calls))]
    (set/difference defined all-callees all-callers)))

(defn cycles
  "Returns the set of cycles (each cycle is a set of function names).
   Uses transitive closure: a node is in a cycle iff it can reach itself.
   Groups mutually-reachable nodes into the same cycle set."
  [facts]
  (let [tc (transitive-callees-map facts)
        in-cycle (into (hash-set)
                       (filter (fn [node]
                                 (contains? (get tc node (hash-set)) node)))
                       (defined-names facts))
        ;; Group mutually-reachable nodes into cycles
        ;; Build equivalence classes: a ~ b if a can reach b and b can reach a
        groups (loop [remaining in-cycle
                      groups (hash-set)]
                 (if-let [node (first remaining)]
                   (let [reachable-from (get tc node (hash-set))
                         same-cycle (into (hash-set)
                                          (filter (fn [other]
                                                    (and (contains? reachable-from other)
                                                         (contains? (get tc other (hash-set)) node)))
                                                  remaining))
                         ;; Include node itself
                         component (if (empty? same-cycle)
                                     (hash-set node)
                                     (conj same-cycle node))
                         remaining (apply disj remaining component)]
                     (recur remaining (conj groups component)))
                   groups))]
    groups))

(defn path
  "Returns the shortest call path from source to target, or nil if none.
   Uses BFS for shortest-path guarantee."
  [facts source target]
  (let [fwd (direct-calls-map facts)]
    (loop [queue (list [source])
           visited #{source}]
      (when-let [current-path (first queue)]
        (let [current (last current-path)]
          (if (= current target)
            (vec current-path)
            (let [neighbors (get fwd current #{})
                  new-paths (for [n neighbors
                                  :when (not (contains? visited n))]
                              (conj current-path n))
                  new-visited (into visited neighbors)]
              (recur (into (vec (rest queue)) new-paths)
                     new-visited))))))))

;; ── Data flow ────────────────────────────────────────────────────

(defn produces-keys
  "Returns the set of data keys produced by a function/cell."
  [facts name]
  (into #{}
        (comp (filter #(= (second %) name))
              (map #(nth % 2)))
        (facts-of-type facts :produces)))

(defn consumes-keys
  "Returns the set of data keys consumed by a function/cell."
  [facts name]
  (into #{}
        (comp (filter #(= (second %) name))
              (map #(nth % 2)))
        (facts-of-type facts :consumes)))

(defn resource-requires
  "Returns the set of resources required by a function/cell."
  [facts name]
  (into #{}
        (comp (filter #(= (second %) name))
              (map #(nth % 2)))
        (facts-of-type facts :requires)))

;; ── Graph mutation ───────────────────────────────────────────────

(defn add-edge!
  "Adds a fact vector to the graph atom. Returns the atom."
  [graph-atom & edge]
  (swap! graph-atom conj (vec edge))
  graph-atom)

(defn add-def!
  "Adds a :defines fact for a function."
  [graph-atom name file kind line]
  (swap! graph-atom conj [:defines name file kind line])
  graph-atom)

;; ── Fact extraction from manifests/cells ─────────────────────────

(defn build-cell-graph
  "Builds a graph facts set from a Mycelium manifest map.
   Extracts calls, data flow, and resource dependencies from
   the manifest structure.

   manifest: parsed manifest map with :cells, :edges, :pipeline"
  [manifest]
  (let [facts (transient #{})]
    (when-let [cells (:cells manifest)]
      (doseq [[cell-name cell-def] cells]
        (let [id (or (:id cell-def) cell-name)
              schema (:schema cell-def)
              requires (:requires cell-def)]
          ;; Cell definition
          (conj! facts [:defines id "" :cell 0])
          ;; Resource requirements
          (doseq [r requires]
            (conj! facts [:requires id r]))
          ;; Schema data flow
          (when-let [input (:input schema)]
            (doseq [[k _] input]
              (conj! facts [:consumes id k])))
          (when-let [output (:output schema)]
            (doseq [[k _] output]
              (conj! facts [:produces id k]))))))
    ;; Edge-based calls
    (when-let [edges (:edges manifest)]
      (doseq [[from to] edges]
        (let [to-cells (if (map? to) (vals to) [to])]
          (doseq [t to-cells
                  :when (not= t :end)]
            (conj! facts [:calls from t])))))
    ;; Pipeline calls
    (when-let [pipeline (:pipeline manifest)]
      (doseq [[a b] (partition 2 1 pipeline)]
        (conj! facts [:calls a b])))
    (persistent! facts)))

(defn build-repl-graph
  "Builds a graph facts set from loaded REPL state.
   Takes a map of namespace-name -> public-vars.
   Extracts calls between defn forms."
  [loaded-ns]
  (let [facts (transient #{})]
    (doseq [[ns-name vars] loaded-ns]
      (doseq [[var-name var-meta] vars]
        (conj! facts [:defines var-name ns-name :function 0])))
    (persistent! facts)))

;; ── Per-cell dynamic graph ──────────────────────────────────────

(defn- symbols-in-call-position
  "Walks a Clojure form and returns a set of all symbols found in
   the first position of any list (i.e., potential function calls)."
  [form]
  (loop [stack (list form)
         calls #{}]
    (if-let [f (first stack)]
      (cond
        (seq? f)
        (let [head (first f)]
          (recur (into (rest stack) (rest f))
                 (if (symbol? head) (conj calls head) calls)))
        (coll? f)
        (recur (into (rest stack) (seq f)) calls)
        :else
        (recur (rest stack) calls))
      calls)))

(defn extract-facts-from-code
  "Extracts :defines and :calls facts from a handler fn body and
   helper defn forms.  The handler is a Clojure form
   (fn [resources data] body...).  Helpers is a seq of
   (defn name [args] body...) forms.

   Returns a set of fact vectors: [:defines :handler ...], [:calls A B], etc."
  [handler helpers]
  (let [facts (transient #{})
        ;; Build defined-names: all helper names + :handler
        helper-names (into (hash-set)
                           (keep (fn [form]
                                   (when (and (seq? form) (= 'defn (first form)))
                                     (second form))))
                           (or helpers []))
        defined-names (conj helper-names :handler)
        ;; Add :defines for each known helper
        _ (doseq [name helper-names]
            (conj! facts [:defines name "" :function 0]))
        ;; Add :defines for the handler
        _ (conj! facts [:defines :handler "" :function 0])
        ;; Extract calls from the handler body (past [resources data])
        handler-calls (set/intersection
                        (symbols-in-call-position (nth handler 2 nil))
                        defined-names)
        _ (doseq [callee handler-calls
                  :when (not= callee :handler)]
            (conj! facts [:calls :handler callee]))
        ;; Extract calls from each helper body (past [args])
        _ (doseq [h (or helpers [])
                  :when (and (seq? h) (= 'defn (first h)))
                  :let [name (second h)
                        body (nth h 2 nil)]
                  :when name]
            (doseq [callee (set/intersection
                             (symbols-in-call-position body)
                             defined-names)
                    :when (and callee (not= callee name))]
              (conj! facts [:calls name callee])))]
    (persistent! facts)))

(defn rebuild-cell-graph!
  "Rebuilds the cell graph atom from the current handler and helpers.
   Replaces all prior facts with freshly extracted ones."
  [graph-atom handler helpers]
  (reset! graph-atom (extract-facts-from-code handler helpers)))
