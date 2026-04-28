(ns sporulator.extract
  "Extracts code and forms from LLM responses.
   Uses Clojure reader for all form parsing — no hand-rolled paren balancing
   for form extraction. Regex is used only for markdown fence detection
   and comment-based directives (appropriate text processing)."
  (:require [clojure.string :as str])
  (:import [java.io PushbackReader StringReader]))

;; =============================================================
;; Markdown code block extraction (regex — appropriate for markdown)
;; =============================================================

(def ^:private code-block-re
  #"(?s)`{3,}(?:clojure|clj|edn)?\s*\n(.*?)\n\s*`{3,}")

(defn extract-code-blocks
  "Returns all fenced code blocks from an LLM response.
   Matches ```clojure, ```clj, ```edn, and bare ``` blocks."
  [response]
  (->> (re-seq code-block-re response)
       (mapv (comp str/trim second))
       (filterv (complement str/blank?))))

(defn strip-fence-markers
  "Removes markdown code fence markers from start and end of text."
  [s]
  (let [lines (str/split-lines s)]
    (if (< (count lines) 2)
      s
      (let [lines (cond-> (vec lines)
                    (str/starts-with? (str/trim (first lines)) "```")
                    (subvec 1))
            lines (cond-> lines
                    (and (seq lines)
                         (str/starts-with? (str/trim (peek lines)) "```"))
                    (subvec 0 (dec (count lines))))]
        (str/join "\n" lines)))))

;; =============================================================
;; Clojure reader — all form parsing goes through here
;; =============================================================

(defn read-all-forms
  "Reads all top-level forms from a Clojure code string using the
   built-in Clojure reader. Returns a vector of forms, or nil if
   reading fails (malformed/truncated code)."
  [code]
  (when (and code (not (str/blank? code)))
    (try
      (binding [*read-eval* false]
        (let [rdr (PushbackReader. (StringReader. code))]
          (loop [forms []]
            (let [form (read {:eof ::eof} rdr)]
              (if (= form ::eof)
                forms
                (recur (conj forms form)))))))
      (catch Exception _ nil))))

;; =============================================================
;; Paren depth — minimal recovery for truncated LLM output
;; =============================================================

(defn paren-depth
  "Returns the unclosed paren/bracket/brace depth of code,
   accounting for string literals. Used only for truncation recovery."
  [s]
  (loop [i 0, depth 0, in-string? false, escaped? false]
    (if (>= i (count s))
      depth
      (let [ch (nth s i)]
        (cond
          escaped?
          (recur (inc i) depth in-string? false)

          (and in-string? (= ch \\))
          (recur (inc i) depth in-string? true)

          (= ch \")
          (recur (inc i) depth (not in-string?) false)

          in-string?
          (recur (inc i) depth in-string? false)

          (= ch \()
          (recur (inc i) (inc depth) false false)

          (= ch \))
          (recur (inc i) (dec depth) false false)

          :else
          (recur (inc i) depth false false))))))

(defn truncated?
  "Returns true if code has unbalanced parens (appears truncated)."
  [code]
  (pos? (paren-depth (or code ""))))

(defn balance-parens
  "Appends closing parens to truncated code. A recovery mechanism
   for LLM output that was cut short, not a parser."
  [code]
  (let [depth (paren-depth (or code ""))]
    (if (pos? depth)
      (str code (apply str (repeat depth \))))
      code)))

;; =============================================================
;; Heuristics
;; =============================================================

(defn looks-like-clojure?
  "Returns true if text starts with a common Clojure form."
  [s]
  (let [trimmed (str/trim (or s ""))]
    (or (str/starts-with? trimmed "(ns ")
        (str/starts-with? trimmed "(def")
        (str/starts-with? trimmed "(cell/")
        (str/starts-with? trimmed "(require"))))

;; =============================================================
;; First code block with fallback
;; =============================================================

(defn extract-first-code-block
  "Returns the first fenced code block from a response.
   Falls back to detecting bare Clojure code if no fences found."
  [response]
  (let [blocks (extract-code-blocks (or response ""))]
    (if (seq blocks)
      (first blocks)
      ;; Fallback: try stripping fence markers
      (let [stripped (strip-fence-markers (str/trim (or response "")))]
        (if (looks-like-clojure? stripped)
          stripped
          ;; Last resort: find (ns ...) form in raw text
          (when-let [idx (str/index-of response "(ns ")]
            (balance-parens (subs response idx))))))))

;; =============================================================
;; Structural form extraction — uses Clojure reader, returns data
;; =============================================================

(defn- fn-form?
  "Returns true if form is a top-level (fn ...) list."
  [form]
  (and (seq? form) (= 'fn (first form))))

(defn- defcell-form?
  "Returns true if form is a (cell/defcell ...) form."
  [form]
  (and (seq? form) (= 'cell/defcell (first form))))

(defn- read-all-forms-lenient
  "Tries to read forms; if that fails, balances parens and retries."
  [code]
  (or (read-all-forms code)
      (read-all-forms (balance-parens code))))

(defn extract-fn-body
  "Extracts the last top-level (fn ...) form from code.
   Uses Clojure reader — correctly handles fn refs inside strings
   and nested fn forms within defn bodies. Returns the form, or nil."
  [code]
  (when-let [forms (read-all-forms-lenient code)]
    (last (filter fn-form? forms))))

(defn extract-helpers
  "Extracts helper forms (all top-level forms before the last fn form).
   Returns a vector of forms, or nil if no helpers."
  [code]
  (when-let [forms (read-all-forms-lenient code)]
    (let [fn-indices (keep-indexed (fn [i f] (when (fn-form? f) i)) forms)
          last-fn-idx (last fn-indices)]
      (when (and last-fn-idx (pos? last-fn-idx))
        (subvec (vec forms) 0 last-fn-idx)))))

(defn extract-extra-requires
  "Extracts ;; REQUIRE: [...] comment directives from code.
   Returns a vector of parsed require spec vectors.
   Comments are not Clojure forms, so regex is appropriate here."
  [code]
  (if-not code
    []
    (let [re #"(?m)^;;\s*REQUIRE:\s*(\[.+\])\s*$"]
      (->> (re-seq re code)
           (mapv (comp read-string second))))))

(defn extract-defcell
  "Extracts the first (cell/defcell ...) form from an LLM response.
   Searches code blocks first, then raw text. Returns the form, or nil."
  [response]
  (let [blocks (extract-code-blocks (or response ""))
        source (if (seq blocks) (str/join "\n\n" blocks) response)]
    (when-let [forms (read-all-forms-lenient source)]
      (first (filter defcell-form? forms)))))

(defn extract-all-defcells
  "Extracts all (cell/defcell ...) forms from an LLM response.
   Returns a vector of forms."
  [response]
  (let [blocks (extract-code-blocks (or response ""))
        source (if (seq blocks) (str/join "\n\n" blocks) response)]
    (when-let [forms (read-all-forms-lenient source)]
      (vec (filter defcell-form? forms)))))

(defn extract-self-review-corrections
  "Checks if a self-review response contains test corrections.
   Returns the code block string if it contains deftest forms, nil otherwise."
  [response]
  (when-not (str/includes? (or response "") "ALL TESTS VERIFIED")
    (let [code (extract-first-code-block response)]
      (when (and code (str/includes? code "deftest"))
        code))))

(defn extract-cell-source-parts
  "Splits an assembled cell source string back into the parts the agent
   workspace expects:
     {:handler  '(fn [resources data] ...)' as a string, or nil
      :helpers  helper defns joined by blank lines, or nil}

   Used to pre-load handler.clj and helpers.clj when regenerating an
   existing cell in edit-mode. Returns nil keys when nothing is found."
  [source]
  (when (and source (not (str/blank? source)))
    (when-let [forms (read-all-forms-lenient source)]
      (let [defcell    (first (filter defcell-form? forms))
            handler    (when defcell (last defcell))
            ;; Helpers are top-level defns/defs that appear before the
            ;; defcell form. Skip the (ns ...) declaration and skip the
            ;; defcell itself.
            helper-form? (fn [f]
                           (and (seq? f)
                                (contains? '#{defn def defmacro defprotocol defrecord deftype}
                                           (first f))))
            helpers    (->> forms
                            (take-while #(not (defcell-form? %)))
                            (filter helper-form?)
                            vec)]
        {:handler (when handler (pr-str handler))
         :helpers (when (seq helpers)
                    (str/join "\n\n" (map pr-str helpers)))}))))
