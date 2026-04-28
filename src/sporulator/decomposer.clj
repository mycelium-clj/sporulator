(ns sporulator.decomposer
  "Recursive task decomposition for the implementor.

   Given a cell's brief + test contract, calls the LLM to either:
   (a) flag the cell as too big for a single namespace (signal back
       to the architect that this should be split into a subgraph), or
   (b) plan a function tree where each node is implementable in ≤20
       lines, with example I/O pairs that become deftests.

   The tree-implementor (see `sporulator.tree-implementor`) walks the
   tree bottom-up, running the agent loop on each leaf in parallel,
   accumulating helpers, and finishing at the cell handler with all
   helpers in scope. The result is one cell namespace built from
   small, independently-verified pieces."
  (:require [clojure.edn :as edn]
            [sporulator.extract :as extract]
            [sporulator.llm :as llm]))

(def ^:private decomposer-system-prompt
  "You are a Clojure implementation planner.

Given a brief and test contract for a single namespace's worth of
code, your job is two-fold:

## Step 1 — size check

If the implementation would obviously exceed ~200 lines of Clojure in
a single namespace (multiple independent concerns, substantial state
machines, multi-stage pipelines that should be their own cells),
respond with:

```edn
{:status :too-big
 :reason \"<one paragraph: what makes this too big and how to split>\"}
```

## Step 2 — function-tree decomposition (the usual case)

Otherwise plan a function tree. Each node is one Clojure function.
**Leaf functions must be implementable in 20 lines or less.** Internal
functions compose leaves; they should also stay small. The ROOT is
the cell handler — it must be named `\"handler\"` and take params
`[\"resources\" \"data\"]`.

For each function, provide:

  :name        — string, kebab-case identifier
  :doc         — one-line description of behaviour
  :params      — vector of arg-name strings
  :test-body   — a STRING containing one or more `(deftest …)` forms
                 that will be run against the function in isolation.
                 Use REAL resources, not placeholders:
                 - For pure functions, just `(is (= expected (fn args)))`.
                 - For functions that take a db datasource, set up an
                   in-memory or temp-file sqlite with `next.jdbc/get-datasource`
                   inside the test, seed any rows the test needs with
                   `next.jdbc/execute!`, then call the function.
                 - For time-dependent functions, pass a literal epoch
                   timestamp argument (the function takes `now` as a
                   parameter) — never depend on `(System/currentTimeMillis)`
                   at test time.
                 NEVER use placeholder symbols like `'some-db` or strings
                 like `\"DB\"` as test inputs. The harness eval-runs your
                 deftests literally.
  :depends-on  — vector of names (strings) of OTHER functions in this
                 tree that this function calls. Empty `[]` for leaves.

Respond with:

```edn
{:status :ok
 :tree [{:name \"valid-handle?\"
         :doc \"Returns true iff handle is non-empty and matches /[A-Za-z0-9_-]+/\"
         :params [\"s\"]
         :test-body \"(deftest test-valid-handle?\\n  (is (true? (valid-handle? \\\"alice\\\")))\\n  (is (false? (valid-handle? \\\"\\\")))\\n  (is (false? (valid-handle? \\\"bad!\\\"))))\"
         :depends-on []}
        {:name \"count-recent\"
         :doc \"Counts rows for handle in guestbook with created_at >= since.\"
         :params [\"db\" \"handle\" \"since\"]
         :test-body \"(deftest test-count-recent\\n  (let [tmp (str \\\"/tmp/leaf-\\\" (System/nanoTime) \\\".sqlite\\\")\\n        ds (next.jdbc/get-datasource {:dbtype \\\"sqlite\\\" :dbname tmp})]\\n    (next.jdbc/execute! ds [\\\"CREATE TABLE guestbook (handle TEXT, created_at INT)\\\"])\\n    (next.jdbc/execute! ds [\\\"INSERT INTO guestbook VALUES ('alice', 100), ('alice', 500), ('bob', 50)\\\"])\\n    (is (= 1 (count-recent ds \\\"alice\\\" 200)))\\n    (is (= 2 (count-recent ds \\\"alice\\\" 50)))\\n    (is (= 0 (count-recent ds \\\"bob\\\" 100)))))\"
         :depends-on []}
        {:name \"handler\"
         :doc \"Validates input handle, returns flat dispatched shape.\"
         :params [\"resources\" \"data\"]
         :test-body \"(deftest test-handler\\n  (let [tmp (str \\\"/tmp/handler-\\\" (System/nanoTime) \\\".sqlite\\\")\\n        ds (next.jdbc/get-datasource {:dbtype \\\"sqlite\\\" :dbname tmp})]\\n    (next.jdbc/execute! ds [\\\"CREATE TABLE guestbook (handle TEXT, created_at INT)\\\"])\\n    (is (= {:validated-handle \\\"alice\\\"}\\n           (handler {:db ds :now 1000} {:handle \\\"alice\\\"})))))\"
         :depends-on [\"valid-handle?\" \"count-recent\"]}]}
```

Rules for the tree:

- The tree must be a DAG (no cycles).
- Every name in `:depends-on` must be the `:name` of another node in
  `:tree`.
- The root node MUST be named `\"handler\"` and depends-on-transitively
  must reach every other node (no orphans).
- The harness eval-loads each leaf's :test-body. Setup that the test
  needs (sqlite tables, seed rows, sample data) MUST live INSIDE the
  deftest's `let`. Do not assume any vars exist outside the test.
- The harness has these requires available in every leaf's namespace:
  `[clojure.test :refer [deftest is testing]]`,
  `[next.jdbc :as jdbc]`, `[next.jdbc.result-set :as rs]`,
  `[clojure.string :as str]`. Use those without re-requiring.
- For functions that need a db, accept the datasource as a parameter
  (so the test can pass a temp sqlite ds). Do not use a global
  resource; that's the cell-handler's job.
- For time-dependent leaves (rate limits, expiry), accept `now` (an
  epoch second) as a parameter. The handler computes
  `(quot (System/currentTimeMillis) 1000)` and passes it down.

Wrap your reply in ```edn ... ``` fences. EDN, not JSON. No
explanation outside the fences.")

(defn- parse-decomposition
  "Parses the LLM response. Returns the decoded EDN map, or
   {:status :error :reason ...} on parse failure."
  [content]
  (let [block (or (extract/extract-first-code-block content) content)]
    (try
      (let [parsed (binding [*read-eval* false]
                     (edn/read-string block))]
        (if (and (map? parsed) (#{:ok :too-big} (:status parsed)))
          parsed
          {:status :error
           :reason "Decomposer response is missing :status :ok / :too-big."
           :raw    content}))
      (catch Exception e
        {:status :error
         :reason (str "Could not parse decomposer EDN: " (.getMessage e))
         :raw    content}))))

(defn- topo-sort
  "Returns the tree's nodes in dependency order (leaves first, root
   last). Throws if the dep graph has cycles or unknown deps."
  [tree]
  (let [by-name (into {} (map (juxt :name identity)) tree)
        names   (set (keys by-name))]
    (doseq [{:keys [name depends-on]} tree
            d depends-on]
      (when-not (contains? names d)
        (throw (ex-info (str "Unknown dependency '" d "' in node '" name "'")
                        {:name name :unknown-dep d}))))
    (loop [out      []
           remaining tree
           done     #{}]
      (if (empty? remaining)
        out
        (let [{ready true blocked false}
              (group-by (fn [{:keys [depends-on]}]
                          (every? done depends-on))
                        remaining)]
          (when (empty? ready)
            (throw (ex-info "Cycle in decomposer tree — no nodes have all deps satisfied"
                            {:remaining (mapv :name remaining)})))
          (recur (into out ready)
                 (vec (or blocked []))
                 (into done (map :name) ready)))))))

(defn validate-tree
  "Sanity checks the LLM's tree shape. Returns nil if OK, or a map
   {:error \"...\" :detail ...} when the tree is structurally broken."
  [tree]
  (cond
    (not (vector? tree))
    {:error "tree must be a vector"}

    (empty? tree)
    {:error "tree is empty"}

    (not (every? map? tree))
    {:error "every tree node must be a map"}

    (not (every? (fn [n] (and (string? (:name n))
                              (string? (:doc n))
                              (vector? (:params n))
                              (string? (:test-body n))
                              (vector? (:depends-on n))))
                 tree))
    {:error "every node must have :name (string) :doc (string) :params (vector) :test-body (string) :depends-on (vector)"}

    (not (some #(= "handler" (:name %)) tree))
    {:error "tree must contain a node named 'handler'"}

    :else
    (try (topo-sort tree) nil
         (catch Exception e {:error (.getMessage e)}))))

(defn ordered-nodes
  "Returns the tree's nodes in dependency order (leaves first). Used
   by the tree-implementor to drive parallel-leaf-then-recompose."
  [tree]
  (topo-sort tree))


(defn assess-and-decompose
  "Calls the decomposer LLM with the cell's brief + test contract.
   Returns:
     {:status :ok :tree [...] :ordered [...]} when the tree validates,
     {:status :too-big :reason \"...\"}          when the cell should split,
     {:status :error :reason \"...\" :raw \"\"}  when the response is broken."
  [client {:keys [doc schema requires test-body cell-id]
           :as   _brief}]
  (let [session (llm/create-session
                  (str "decomposer:" cell-id)
                  decomposer-system-prompt)
        prompt  (str "**Cell brief:**\n"
                     "Doc: " (or doc "(no doc)") "\n"
                     "Schema: " (or schema "{}") "\n"
                     (when (seq requires)
                       (str "Requires: " (vec requires) "\n"))
                     "\n**Test contract:**\n```clojure\n"
                     (or test-body "(no tests)")
                     "\n```\n\n"
                     "Plan the function tree.")
        resp    (llm/session-send session client prompt :temperature 0.2)
        parsed  (parse-decomposition (:content resp))]
    (case (:status parsed)
      :ok       (if-let [bad (validate-tree (:tree parsed))]
                  (assoc parsed :status :error :reason (:error bad))
                  (assoc parsed :ordered (ordered-nodes (:tree parsed))))
      :too-big  parsed
      :error    parsed
      ;; unknown
      {:status :error :reason (str "Unknown status: " (:status parsed)) :raw (:content resp)})))
