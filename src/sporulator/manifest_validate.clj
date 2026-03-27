(ns sporulator.manifest-validate
  "Programmatic manifest validation for the sporulator.
   Validates graph structure via mycelium.manifest/validate-manifest,
   checks schema compatibility between connected cells,
   and extracts graph context (predecessors/successors) for cell briefs."
  (:require [clojure.string :as str]
            [mycelium.manifest :as manifest]))

;; =============================================================
;; Manifest parsing (handles fn forms in dispatches)
;; =============================================================

(defn parse-manifest
  "Parses a manifest EDN string using read-string (not edn/read-string)
   so that (fn ...) forms in dispatches survive parsing.
   Returns the parsed map, or {:error message} on failure."
  [edn-str]
  (try
    (binding [*read-eval* false]
      (read-string edn-str))
    (catch Exception e
      {:error (.getMessage e)})))

;; =============================================================
;; Structural validation (delegates to mycelium)
;; =============================================================

(defn validate-structure
  "Validates manifest structure via mycelium.manifest/validate-manifest.
   Uses {:strict? false} since LLM-generated manifests may lack :on-error.
   Returns {:status :ok :manifest normalized-manifest}
        or {:status :error :issues [string ...]}."
  [manifest]
  (try
    (let [validated (manifest/validate-manifest manifest {:strict? false})]
      {:status :ok :manifest validated})
    (catch clojure.lang.ExceptionInfo e
      {:status :error
       :issues [(ex-message e)]})
    (catch Exception e
      {:status :error
       :issues [(.getMessage e)]})))

;; =============================================================
;; Schema field extraction
;; =============================================================

(defn extract-map-fields
  "Extracts field-name → type mappings from a Malli schema form.
   Handles [:map [:field :type] ...] standard syntax.
   Returns nil for generic schemas ([:map], nil, non-map schemas).
   For :union schemas, returns nil (too complex to statically check)."
  [schema]
  (cond
    (nil? schema) nil

    ;; Plain map (lite syntax): {:field :type}
    (map? schema)
    (when (seq schema)
      (into {} (map (fn [[k v]] [(keyword k) v])) schema))

    ;; Vector — check first element
    (vector? schema)
    (let [tag (first schema)]
      (cond
        ;; [:map [:field :type] ...]
        (= :map tag)
        (let [entries (rest schema)]
          (when (seq entries)
            (into {}
                  (keep (fn [entry]
                          (when (and (vector? entry) (>= (count entry) 2))
                            [(first entry) (if (= 3 (count entry))
                                             (nth entry 2)
                                             (second entry))])))
                  entries)))

        ;; :union — skip, too complex
        (= :union tag) nil

        ;; anything else ([:vector ...], :string, etc.)
        :else nil))

    :else nil))

;; =============================================================
;; Type compatibility
;; =============================================================

(defn types-compatible?
  "Checks if an output type can feed an input type.
   Exact match, :int → :double widening, or deep structural equality."
  [output-type input-type]
  (or (= output-type input-type)
      ;; Safe widening: :int can feed :double
      (and (= output-type :int) (= input-type :double))
      ;; :any accepts anything
      (= input-type :any)))

;; =============================================================
;; Edge pair extraction
;; =============================================================

(defn- edge-targets
  "Extracts target cell names from an edge definition.
   Returns a seq of [transition-key target-name] pairs.
   For unconditional edges, transition-key is nil."
  [edge-def]
  (cond
    (keyword? edge-def) [[nil edge-def]]
    (map? edge-def) (map (fn [[k v]] [k v]) edge-def)
    :else []))

(def ^:private terminal-targets #{:end :error :halt})

(defn extract-edge-pairs
  "Builds source→target schema pairs from manifest :cells and :edges.
   For per-transition output schemas, matches specific transition output.
   Skips :end/:error/:halt targets.
   Returns [{:source-name kw :source-output schema
             :target-name kw :target-input schema} ...]."
  [manifest]
  (let [cells (:cells manifest)
        edges (:edges manifest)]
    (vec
      (for [[source-name edge-def] edges
            [transition-key target-name] (edge-targets edge-def)
            :when (not (terminal-targets target-name))
            :let [source-cell (get cells source-name)
                  target-cell (get cells target-name)]
            :when (and source-cell target-cell)
            :let [source-output (get-in source-cell [:schema :output])
                  ;; For per-transition output maps, use the specific transition's schema
                  source-output (if (and transition-key (map? source-output))
                                  (get source-output transition-key source-output)
                                  source-output)
                  target-input (get-in target-cell [:schema :input])]]
        {:source-name   source-name
         :source-output source-output
         :target-name   target-name
         :target-input  target-input}))))

;; =============================================================
;; Schema compatibility checking
;; =============================================================

(defn check-edge-compatibility
  "Checks schema compatibility for a single edge pair.
   Returns nil if compatible, or a map with details:
   {:source-name kw :target-name kw
    :missing-fields [kw ...] :type-diffs [string ...]}."
  [{:keys [source-name source-output target-name target-input]}]
  (let [output-fields (extract-map-fields source-output)
        input-fields  (extract-map-fields target-input)]
    (cond
      ;; Generic target ([:map] or nil) — accepts anything
      (nil? input-fields) nil

      ;; Generic source but target expects specific fields
      (nil? output-fields)
      {:source-name    source-name
       :target-name    target-name
       :missing-fields (vec (keys input-fields))
       :type-diffs     []}

      :else
      (let [missing (vec (for [[field _type] input-fields
                               :when (not (contains? output-fields field))]
                           field))
            diffs   (vec (for [[field input-type] input-fields
                               :let [output-type (get output-fields field)]
                               :when (and output-type
                                          (not (types-compatible? output-type input-type)))]
                           (str field ": output=" (pr-str output-type)
                                " input=" (pr-str input-type))))]
        (when (or (seq missing) (seq diffs))
          {:source-name    source-name
           :target-name    target-name
           :missing-fields missing
           :type-diffs     diffs})))))

(defn find-schema-mismatches
  "Checks all edge pairs in a manifest for schema compatibility.
   Returns a vector of mismatch maps (empty if all compatible)."
  [manifest]
  (vec (keep check-edge-compatibility (extract-edge-pairs manifest))))

;; =============================================================
;; Formatting for LLM feedback
;; =============================================================

(defn format-issues
  "Renders structural issues and schema mismatches as a human-readable string."
  [{:keys [issues mismatches]}]
  (str
    (when (seq issues)
      (str "Structural issues:\n"
           (str/join "\n" (map #(str "- " %) issues))
           "\n"))
    (when (seq mismatches)
      (str "\nSchema compatibility issues:\n"
           (str/join "\n"
                     (map (fn [{:keys [source-name target-name missing-fields type-diffs]}]
                            (str "- " source-name " → " target-name
                                 (when (seq missing-fields)
                                   (str " missing fields: " (pr-str missing-fields)))
                                 (when (seq type-diffs)
                                   (str " type mismatches: " (str/join ", " type-diffs)))))
                          mismatches))
           "\n"))))

;; =============================================================
;; Graph context for cell briefs
;; =============================================================

(defn build-graph-context
  "Extracts predecessors and successors for a cell from the manifest.
   Returns {:predecessors [{:cell-id kw :doc str :output-schema schema}]
            :successors  [{:cell-id kw :doc str :input-schema schema}]}."
  [manifest cell-name]
  (let [cells (:cells manifest)
        edges (:edges manifest)
        ;; Predecessors: cells whose edges point to cell-name
        predecessors
        (vec
          (for [[source-name edge-def] edges
                :when (not= source-name cell-name)
                [_transition target] (edge-targets edge-def)
                :when (= target cell-name)
                :let [source-cell (get cells source-name)]
                :when source-cell]
            {:cell-id       (:id source-cell)
             :doc           (:doc source-cell)
             :output-schema (get-in source-cell [:schema :output])}))
        ;; Successors: targets of this cell's edges
        successors
        (when-let [edge-def (get edges cell-name)]
          (vec
            (for [[_transition target] (edge-targets edge-def)
                  :when (not (terminal-targets target))
                  :let [target-cell (get cells target)]
                  :when target-cell]
              {:cell-id      (:id target-cell)
               :doc          (:doc target-cell)
               :input-schema (get-in target-cell [:schema :input])})))]
    {:predecessors (distinct predecessors)
     :successors   (or (distinct successors) [])}))

(defn format-graph-context
  "Renders graph context as a human-readable string for cell prompts.
   Returns empty string if no neighbors."
  [{:keys [predecessors successors]}]
  (if (and (empty? predecessors) (empty? successors))
    ""
    (str
      (when (seq predecessors)
        (str "\n**Receives data from:**\n"
             (str/join ""
                       (for [p predecessors]
                         (str "- " (pr-str (:cell-id p)) " — " (:doc p) "\n"
                              (when (:output-schema p)
                                (str "  Output schema: " (pr-str (:output-schema p)) "\n")))))))
      (when (seq successors)
        (str "\n**Feeds data to:**\n"
             (str/join ""
                       (for [s successors]
                         (str "- " (pr-str (:cell-id s)) " — " (:doc s) "\n"
                              (when (:input-schema s)
                                (str "  Input schema: " (pr-str (:input-schema s)) "\n"))))))))))

;; =============================================================
;; Full manifest validation
;; =============================================================

(defn validate-manifest
  "Full programmatic validation of a manifest (parsed map).
   Returns {:status :ok :manifest normalized-manifest}
        or {:status :error :issues [...] :mismatches [...]}."
  [manifest]
  (let [result (validate-structure manifest)]
    (if (= :error (:status result))
      result
      ;; Structure is valid — check schema compatibility
      (let [normalized (:manifest result)
            mismatches (find-schema-mismatches normalized)]
        (if (seq mismatches)
          {:status     :warning
           :manifest   normalized
           :mismatches mismatches}
          {:status   :ok
           :manifest normalized})))))
