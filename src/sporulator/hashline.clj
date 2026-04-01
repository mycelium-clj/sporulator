(ns sporulator.hashline
  "Hashline annotations for code feedback.
   Tags each line with a short content hash so LLMs can reference
   specific locations without counting lines manually."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest]))

(defn- line-hash
  "Returns a 2-character hex hash of a line's content."
  [line]
  (let [digest (MessageDigest/getInstance "MD5")
        bytes  (.digest digest (.getBytes (str line) "UTF-8"))]
    (format "%02x" (bit-and (aget bytes 0) 0xff))))

(defn annotate-hashlines
  "Annotates each line of code with a line number and content hash.
   Format: `N:HH| content`
   Returns empty string for empty input."
  [code]
  (if (or (nil? code) (str/blank? code))
    ""
    (->> (str/split code #"\n" -1)
         (map-indexed (fn [i line]
                        (str (inc i) ":" (line-hash line) "| " line)))
         (str/join "\n"))))

(defn strip-hashlines
  "Strips hashline annotations, recovering the original code.
   Removes the `N:HH| ` prefix from each line."
  [annotated]
  (if (or (nil? annotated) (str/blank? annotated))
    ""
    (->> (str/split annotated #"\n" -1)
         (map (fn [line]
                (if-let [m (re-find #"^\d+:[0-9a-f]+\| (.*)$" line)]
                  (second m)
                  line)))
         (str/join "\n"))))
