(ns sporulator.bootstrap
  "Seeds a freshly-opened sporulator store from a project's existing
   filesystem state. When a user adds sporulator to a project that
   already has cells on disk + a manifest, the orchestrator's diff
   machinery should treat those cells as the green baseline (carry-
   over on the next run) rather than as `:added`. Without this, the
   first orchestrate! pass would re-implement every cell from scratch.

   Reads:
   - `<project>/resources/manifest.edn`  → initial manifest version +
     green snapshot.
   - `<project>/src/<base-ns>/cells/*.clj` → each cell as version 1.

   Idempotent: cells/manifests already in the store are not duplicated."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [sporulator.extract :as extract]
            [sporulator.manifest-diff :as manifest-diff]
            [sporulator.store :as store]))

;; ── File walking ─────────────────────────────────────────────────

(defn- ns->path-segment
  "Converts `app` or `myapp.core` to the on-disk segment used inside
   `src/`: `app` → \"app\", `myapp.core` → \"myapp/core\"."
  [base-ns]
  (-> (str base-ns)
      (str/replace "." "/")
      (str/replace "-" "_")))

(defn- cell-files
  "Returns a seq of File objects for every `*.clj` cell source under
   `<project-path>/src/<base-ns>/cells/`."
  [project-path base-ns]
  (let [dir (java.io.File.
              (str project-path "/src/" (ns->path-segment base-ns) "/cells"))]
    (when (.isDirectory dir)
      (->> (.listFiles dir)
           (filter (fn [^java.io.File f]
                     (and (.isFile f)
                          (str/ends-with? (.getName f) ".clj"))))))))

;; ── Cell parsing ─────────────────────────────────────────────────

(defn- defcell-meta
  "Pulls `:id :doc :input :output :requires` out of a parsed
   `(cell/defcell <id> {<opts>} <handler>)` form. Returns nil if the
   form isn't a defcell.

   Cells produced by sporulator's codegen always have this shape, so
   plain destructuring of the second element works. Hand-written cells
   that omit the opts map (defcell with no schema) are also tolerated."
  [form]
  (when (and (seq? form)
             (contains? '#{cell/defcell mycelium.cell/defcell} (first form))
             (>= (count form) 3))
    (let [cell-id (nth form 1)
          opts    (nth form 2)
          handler (nth form 3)]
      (if (map? opts)
        {:cell-id  cell-id
         :doc      (:doc opts)
         :input    (:input opts)
         :output   (:output opts)
         :requires (:requires opts)
         :handler  handler}
        ;; Two-arg defcell: (defcell :id (fn ...))
        {:cell-id cell-id
         :handler opts}))))

(defn- parse-cell-file
  "Reads a cell source file and returns a normalised seed map for
   `store/save-cell!`. Returns nil when the file has no recognisable
   defcell form."
  [^java.io.File file]
  (let [src   (slurp file)
        forms (try (extract/read-all-forms src) (catch Throwable _ nil))]
    (when forms
      (when-let [defcell-form (first (filter (fn [f]
                                               (and (seq? f)
                                                    (contains? '#{cell/defcell mycelium.cell/defcell}
                                                               (first f))))
                                             forms))]
        (when-let [m (defcell-meta defcell-form)]
          ;; `cells.id` in the store is bare-string form (no leading colon).
          (let [id-str (let [s (str (:cell-id m))]
                         (cond-> s (str/starts-with? s ":") (subs 1)))
                schema (when (or (:input m) (:output m))
                         (pr-str {:input  (:input m)
                                  :output (:output m)}))]
            {:id        id-str
             :handler   src              ;; whole file — used by orchestrator's
                                         ;; reload path as the assembled source
             :schema    (or schema "")
             :doc       (or (:doc m) "")
             :requires  (pr-str (or (:requires m) []))
             :created-by "bootstrap"}))))))

;; ── Public entry ────────────────────────────────────────────────

(defn bootstrap-from-project!
  "Seeds the store from a project's filesystem. Returns a summary map
     {:manifest-saved? bool
      :cells-saved     <count>
      :cells-skipped   <count>}.

   `opts`:
     :store         — sporulator store (required)
     :project-path  — root of the user's project (required)
     :base-ns       — namespace prefix for the cells dir under src/
                      (default \"app\")
     :manifest-id   — id to use when saving the manifest (default
                      derived from the manifest's :id field)"
  [{:keys [store project-path base-ns manifest-id]
    :or   {base-ns "app"}}]
  (let [;; --- manifest ---
        manifest-path (str project-path "/resources/manifest.edn")
        manifest-file (java.io.File. manifest-path)
        manifest-body (when (.exists manifest-file) (slurp manifest-file))
        manifest      (when manifest-body
                        (try (binding [*read-eval* false]
                               (read-string manifest-body))
                             (catch Throwable _ nil)))
        ;; Use the same manifest-id normalization the rest of sporulator
        ;; uses (manifest-diff/normalize-manifest-id strips a leading
        ;; colon but preserves slashes). Earlier we replaced "/" with
        ;; "-" here, which produced a different id ("todomvc-app") than
        ;; the architect's persistence path ("todomvc/app") and ended
        ;; up with two duplicate manifest rows in the store.
        m-id          (or manifest-id
                          (when manifest
                            (manifest-diff/normalize-manifest-id (:id manifest))))
        existing-m    (when (and store m-id) (store/get-latest-manifest store m-id))
        manifest-saved?
        (boolean
          (when (and store manifest m-id (nil? existing-m))
            (store/save-manifest! store
              {:id m-id :body manifest-body :created-by "bootstrap"})))

        ;; --- cells ---
        seeded-cells
        (when store
          (for [^java.io.File f (cell-files project-path base-ns)
                :let [seed (parse-cell-file f)]
                :when seed]
            seed))
        existing-ids (when store
                       (set (map :id (or (store/list-latest-cells store) []))))
        to-save      (remove (fn [s] (contains? existing-ids (:id s)))
                             seeded-cells)
        cells-saved  (count
                       (for [seed to-save]
                         (store/save-cell! store seed)))]
    {:manifest-saved? manifest-saved?
     :cells-saved     cells-saved
     :cells-skipped   (- (count seeded-cells) cells-saved)
     :manifest-id     m-id}))
