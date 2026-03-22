(ns sporulator.tools
  "Generates UTCP tool definitions for registering sporulator endpoints
   with Claude Code's code-mode MCP server."
  (:require [clojure.data.json :as json]))

(defn- http-call-template [base-url method path & [content-type]]
  (cond-> {"call_template_type" "http"
           "http_method"        method
           "url"                (str base-url path)}
    content-type (assoc "content_type" content-type)))

(defn tool-definitions
  "Returns the UTCP manual map for all sporulator tools.
   base-url is the sporulator server URL (default http://localhost:8420)."
  [& {:keys [base-url] :or {base-url "http://localhost:8420"}}]
  {"utcp_version"   "1.0.0"
   "manual_version" "1.0.0"
   "tools"
   [{"name"        "clj_eval"
     "description" "Evaluate Clojure code in the running JVM. Returns the result, stdout output, and any error. Use this to inspect state, run expressions, define functions, require namespaces, etc."
     "inputs"      {"type"       "object"
                    "properties" {"code" {"type"        "string"
                                          "description" "Clojure code to evaluate"}}
                    "required"   ["code"]}
     "tool_call_template" (http-call-template base-url "POST" "/api/repl/eval" "application/json")}

    {"name"        "clj_repl_status"
     "description" "Check if the Clojure REPL is connected and ready."
     "inputs"      {"type" "object" "properties" {}}
     "tool_call_template" (http-call-template base-url "GET" "/api/repl/status")}

    {"name"        "clj_list_cells"
     "description" "List all registered cells in the store with their latest version, schema, handler code, and documentation."
     "inputs"      {"type" "object" "properties" {}}
     "tool_call_template" (http-call-template base-url "GET" "/api/cells")}

    {"name"        "clj_get_cell"
     "description" "Get a specific cell by ID. Returns the latest version with full handler code, schema, doc, and metadata."
     "inputs"      {"type"       "object"
                    "properties" {"id" {"type"        "string"
                                        "description" "Cell ID (e.g. ':order/compute-tax')"}}
                    "required"   ["id"]}
     "tool_call_template" (http-call-template base-url "GET" "/api/cell?id={id}")}

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
     "tool_call_template" (http-call-template base-url "POST" "/api/cell" "application/json")}

    {"name"        "clj_list_manifests"
     "description" "List all workflow manifests in the store with their latest version and timestamps."
     "inputs"      {"type" "object" "properties" {}}
     "tool_call_template" (http-call-template base-url "GET" "/api/manifests")}

    {"name"        "clj_get_manifest"
     "description" "Get a specific workflow manifest by ID. Returns the full EDN body."
     "inputs"      {"type"       "object"
                    "properties" {"id" {"type"        "string"
                                        "description" "Manifest ID (e.g. ':order/placement')"}}
                    "required"   ["id"]}
     "tool_call_template" (http-call-template base-url "GET" "/api/manifest?id={id}")}

    {"name"        "clj_save_manifest"
     "description" "Save a workflow manifest to the store. Creates a new version."
     "inputs"      {"type"       "object"
                    "properties" {"id"   {"type"        "string"
                                          "description" "Manifest ID (e.g. ':order/placement')"}
                                  "body" {"type"        "string"
                                          "description" "Manifest EDN body"}}
                    "required"   ["id" "body"]}
     "tool_call_template" (http-call-template base-url "POST" "/api/manifest" "application/json")}

    {"name"        "clj_instantiate"
     "description" "Load a cell from the store into the running JVM. The cell becomes available for workflow execution."
     "inputs"      {"type"       "object"
                    "properties" {"cell_id" {"type"        "string"
                                             "description" "Cell ID to instantiate"}}
                    "required"   ["cell_id"]}
     "tool_call_template" (http-call-template base-url "POST" "/api/repl/instantiate" "application/json")}

    {"name"        "clj_generate_source"
     "description" "Generate Clojure source files from stored cells and manifests. Writes .clj files to the specified output directory."
     "inputs"      {"type"       "object"
                    "properties" {"output_dir"      {"type"        "string"
                                                     "description" "Output directory for generated files"}
                                  "base_namespace"  {"type"        "string"
                                                     "description" "Base namespace prefix (e.g. 'myapp')"}}
                    "required"   ["output_dir" "base_namespace"]}
     "tool_call_template" (http-call-template base-url "POST" "/api/source/generate" "application/json")}]})

(defn tools-json
  "Returns the UTCP manual as a JSON string for registration."
  [& opts]
  (json/write-str (apply tool-definitions opts)))
