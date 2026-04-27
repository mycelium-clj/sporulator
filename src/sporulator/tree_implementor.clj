(ns sporulator.tree-implementor
  "Drives implementation of a cell using a decomposer-produced
   function tree. Two strategies (both share the same input tree
   from `sporulator.decomposer`):

   - skeleton-mode  — pre-write helpers.clj with stub defns (one per
                      tree leaf, body throws UnsupportedOperationException).
                      Run the flat agent_loop once with these stubs in
                      scope. Agent fills them in to make the cell-level
                      tests pass. The skeleton acts as a planning
                      scaffold so the agent doesn't have to discover
                      the decomposition itself.

   - bottom-up-mode — implement each leaf independently in parallel
                      against per-leaf deftests synthesised from the
                      node's :examples. Lift implementations into a
                      shared helpers.clj. Run the agent_loop for the
                      handler with all helpers in scope. (TODO: see
                      `implement-leaf` stub at the bottom of this ns.)

   The skeleton-mode is the cheaper prototype to validate the
   decomposer-as-scaffold idea. Bottom-up parallel comes next once
   skeleton-mode shows whether the decomposition is useful at all."
  (:require [clojure.string :as str]
            [sporulator.agent-loop :as agent-loop]
            [sporulator.decomposer :as decomposer]
            [sporulator.leaf-implementor :as leaf]))

(defn- node->stub-defn
  "Renders one tree node as a stub `(defn name [params] (throw …))`
   form. The stub's exception message echoes the doc + params so the
   agent's first read_file shows what each TODO is supposed to do."
  [{:keys [name doc params]}]
  (let [msg (str name " not yet implemented — "
                 (str/replace (or doc "") "\"" "\\\""))]
    (str "(defn " name " [" (str/join " " params) "]\n"
         "  ;; TODO: " doc "\n"
         "  (throw (java.lang.UnsupportedOperationException.\n"
         "           \"" msg "\")))")))

(defn skeleton-helpers-source
  "Builds a `helpers.clj` source string with one stub `defn` per
   non-handler node in the tree. The handler lives in handler.clj
   (not here) so we exclude it.

   Topological order: leaves first, dependencies-of-internal-nodes
   before them, so a dep is always defined above its caller."
  [tree]
  (let [ordered (decomposer/ordered-nodes tree)
        helpers (remove #(= "handler" (:name %)) ordered)]
    (str/join "\n\n" (map node->stub-defn helpers))))

(defn handler-skeleton-source
  "Builds the initial handler.clj source: a `(fn [resources data] …)`
   that wires the tree's helpers together based on :depends-on edges.
   We don't try to be clever about argument plumbing — the agent will
   refine. The skeleton just makes the helpers visibly used so the
   agent sees the call shape."
  [tree]
  (let [handler (some #(when (= "handler" (:name %)) %) tree)
        params  (or (:params handler) ["resources" "data"])
        deps    (vec (:depends-on handler))]
    (str "(fn [" (str/join " " params) "]\n"
         "  ;; TODO: " (or (:doc handler) "implement the cell") "\n"
         (when (seq deps)
           (str "  ;; helpers available: " (str/join ", " deps) "\n"))
         "  (throw (java.lang.UnsupportedOperationException.\n"
         "           \"handler not yet implemented\")))")))

(defn build-skeleton
  "Given a decomposer tree, returns
     {:initial-handler <handler.clj source>
      :initial-helpers <helpers.clj source>}
   suitable for `agent-loop/run!`'s edit-mode entry points."
  [tree]
  {:initial-handler (handler-skeleton-source tree)
   :initial-helpers (skeleton-helpers-source tree)})

(defn run-skeleton-mode!
  "Runs the agent loop with a decomposer-produced skeleton in scope.
   The agent fills in the TODO stubs to make the cell tests pass.

   Same opts shape as `agent-loop/run!`. We override `:initial-handler`
   and `:initial-helpers` from the tree, and prepend a `:change-summary`
   that explains the scaffold so the agent treats the stubs as work
   items rather than working code.

   Returns whatever `agent-loop/run!` returns."
  [tree opts]
  (let [{:keys [initial-handler initial-helpers]} (build-skeleton tree)
        names (mapv :name tree)
        change-summary
        (str "## Decomposer scaffold loaded\n"
             "The implementor planned this cell as a tree of " (count tree)
             " functions: " (str/join ", " names) ".\n"
             "helpers.clj has been pre-populated with stub `(defn …)` forms\n"
             "for every non-handler node. handler.clj has a stub root.\n"
             "Each stub throws UnsupportedOperationException with a TODO\n"
             "comment summarising what that function should do.\n\n"
             "Your job is to replace each `throw` with a real body so the\n"
             "tests pass. You can keep the decomposition (one fn per stub)\n"
             "or merge / split / reshape it however you like — the\n"
             "decomposer is a planning hint, not a constraint."
             (when-let [extra (:change-summary opts)]
               (str "\n\nAdditional context:\n" extra)))]
    (agent-loop/run!
      (merge opts
        {:initial-handler initial-handler
         :initial-helpers initial-helpers
         :change-summary  change-summary}))))

;; -----------------------------------------------------------------
;; Bottom-up + parallel-leaves mode
;; -----------------------------------------------------------------

(defn- batch-by-deps
  "Splits `tree` into successive batches where each batch's nodes
   have all their :depends-on satisfied by names from earlier
   batches. Within a batch, nodes are independent and can be
   implemented in parallel.

   `tree` is the topo-ordered decomposer output (leaves first,
   root last)."
  [tree]
  (loop [remaining tree
         done #{}
         batches []]
    (if (empty? remaining)
      batches
      (let [{ready true blocked false}
            (group-by (fn [{:keys [depends-on]}] (every? done depends-on))
                      remaining)
            ready (vec (or ready []))
            new-done (into done (map :name) ready)
            still (vec (or blocked []))]
        (when (empty? ready)
          (throw (ex-info "Cycle in decomposer tree — no nodes ready in this batch"
                          {:remaining (mapv :name remaining)})))
        (recur still new-done (conj batches ready))))))

(defn- implement-leaf-batch
  "Runs `leaf/implement-leaf` for every node in `batch` in parallel.
   Returns a vector of {:node ... :result ...} maps, in the same
   order as the batch."
  [client batch helpers-source on-event]
  (let [futures (mapv (fn [node]
                        (future
                          {:node   node
                           :result (try (leaf/implement-leaf client node
                                          {:helpers-source helpers-source
                                           :on-event       on-event})
                                        (catch Throwable t
                                          {:status :crashed
                                           :error  (.getMessage t)}))}))
                      batch)]
    (mapv deref futures)))

(defn- ensure-newline-padded
  "When concatenating helpers blobs, make sure each defn starts on
   its own line — avoids accidental run-together."
  [s]
  (cond
    (str/blank? s)        s
    (str/ends-with? s "\n\n") s
    (str/ends-with? s "\n")   (str s "\n")
    :else                 (str s "\n\n")))

(defn run-tree!
  "Full bottom-up implementation. For every non-handler batch in
   topo-order:
   - run leaf/implement-leaf for each node in parallel (the leaves
     have no in-batch deps);
   - lift each successful :defn-src into the accumulating
     helpers-source.

   When all helpers are implemented, hand control to
   `agent-loop/run!` for the handler with the accumulated helpers
   as :initial-helpers — the handler agent sees real, working
   helpers in scope, not stubs.

   Returns a map:
     {:status     :ok | :error
      :leaves     [{:name :status :defn-src ...} ...]   (per-leaf log)
      :helpers    \"...\"          (accumulated helpers source)
      :handler    <agent-loop/run! result map>}        (final cell run)

   `opts` is forwarded to `agent-loop/run!` for the handler step.
   Anything not relevant to the handler (just :on-event below) is
   ignored elsewhere."
  [client tree opts]
  (let [{:keys [on-event] :or {on-event (fn [_])}} opts
        ordered  (decomposer/ordered-nodes tree)
        batches  (batch-by-deps ordered)
        ;; Split off the batch that contains "handler" — that one
        ;; always runs last via the full agent_loop.
        handler-batch-idx
        (first (keep-indexed
                 (fn [i b] (when (some #(= "handler" (:name %)) b) i))
                 batches))
        leaf-batches (subvec (vec batches) 0 handler-batch-idx)]
    (loop [batches-left leaf-batches
           helpers-src  ""
           leaf-log     []]
      (if (empty? batches-left)
        ;; All leaves done — run the handler.
        (do (on-event {:phase  "tree_handler"
                       :status "started"
                       :leaves (count leaf-log)})
            (let [result (agent-loop/run!
                           (assoc opts
                             :initial-helpers helpers-src
                             :change-summary
                             (str "## Helpers pre-implemented\n"
                                  "The decomposer planned this cell as "
                                  (count tree) " functions. The "
                                  (count leaf-log) " non-handler leaves "
                                  "are already implemented and visible in "
                                  "helpers.clj — read them, then write "
                                  "handler.clj that composes them. You can "
                                  "still revise helpers.clj if a leaf needs "
                                  "fixing, but they passed isolation checks.")))]
              {:status   (if (= :ok (:status result)) :ok :error)
               :leaves   leaf-log
               :helpers  helpers-src
               :handler  result}))
        ;; Run the next leaf batch in parallel.
        (let [batch    (first batches-left)
              _        (on-event {:phase  "tree_batch"
                                  :status "started"
                                  :leaves (mapv :name batch)})
              results  (implement-leaf-batch client batch helpers-src on-event)
              new-defs (->> results
                            (filter #(= :ok (get-in % [:result :status])))
                            (map #(get-in % [:result :defn-src])))
              entries  (mapv (fn [{:keys [node result]}]
                               {:name     (:name node)
                                :status   (:status result)
                                :defn-src (:defn-src result)})
                             results)]
          (on-event {:phase  "tree_batch"
                     :status "done"
                     :ok-count (count new-defs)
                     :total    (count batch)})
          (recur (rest batches-left)
                 (str (ensure-newline-padded helpers-src)
                      (str/join "\n\n" new-defs))
                 (into leaf-log entries)))))))
