(ns sporulator.source-gen
  "Generates Clojure source files from cells and manifests in the store."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sporulator.store :as store]))

(defn- cell-prefix
  "Extracts the namespace prefix from a cell ID like ':order/validate' → 'order'."
  [cell-id]
  (let [id (if (str/starts-with? (str cell-id) ":")
             (subs (str cell-id) 1)
             (str cell-id))]
    (if-let [idx (str/index-of id "/")]
      (subs id 0 idx)
      "core")))

(defn- ns-to-path
  "Converts a namespace like 'myapp.cells.order' to 'src/myapp/cells/order.clj'."
  [ns-name]
  (str "src/" (str/replace (str/replace ns-name "." "/") "-" "_") ".clj"))

(defn- build-cell-namespace
  "Generates a Clojure namespace source string for a group of cells."
  [ns-name handlers]
  (str "(ns " ns-name "\n"
       "  (:require [mycelium.cell :as cell]))\n\n"
       (str/join "\n\n" handlers) "\n"))

(defn- build-manifest-namespace
  "Generates a Clojure namespace source string for a manifest."
  [ns-name base-ns manifest-body cell-ns-set]
  (str "(ns " ns-name "\n"
       "  (:require [mycelium.core :as myc]\n"
       (str/join "\n"
         (map #(str "            [" % "]") (sort cell-ns-set)))
       "))\n\n"
       "(def manifest\n  " manifest-body ")\n\n"
       "(defn compile-workflow []\n"
       "  (myc/pre-compile manifest))\n"))

(defn- write-file!
  "Writes content to a file, creating parent directories as needed."
  [base-dir rel-path content]
  (let [f (io/file base-dir rel-path)]
    (io/make-parents f)
    (spit f content)))

;; ── Single-file writers ─────────────────────────────────────────

(defn- cell-name-from-id
  "Extracts the cell name from a cell ID like ':order/compute-tax' → 'compute-tax'."
  [cell-id]
  (let [id (if (str/starts-with? (str cell-id) ":")
             (subs (str cell-id) 1)
             (str cell-id))]
    (if-let [idx (str/index-of id "/")]
      (subs id (inc idx))
      id)))

(defn write-cell!
  "Writes a single cell's source file to disk.
   Returns {:path relative-path}."
  [project-path base-namespace cell-id cell-source]
  (let [name    (cell-name-from-id cell-id)
        ns-name (str base-namespace ".cells." name)
        rel-path (ns-to-path ns-name)]
    (write-file! project-path rel-path cell-source)
    {:path rel-path :namespace ns-name}))

(defn delete-cell!
  "Deletes a cell's source file from disk if it exists. No-op otherwise.
   Returns {:path :deleted? bool}."
  [project-path base-namespace cell-id]
  (let [name     (cell-name-from-id cell-id)
        ns-name  (str base-namespace ".cells." name)
        rel-path (ns-to-path ns-name)
        f        (java.io.File. (str project-path "/" rel-path))]
    {:path rel-path
     :deleted? (when (.exists f) (.delete f))}))

(defn write-test!
  "Writes a single cell's test file to disk.
   Returns {:path relative-path :namespace ns-name}."
  [project-path base-namespace cell-id test-source]
  (let [name     (cell-name-from-id cell-id)
        ns-name  (str base-namespace ".cells." name "-test")
        rel-path (str "test/" (str/replace (str/replace ns-name "." "/") "-" "_") ".clj")]
    (write-file! project-path rel-path test-source)
    {:path rel-path :namespace ns-name}))

(defn write-manifest!
  "Writes a manifest as a namespace file and as raw EDN to resources/.
   Returns {:path relative-path}."
  [project-path base-namespace manifest-id manifest-body]
  (let [clean-id (-> (str manifest-id)
                     (str/replace ":" "")
                     (str/replace "/" "-"))
        ns-name  (str base-namespace ".workflows." clean-id)
        content  (build-manifest-namespace ns-name base-namespace manifest-body #{})
        rel-path (ns-to-path ns-name)]
    (write-file! project-path rel-path content)
    ;; Also write raw EDN to resources
    (write-file! project-path "resources/manifest.edn" manifest-body)
    {:path rel-path :namespace ns-name}))

;; ── Batch generation ───────────────────────────────────────────

(defn generate
  "Generates source files from cells and manifests in the store.
   Writes .clj files to output-dir.

   Options:
     :output-dir      — root directory for generated files
     :base-namespace  — Clojure namespace prefix (e.g. 'myapp')

   Returns {:files [{:path :namespace :cell-ids}...]}"
  [store {:keys [output-dir base-namespace]}]
  (let [cells     (store/list-latest-cells store)
        manifests (store/list-manifests store)]
    (if (and (empty? cells) (empty? manifests))
      {:files []}
      (let [;; Group cells by prefix
            groups (group-by #(cell-prefix (:id %)) cells)
            ;; Generate cell namespace files
            cell-files
            (mapv (fn [[prefix group-cells]]
                    (let [ns-name  (str base-namespace ".cells." prefix)
                          handlers (mapv (fn [cell]
                                          (let [full (store/get-latest-cell store (:id cell))]
                                            (:handler full)))
                                        group-cells)
                          content  (build-cell-namespace ns-name handlers)
                          rel-path (ns-to-path ns-name)]
                      (write-file! output-dir rel-path content)
                      {:path      rel-path
                       :namespace ns-name
                       :cell-ids  (mapv :id group-cells)}))
                  (sort-by key groups))
            ;; Track cell namespaces for manifest requires
            cell-ns-set (set (map :namespace cell-files))
            ;; Generate manifest namespace files
            manifest-files
            (mapv (fn [m]
                    (let [full      (store/get-latest-manifest store (:id m))
                          clean-id  (-> (:id m)
                                        (str/replace ":" "")
                                        (str/replace "/" "-"))
                          ns-name   (str base-namespace ".workflows." clean-id)
                          content   (build-manifest-namespace
                                      ns-name base-namespace
                                      (:body full) cell-ns-set)
                          rel-path  (ns-to-path ns-name)]
                      (write-file! output-dir rel-path content)
                      {:path      rel-path
                       :namespace ns-name}))
                  manifests)]
        {:files (into cell-files manifest-files)}))))
