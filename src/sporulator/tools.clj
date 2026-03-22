(ns sporulator.tools
  "Generates UTCP tool definitions for registering sporulator endpoints
   with Claude Code's code-mode MCP server."
  (:require [clojure.data.json :as json]))

(defn tool-definitions
  "Returns the UTCP manual map for all sporulator tools.
   base-url is the sporulator server URL (default http://localhost:8420)."
  [& {:keys [base-url] :or {base-url "http://localhost:8420"}}]
  {"utcp_version"   "1.0.0"
   "manual_version" "1.0.0"
   "name"           "sporulator"
   "description"    "Clojure REPL and workflow tools via sporulator server"
   "tools"
   [;; Eval
    {"name"        "clj_eval"
     "description" "Evaluate Clojure code in the running JVM. Returns the result, stdout output, and any error. Use this to inspect state, run expressions, define functions, require namespaces, etc."
     "inputs"      {"type"       "object"
                    "properties" {"code" {"type"        "string"
                                          "description" "Clojure code to evaluate"}}
                    "required"   ["code"]}
     "tool_transport" {"transport_type" "http"
                       "url"            (str base-url "/api/repl/eval")
                       "http_method"    "POST"
                       "content_type"   "application/json"}}

    ;; REPL status
    {"name"        "clj_repl_status"
     "description" "Check if the Clojure REPL is connected and ready."
     "inputs"      {"type" "object" "properties" {}}
     "tool_transport" {"transport_type" "http"
                       "url"            (str base-url "/api/repl/status")
                       "http_method"    "GET"}}

    ;; List cells
    {"name"        "clj_list_cells"
     "description" "List all registered cells in the store with their latest version, schema, handler code, and documentation."
     "inputs"      {"type" "object" "properties" {}}
     "tool_transport" {"transport_type" "http"
                       "url"            (str base-url "/api/cells")
                       "http_method"    "GET"}}

    ;; Get cell
    {"name"        "clj_get_cell"
     "description" "Get a specific cell by ID. Returns the latest version with full handler code, schema, doc, and metadata."
     "inputs"      {"type"       "object"
                    "properties" {"id" {"type"        "string"
                                        "description" "Cell ID (e.g. ':order/compute-tax')"}}
                    "required"   ["id"]}
     "tool_transport" {"transport_type" "http"
                       "url"            (str base-url "/api/cell?id={id}")
                       "http_method"    "GET"}}

    ;; Save cell
    {"name"        "clj_save_cell"
     "description" "Save a cell to the store. Creates a new version. Provide the cell ID, handler code, schema, and documentation."
     "inputs"      {"type"       "object"
                    "properties" {"id"      {"type"        "string"
                                              "description" "Cell ID (e.g. ':order/compute-tax')"}
                                  "handler" {"type"        "string"
                                              "description" "Handler source code (defcell form)"}
                                  "schema"  {"type"        "string"
                                              "description" "Schema EDN string"}
                                  "doc"     {"type"        "string"
                                              "description" "Documentation string"}}
                    "required"   ["id" "handler"]}
     "tool_transport" {"transport_type" "http"
                       "url"            (str base-url "/api/cell")
                       "http_method"    "POST"
                       "content_type"   "application/json"}}

    ;; List manifests
    {"name"        "clj_list_manifests"
     "description" "List all workflow manifests in the store with their latest version and timestamps."
     "inputs"      {"type" "object" "properties" {}}
     "tool_transport" {"transport_type" "http"
                       "url"            (str base-url "/api/manifests")
                       "http_method"    "GET"}}

    ;; Get manifest
    {"name"        "clj_get_manifest"
     "description" "Get a specific workflow manifest by ID. Returns the full EDN body."
     "inputs"      {"type"       "object"
                    "properties" {"id" {"type"        "string"
                                        "description" "Manifest ID (e.g. ':order/placement')"}}
                    "required"   ["id"]}
     "tool_transport" {"transport_type" "http"
                       "url"            (str base-url "/api/manifest?id={id}")
                       "http_method"    "GET"}}

    ;; Save manifest
    {"name"        "clj_save_manifest"
     "description" "Save a workflow manifest to the store. Creates a new version."
     "inputs"      {"type"       "object"
                    "properties" {"id"   {"type"        "string"
                                          "description" "Manifest ID (e.g. ':order/placement')"}
                                  "body" {"type"        "string"
                                          "description" "Manifest EDN body"}}
                    "required"   ["id" "body"]}
     "tool_transport" {"transport_type" "http"
                       "url"            (str base-url "/api/manifest")
                       "http_method"    "POST"
                       "content_type"   "application/json"}}

    ;; Instantiate cell
    {"name"        "clj_instantiate"
     "description" "Load a cell from the store into the running JVM. The cell becomes available for workflow execution."
     "inputs"      {"type"       "object"
                    "properties" {"cell_id" {"type"        "string"
                                             "description" "Cell ID to instantiate"}}
                    "required"   ["cell_id"]}
     "tool_transport" {"transport_type" "http"
                       "url"            (str base-url "/api/repl/instantiate")
                       "http_method"    "POST"
                       "content_type"   "application/json"}}

    ;; Generate source
    {"name"        "clj_generate_source"
     "description" "Generate Clojure source files from stored cells and manifests. Writes .clj files to the specified output directory."
     "inputs"      {"type"       "object"
                    "properties" {"output_dir"      {"type"        "string"
                                                     "description" "Output directory for generated files"}
                                  "base_namespace"  {"type"        "string"
                                                     "description" "Base namespace prefix (e.g. 'myapp')"}}
                    "required"   ["output_dir" "base_namespace"]}
     "tool_transport" {"transport_type" "http"
                       "url"            (str base-url "/api/source/generate")
                       "http_method"    "POST"
                       "content_type"   "application/json"}}]})

(defn tools-json
  "Returns the UTCP manual as a JSON string for registration."
  [& opts]
  (json/write-str (apply tool-definitions opts)))
