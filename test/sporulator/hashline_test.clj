(ns sporulator.hashline-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [sporulator.hashline :as hl]))

(deftest annotate-hashlines-test
  (testing "produces numbered lines with hashes"
    (let [code "(ns foo)\n(defn bar [x] x)"
          result (hl/annotate-hashlines code)]
      (is (str/includes? result "1:"))
      (is (str/includes? result "| (ns foo)"))
      (is (str/includes? result "2:"))
      (is (str/includes? result "| (defn bar [x] x)"))))

  (testing "hash changes when content changes"
    (let [a (hl/annotate-hashlines "(+ 1 2)")
          b (hl/annotate-hashlines "(+ 1 3)")]
      (is (not= a b))))

  (testing "empty string returns empty"
    (is (= "" (hl/annotate-hashlines ""))))

  (testing "single line"
    (let [result (hl/annotate-hashlines "hello")]
      (is (str/starts-with? result "1:"))
      (is (str/includes? result "| hello")))))

(deftest strip-hashlines-test
  (testing "round-trip recovers original"
    (let [original "(ns foo)\n(defn bar [x] x)\n(bar 42)"
          annotated (hl/annotate-hashlines original)
          stripped (hl/strip-hashlines annotated)]
      (is (= original stripped))))

  (testing "strips annotation prefix from each line"
    (let [annotated "1:a3| (ns foo)\n2:f1| (defn bar [])"
          stripped (hl/strip-hashlines annotated)]
      (is (= "(ns foo)\n(defn bar [])" stripped))))

  (testing "empty string"
    (is (= "" (hl/strip-hashlines "")))))
