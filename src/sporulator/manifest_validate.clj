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
;; Output-shape gate — :any rejected
;; =============================================================
;;
;; Cells declare both :input and :output schemas. The :input side may be
;; permissive (`:any`, `[:map]`) — that's just the receiver agreeing to
;; accept a wide shape. The :output side, however, is the producer's
;; promise to downstream cells: if it's opaque, downstream contract
;; checks have nothing to bind against. So we hard-reject `:any` and
;; nil outputs at validation time and force the architect to declare
;; what the cell actually produces. Per-transition outputs (dispatched)
;; are checked on each branch separately.

(defn- opaque-output-schema?
  "True when an output schema gives the validator nothing to bind
   against — `:any`, nil, or an explicit empty/unconstrained shape.
   Used to fail the architect's manifest before downstream edges can
   be checked at all."
  [schema]
  (or (nil? schema)
      (= :any schema)
      (= [:any] schema)))

(defn check-output-declared
  "Returns nil if the cell declares a usable :output schema, an issue
   string otherwise. For dispatched outputs, every per-transition
   sub-schema must be non-opaque too — otherwise the dispatched edge
   can't be bound."
  [cell-name cell]
  (let [output (get-in cell [:schema :output])]
    (cond
      (opaque-output-schema? output)
      (str cell-name " declares an opaque :output (" (pr-str output)
           "). Every cell must declare what it produces; downstream "
           "edges can't be validated against `:any`.")

      ;; Dispatched: map-of-transition → sub-schema. Any opaque sub
      ;; (e.g. `{:success [:map [:id :int]] :failure :any}`) is also a
      ;; gap, since the :failure edge would be unbindable.
      (and (map? output) (seq output))
      (let [opaque-transitions
            (vec (for [[label sub] output
                       :when (opaque-output-schema? sub)]
                   label))]
        (when (seq opaque-transitions)
          (str cell-name " has opaque per-transition output(s): "
               (pr-str opaque-transitions)
               ". Each transition must declare its produced shape."))))))

(defn find-opaque-outputs
  "Walks every cell in the manifest and returns a vector of issue
   strings for cells whose :output is opaque (`:any`, nil, or an
   opaque per-transition entry). Empty vector when all outputs are
   declared."
  [manifest]
  (vec
    (keep (fn [[cell-name cell]]
            (check-output-declared cell-name cell))
          (:cells manifest))))

;; ── Sequence-operator lint (catches `[:?]` and friends in map entries) ──
;;
;; Malli has sequence operators (`:?`, `:*`, `:+`, `:cat`, `:catn`,
;; `:alt`, `:altn`, `:repeat`) that operate inside `[:cat ...]` or
;; `[:sequential ...]`. They are *valid Malli schemas* on their own,
;; so the validator can't reject them outright — but inside a flat
;; map entry's type position they are almost always a mistake for
;; "optional", because `[:? :string]` parses as "a sequence of zero
;; or one strings" and never matches a flat string value. This lint
;; catches the architect's invented `[:?]` shorthand and steers
;; toward the canonical `{:optional true}` form.

(def ^:private sequence-operator-heads
  #{:? :* :+ :cat :catn :alt :altn :repeat})

(defn- seq-ops-in-map-schema
  "Walks a `:map` schema's entries; returns a vector of
   `[entry-key seq-op]` pairs whose type position uses a top-level
   sequence-operator schema head. Returns nil for non-map schemas."
  [schema]
  (when (and (vector? schema) (= :map (first schema)))
    (seq
      (keep (fn [entry]
              (when (and (vector? entry) (>= (count entry) 2))
                (let [type-pos (last entry)
                      k        (first entry)]
                  (when (and (vector? type-pos)
                             (sequence-operator-heads (first type-pos)))
                    [k (first type-pos)]))))
            (rest schema)))))

(defn- check-cell-for-bad-optionals
  "Returns an issue string if any of the cell's input/output map
   entries use `[:?]`-style shorthand (or other sequence operators)
   instead of the canonical `[:k {:optional true} :type]` form."
  [cell-name cell]
  (let [in  (get-in cell [:schema :input])
        out (get-in cell [:schema :output])
        bad-in  (seq-ops-in-map-schema in)
        ;; Output may be dispatched (map of transition → vector schema).
        bad-out (cond
                  (vector? out) (seq-ops-in-map-schema out)
                  (and (map? out) (seq out))
                  (seq (mapcat (fn [[label sub]]
                                 (when-let [pairs (seq-ops-in-map-schema sub)]
                                   (mapv (fn [[k op]] [(keyword (str (name label) "/" (name k))) op])
                                         pairs)))
                               out)))
        bad     (concat bad-in bad-out)]
    (when (seq bad)
      (str cell-name " uses sequence-operator schema head(s) inside map entries:\n"
           (str/join "\n"
             (for [[k op] bad]
               (str "  - :" (name k) " has type starting with " op
                    " — sequence operators (`:?`, `:*`, `:+`, `:cat`, ...) "
                    "belong inside `[:cat ...]` or `[:sequential ...]`. "
                    "For an OPTIONAL map entry use "
                    "`[" (pr-str k) " {:optional true} <type>]` instead.")))))))

(defn find-bad-optionals
  "Walks every cell in the manifest and returns issue strings for
   any cell whose :input or :output uses `[:?]`-style sequence-op
   shorthand inside a map entry."
  [manifest]
  (vec
    (keep (fn [[cell-name cell]]
            (check-cell-for-bad-optionals cell-name cell))
          (:cells manifest))))

;; =============================================================
;; Schema field extraction
;; =============================================================

(defn extract-map-fields
  "Extracts field information from a Malli `:map` schema form.
   Returns
     {:required {field-name type ...}
      :optional {field-name type ...}}
   for any schema that's a map (vector or lite form), or nil for
   generic / non-map schemas.

   Lite-form maps (`{:field :type}`) have no notion of optional, so
   every field lands in `:required`.

   Vector form distinguishes via the 3-element entry shape:
   `[:k {:optional true} :type]` → optional; `[:k :type]` or
   `[:k <props-without-:optional> :type]` → required."
  [schema]
  (cond
    (nil? schema) nil

    ;; Lite map: {:field :type ...} — no way to mark optional, all required.
    (map? schema)
    (when (seq schema)
      {:required (into {} (map (fn [[k v]] [(keyword k) v])) schema)
       :optional {}})

    ;; Vector — only :map carries field info.
    (vector? schema)
    (let [tag (first schema)]
      (cond
        (= :map tag)
        (let [entries (rest schema)]
          (when (seq entries)
            (reduce
              (fn [acc entry]
                (if (and (vector? entry) (>= (count entry) 2))
                  (let [k        (first entry)
                        ;; 2-element: [:k :type]
                        ;; 3-element: [:k <props> :type]
                        ;;   props with :optional true → optional
                        [props t] (if (= 3 (count entry))
                                    [(second entry) (nth entry 2)]
                                    [nil (second entry)])
                        optional? (and (map? props) (true? (:optional props)))
                        bucket    (if optional? :optional :required)]
                    (assoc-in acc [bucket k] t))
                  acc))
              {:required {} :optional {}}
              entries)))

        ;; :union, [:vector ...], etc. — not a map shape, no field info.
        :else nil))

    :else nil))

(defn- required-fields
  "Returns just the {field type} map of REQUIRED fields, or nil for
   generic schemas. Convenience wrapper."
  [schema]
  (some-> schema extract-map-fields :required))

(defn- all-fields
  "Returns the union of required + optional fields as {field type}, or
   nil for generic schemas. Used on the producer side: a producer
   guarantees a key whether its declared schema marks it required or
   optional in its OWN map (since the producer is the writer)."
  [schema]
  (when-let [m (extract-map-fields schema)]
    (merge (:required m) (:optional m))))

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

   Compatibility rule:
     - Producer's `:output` declares which keys downstream can rely on.
       A key the producer marks `{:optional true}` in its own output
       *may* be present, so consumers can't rely on it — only a
       producer's REQUIRED output keys count as guaranteed.
     - Consumer's `:input` REQUIRED keys must each be in the producer's
       guaranteed set, with compatible types.
     - Consumer's `:input` OPTIONAL keys impose no obligation on the
       producer.

   Returns nil if compatible, or a map with details:
     {:source-name kw :target-name kw
      :missing-fields [kw ...] :type-diffs [string ...]}"
  [{:keys [source-name source-output target-name target-input]}]
  (let [output-required (required-fields source-output)
        ;; Consumer-side: split into required vs optional explicitly.
        input-shape     (extract-map-fields target-input)
        input-required  (when input-shape (:required input-shape))]
    (cond
      ;; Consumer accepts a generic shape — no required keys to check.
      (nil? input-shape)              nil
      (empty? input-required)         nil

      ;; Producer declares no map shape (e.g. `[:vector ...]` or scalar
      ;; output) but consumer requires keys → unsatisfiable.
      (nil? output-required)
      {:source-name    source-name
       :target-name    target-name
       :missing-fields (vec (keys input-required))
       :type-diffs     []}

      :else
      (let [missing (vec (for [[field _type] input-required
                               :when (not (contains? output-required field))]
                           field))
            diffs   (vec (for [[field input-type] input-required
                               :let [output-type (get output-required field)]
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

(defn validate-edges
  "Schema-only validation of a manifest. Skips mycelium's structural
   checks (start-cell present, reachability, etc.) and focuses on the
   architect's contract:
     1. Opaque-output rejection — every cell must declare what it produces.
     2. Edge schema compatibility — each producer/consumer pair on every
        edge must agree on required input keys + types.

   This is the gate `orchestrate!` runs at entry — failures here mean
   the workflow is undefined regardless of its overall structure, and
   no LLM budget should be spent.

   Returns:
     {:status :ok}
     {:status :error :issues [...]}                  ;; opaque outputs
     {:status :error :mismatches [...]}              ;; edge schema gaps"
  [manifest]
  (let [opaque-issues  (find-opaque-outputs manifest)
        bad-optionals  (find-bad-optionals manifest)
        mismatches     (find-schema-mismatches manifest)]
    (cond
      ;; Opaque outputs come first because subsequent edge checks
      ;; can't bind against `:any` — surfacing both at once would be
      ;; noise.
      (seq opaque-issues)
      {:status :error :issues opaque-issues}

      ;; Sequence-operator shorthand inside map entries is the
      ;; architect's invented "optional" form. It parses cleanly so
      ;; type-diff messages alone are confusing — we lint it here so
      ;; the issue names the actual bug.
      (seq bad-optionals)
      {:status :error :issues bad-optionals}

      (seq mismatches)
      {:status :error :mismatches mismatches}

      :else
      {:status :ok})))

(defn validate-manifest
  "Full programmatic validation of a manifest (parsed map). Combines
   mycelium's structural validator (start cell, reachability, etc.)
   with this module's schema-compatibility checks.

   `orchestrate!` uses the lighter `validate-edges` for its entry-gate
   so structural minutiae don't block runs that are otherwise sound;
   mycelium catches structural problems at workflow compile time.
   Use `validate-manifest` when you want both layers reported.

   Returns:
     {:status :ok :manifest normalized}
     {:status :error :issues [...] :mismatches [...] :manifest normalized}"
  [manifest]
  (let [result (validate-structure manifest)]
    (if (= :error (:status result))
      result
      (let [normalized (:manifest result)
            edge-r     (validate-edges normalized)]
        (assoc edge-r :manifest normalized)))))
