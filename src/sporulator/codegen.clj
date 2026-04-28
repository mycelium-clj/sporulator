(ns sporulator.codegen
  "Code generation and assembly for sporulator.
   Takes parsed data structures (forms, schemas, keywords) and produces
   source strings. All serialization happens here at the output boundary."
  (:require [clojure.string :as str]))

;; =============================================================
;; Schema rendering helpers
;; =============================================================

(defn- lite-map->vector-schema
  "Converts a lite-syntax map schema {:k :type ...} to Malli vector form
   [:map [:k :type] ...]. Returns the schema unchanged if it isn't a
   lite-syntax map."
  [schema]
  (if (and (map? schema)
           (seq schema)
           (every? keyword? (keys schema)))
    (into [:map] (map (fn [[k v]] [k v])) schema)
    schema))

(defn- dispatched-output?
  "True if `output` is a dispatched-output schema map keyed by transition
   labels. Mirrors mycelium.cell/output-dispatched? but accepts either
   already-vector or lite-map sub-schemas — codegen needs to recognise
   both shapes so it can canonicalise sub-schemas to vector form before
   emission."
  [output]
  (and (map? output)
       (seq output)
       (every? (fn [v] (or (vector? v) (and (map? v) (every? keyword? (keys v)))))
               (vals output))))

(defn- render-output-schema
  "Renders a cell's :output schema for emission inside (cell/defcell ...).
   For dispatched outputs, each per-transition sub-schema is canonicalised
   to vector form so that `defcell`'s `output-dispatched?` heuristic
   (every? vector? (vals output)) trips at load time. Flat outputs and
   already-vector schemas pass through unchanged."
  [output]
  (cond
    (nil? output)             output
    (dispatched-output? output)
    (into {} (map (fn [[label sub]] [label (lite-map->vector-schema sub)])) output)
    :else                     output))

;; =============================================================
;; Cell source assembly
;; =============================================================

(defn assemble-cell-source
  "Generates a complete cell namespace source string from data.

   Parameters (all in a single map):
   - cell-ns:        string, namespace name
   - cell-id:        keyword, cell ID (e.g. :order/validate)
   - doc:            string, documentation
   - schema:         map with :input and :output (Malli/lite schema data)
   - requires:       vector of resource keywords (e.g. [:db])
   - extra-requires: vector of parsed require spec vectors
   - helpers:        vector of helper forms (defn, def, etc.)
   - fn-body:        the handler form (fn [resources data] ...)"
  [{:keys [cell-ns cell-id doc schema requires extra-requires helpers fn-body]}]
  (str "(ns " cell-ns "\n"
       "  (:require [mycelium.cell :as cell]"
       (when (seq extra-requires)
         (str "\n            "
              (str/join "\n            " (map pr-str extra-requires))))
       "))\n"
       (when (seq helpers)
         (str "\n" (str/join "\n\n" (map pr-str helpers)) "\n"))
       "\n(cell/defcell " (pr-str cell-id) "\n"
       "  {:doc " (pr-str doc) "\n"
       "   :input " (pr-str (:input schema)) "\n"
       "   :output " (pr-str (render-output-schema (:output schema)))
       (when (seq requires)
         (str "\n   :requires " (pr-str (vec requires))))
       "}\n"
       "  " (pr-str fn-body) ")\n"))

(defn assemble-stub-cell-source
  "Generates a minimal stub cell source for schema validation.
   Registers the cell with a passthrough handler."
  [{:keys [cell-ns cell-id doc schema]}]
  (str "(ns " cell-ns "\n"
       "  (:require [mycelium.cell :as cell]))\n\n"
       "(cell/defcell " (pr-str cell-id) "\n"
       "  {:doc " (pr-str doc) "\n"
       "   :input " (pr-str (:input schema)) "\n"
       "   :output " (pr-str (render-output-schema (:output schema))) "}\n"
       "  (fn [resources data] data))\n"))

;; =============================================================
;; Test source assembly
;; =============================================================

(defn assemble-test-source
  "Generates a complete test namespace source string.
   test-body is a string of deftest forms (from LLM output)."
  [{:keys [test-ns cell-ns cell-id test-body]}]
  (str "(ns " test-ns "\n"
       "  (:require [clojure.test :refer [deftest is testing]]\n"
       "            [mycelium.cell :as cell]\n"
       "            [malli.core :as m]\n"
       "            [next.jdbc :as jdbc]\n"
       "            [next.jdbc.sql :as jdbc-sql]\n"
       "            [next.jdbc.result-set :as rs]\n"
       "            [" cell-ns "]))\n\n"
       "(def cell-spec (cell/get-cell! " (pr-str cell-id) "))\n"
       "(def handler (:handler cell-spec))\n"
       "(def cell-schema (:schema cell-spec))\n\n"
       "(defn approx= [x y tolerance]\n"
       "  (< (Math/abs (- (double x) (double y))) tolerance))\n\n"
       "(defn validate-output\n"
       "  \"Validates that result satisfies the cell's declared output schema.\"\n"
       "  [result]\n"
       "  (when-let [out-schema (:output cell-schema)]\n"
       "    (let [schema (if (vector? out-schema) out-schema [:map])]\n"
       "      (is (m/validate schema result)\n"
       "          (str \"Output schema violation: \"\n"
       "               (pr-str (m/explain schema result)))))))\n\n"
       test-body "\n"))

;; =============================================================
;; Manifest assembly
;; =============================================================

(defn assemble-manifest
  "Assembles an EDN manifest string from structured data.

   Parameters (in a single map):
   - id:          keyword, manifest ID
   - ns-prefix:   string, namespace prefix for cell IDs
   - steps:       vector of {:name :doc :input-schema :output-schema :requires}
   - edges:       map of step-name → edge EDN string
   - dispatches:  map of step-name → dispatch EDN string

   The first step is always mapped to :start."
  [{:keys [id ns-prefix steps edges dispatches]}]
  (let [step-key (fn [i step]
                   (if (zero? i) ":start" (str ":" (:name step))))]
    (str "{:id " (pr-str id) "\n"
         " :cells\n {"
         (str/join "\n  "
           (map-indexed
             (fn [i step]
               (str (step-key i step)
                    " {:id :" ns-prefix "/" (:name step) "\n"
                    "         :doc " (pr-str (:doc step)) "\n"
                    "         :schema {:input " (pr-str (:input-schema step))
                    " :output " (pr-str (:output-schema step)) "}"
                    (when (seq (:requires step))
                      (str "\n         :requires " (pr-str (mapv keyword (:requires step)))))
                    "}"))
             steps))
         "}\n"
         " :edges\n {"
         (str/join "\n  "
           (keep
             (fn [[i step]]
               (when-let [e (get edges (:name step))]
                 (str (step-key i step) " " e)))
             (map-indexed vector steps)))
         "}"
         (when (seq dispatches)
           (str "\n :dispatches\n {"
                (str/join "\n  "
                  (keep
                    (fn [[i step]]
                      (when-let [d (get dispatches (:name step))]
                        (str (step-key i step) " " d)))
                    (map-indexed vector steps)))
                "}"))
         "}")))
