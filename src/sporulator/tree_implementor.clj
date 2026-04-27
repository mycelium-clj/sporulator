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
            [sporulator.decomposer :as decomposer]))

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
;; Bottom-up + parallel-leaves mode (TODO — phase B)
;; -----------------------------------------------------------------

;; The bottom-up mode is the user's full vision: implement each leaf
;; in isolation against its own deftests (built from :examples), in
;; parallel batches keyed by the dependency graph, then lift into a
;; shared helpers.clj before running the handler. The hardest part is
;; per-leaf testing for impure / non-deterministic leaves whose
;; example I/O can't be turned into exact-equality assertions; we
;; either need the LLM to produce property-shaped tests or fall back
;; to verifying at the cell level after recomposition.
;;
;; Skeleton-mode (above) is the prototype that validates the
;; decomposer is useful as a scaffold. If it pays off we'll build out
;; this fuller path.
