(ns sporulator.manifest-diff
  "Computes the cell-level diff between two manifests so the orchestrator
   can regenerate only what's changed.

   The diff is keyed by cell-id (which is globally unique within a workflow).
   Step names — the keys of the manifest's :cells map — are just structural
   labels for edges/pipeline; renaming a step keeps the cell intact.

   A cell's contract for diff purposes is (:doc, :schema, :requires).
   :on-error is a manifest-level dispatch concern, not a cell change.
   Edges and :pipeline are graph rewires and likewise don't trigger
   cell-level regeneration; the workflow validator catches structural
   mismatches between connected cells when they actually break."
  (:require [clojure.string :as str]))

(defn cells-by-id
  "Re-keys a manifest's :cells map from step-name to cell-id.
   Returns {} for nil or empty manifests."
  [manifest]
  (->> (:cells manifest)
       (keep (fn [[_step-name cell-def]]
               (when-let [id (:id cell-def)]
                 [id cell-def])))
       (into {})))

(defn- normalize-schema
  [schema]
  (if (or (nil? schema) (= {} schema))
    {}
    schema))

(defn- normalize-requires
  [requires]
  (set (or requires [])))

(defn- normalize-doc
  [doc]
  (or doc ""))

(defn- contract-of
  "Extracts the trio of fields whose changes drive cell regeneration."
  [cell-def]
  {:schema   (normalize-schema (:schema cell-def))
   :requires (normalize-requires (:requires cell-def))
   :doc      (normalize-doc (:doc cell-def))})

(defn classify
  "Classifies the change between two cell-defs (either may be nil) as one of:
     :added   — old=nil, new present
     :removed — old present, new=nil
     :unchanged — both present, contracts equal
     :schema-changed — :schema or :requires moved
     :doc-changed    — only :doc moved"
  [old-cell new-cell]
  (cond
    (nil? old-cell) :added
    (nil? new-cell) :removed
    :else
    (let [o (contract-of old-cell)
          n (contract-of new-cell)]
      (cond
        (= o n) :unchanged
        (or (not= (:schema o)   (:schema n))
            (not= (:requires o) (:requires n))) :schema-changed
        :else :doc-changed))))

(defn diff
  "Computes a cell-id keyed diff between two manifests. Either argument
   may be nil or empty (fresh project starts from {}).

   Returns:
     {:added          [<cell-id> ...]
      :removed        [<cell-id> ...]
      :schema-changed [<cell-id> ...]
      :doc-changed    [<cell-id> ...]
      :unchanged      [<cell-id> ...]}

   Each cell-id list is sorted for stable output."
  [old-manifest new-manifest]
  (let [old-cells (cells-by-id old-manifest)
        new-cells (cells-by-id new-manifest)
        all-ids   (into #{} (concat (keys old-cells) (keys new-cells)))
        classified (group-by (fn [id]
                               (classify (get old-cells id)
                                         (get new-cells id)))
                             all-ids)
        sort-by-name (fn [ids] (vec (sort-by str ids)))]
    {:added          (sort-by-name (get classified :added []))
     :removed        (sort-by-name (get classified :removed []))
     :schema-changed (sort-by-name (get classified :schema-changed []))
     :doc-changed    (sort-by-name (get classified :doc-changed []))
     :unchanged      (sort-by-name (get classified :unchanged []))}))

(defn empty-diff?
  "True if the diff has no actionable cells — old and new manifests are
   functionally equivalent."
  [diff]
  (and (empty? (:added diff))
       (empty? (:removed diff))
       (empty? (:schema-changed diff))
       (empty? (:doc-changed diff))))

(defn affected-cells
  "Translates a diff into action buckets for the orchestrator.

   Returns:
     {:regen-tests-and-impl  — generate fresh tests + run cell agent
      :regen-impl-only       — keep existing test contract, run cell agent
      :carry-over            — already green, do nothing
      :delete                — deprecate in store + delete on disk}"
  [diff]
  {:regen-tests-and-impl (vec (concat (:added diff) (:schema-changed diff)))
   :regen-impl-only      (vec (:doc-changed diff))
   :carry-over           (vec (:unchanged diff))
   :delete               (vec (:removed diff))})

(defn format-diff
  "Renders a diff as a compact human-readable block."
  [diff]
  (let [{:keys [added removed schema-changed doc-changed unchanged]} diff
        section (fn [marker label ids]
                  (when (seq ids)
                    (str "  " marker " " (count ids) " " label ": "
                         (str/join ", " (map str ids))
                         "\n")))]
    (str "Workflow diff:\n"
         (section "+" "added"          added)
         (section "~" "schema changed" schema-changed)
         (section "~" "doc changed"    doc-changed)
         (section "-" "removed"        removed)
         (section "=" "unchanged"      unchanged)
         (when (empty-diff? diff) "  (no changes)\n"))))

(defn change-summary
  "Produces a per-cell summary string for use in the agent's edit-mode prompt:
   describes how this cell's contract differs from the previous green version.

   Returns nil for :added cells (no prior contract) and :unchanged cells."
  [old-cell-def new-cell-def]
  (let [class (classify old-cell-def new-cell-def)]
    (case class
      :added     nil
      :removed   nil
      :unchanged nil
      (let [o (contract-of old-cell-def)
            n (contract-of new-cell-def)
            parts (cond-> []
                    (not= (:schema o) (:schema n))
                    (conj (str "  - schema:\n"
                               "      previous: " (pr-str (:schema o)) "\n"
                               "      new:      " (pr-str (:schema n))))

                    (not= (:requires o) (:requires n))
                    (conj (str "  - requires:\n"
                               "      previous: " (pr-str (vec (sort (:requires o)))) "\n"
                               "      new:      " (pr-str (vec (sort (:requires n))))))

                    (not= (:doc o) (:doc n))
                    (conj (str "  - doc:\n"
                               "      previous: " (:doc o) "\n"
                               "      new:      " (:doc n))))]
        (str "Contract changes since the previous green implementation:\n"
             (str/join "\n" parts))))))
