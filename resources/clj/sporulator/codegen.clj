(ns sporulator.codegen
  "Code generation helpers for the sporulator orchestrator.
   Loaded in the nREPL session at startup. Called by the Go bridge
   to do structured transformations that shouldn't be done with string manipulation."
  (:require [mycelium.cell :as cell]
            [mycelium.schema :as schema]
            [malli.core :as m]
            [clojure.string :as str]))

(defn assemble-cell-source
  "Generates a complete cell namespace source string.
   schema-map is {:input <schema> :output <schema>}.
   extra-requires is a vector of require specs like [clojure.string :as str].
   helpers is a string of helper function definitions.
   fn-body is the (fn [resources data] ...) string."
  [{:keys [cell-ns cell-id doc schema-map requires extra-requires helpers fn-body]}]
  (let [input-schema  (:input schema-map)
        output-schema (:output schema-map)]
    (str "(ns " cell-ns "\n"
         "  (:require [mycelium.cell :as cell]"
         (when (seq extra-requires)
           (str "\n            " (str/join "\n            " extra-requires)))
         "))\n"
         (when (seq helpers)
           (str "\n" helpers "\n"))
         "\n(cell/defcell " cell-id "\n"
         "  {:doc " (pr-str doc) "\n"
         "   :input " (pr-str input-schema) "\n"
         "   :output " (pr-str output-schema)
         (when (seq requires)
           (str "\n   :requires " (pr-str (mapv keyword requires))))
         "}\n"
         "  " fn-body ")\n")))

(defn assemble-stub-cell-source
  "Generates a minimal stub cell source for schema validation.
   Registers the cell with a passthrough handler."
  [{:keys [cell-ns cell-id doc schema-map]}]
  (let [input-schema  (:input schema-map)
        output-schema (:output schema-map)]
    (str "(ns " cell-ns "\n"
         "  (:require [mycelium.cell :as cell]))\n\n"
         "(cell/defcell " cell-id "\n"
         "  {:doc " (pr-str doc) "\n"
         "   :input " (pr-str input-schema) "\n"
         "   :output " (pr-str output-schema) "}\n"
         "  (fn [resources data] data))\n")))

(defn parse-schema
  "Parses a schema EDN string into a map with :input and :output keys.
   Normalizes lite map syntax to proper Malli."
  [schema-edn-str]
  (let [raw (read-string schema-edn-str)]
    {:input  (schema/normalize-schema (:input raw))
     :output (schema/normalize-schema (:output raw))}))

(defn validate-schema
  "Validates a schema (after normalization) is valid Malli.
   Returns nil on success, error message string on failure."
  [schema]
  (try
    (m/schema schema)
    nil
    (catch Exception e
      (str "INVALID: " (.getMessage e)))))
