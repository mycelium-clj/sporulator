(ns sporulator.source-gen-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [sporulator.source-gen :as sg]
            [sporulator.store :as store]))

(def ^:dynamic *store* nil)
(def ^:dynamic *out-dir* nil)

(defn with-store-and-dir [f]
  (let [s (store/open ":memory:")
        dir (str (System/getProperty "java.io.tmpdir")
                 "/sporulator-test-" (System/nanoTime))]
    (try
      (binding [*store* s *out-dir* dir]
        (f))
      (finally
        ;; Clean up generated files
        (doseq [f (reverse (file-seq (io/file dir)))]
          (.delete f))
        (store/close s)))))

(use-fixtures :each with-store-and-dir)

(deftest generate-sources-test
  (testing "generates cell source files from store"
    ;; Save some cells
    (store/save-cell! *store*
      {:id ":order/validate"
       :handler "(cell/defcell :order/validate\n  {:doc \"Validates\" :input {:x :int} :output {:valid? :boolean}}\n  (fn [_ data] {:valid? true}))"
       :schema "{:input {:x :int} :output {:valid? :boolean}}"
       :doc "Validates order"})
    (store/save-cell! *store*
      {:id ":order/compute-tax"
       :handler "(cell/defcell :order/compute-tax\n  {:doc \"Tax\" :input {:subtotal :double} :output {:tax :double}}\n  (fn [_ data] {:tax (* 0.1 (:subtotal data))}))"
       :schema "{:input {:subtotal :double} :output {:tax :double}}"
       :doc "Computes tax"})

    (let [result (sg/generate *store*
                   {:output-dir *out-dir*
                    :base-namespace "myapp"})]
      (is (some? result))
      (is (pos? (count (:files result))))
      ;; Check files were written
      (doseq [{:keys [path]} (:files result)]
        (is (.exists (io/file *out-dir* path))
            (str "File should exist: " path)))))

  (testing "generates manifest namespace files"
    (store/save-manifest! *store*
      {:id ":order/placement" :body "{:id :order/placement :cells {} :pipeline []}"})
    (let [result (sg/generate *store*
                   {:output-dir *out-dir*
                    :base-namespace "myapp"})]
      (is (some? (some #(str/includes? (:path %) "workflow") (:files result)))))))

(deftest generate-empty-store-test
  (testing "returns empty result for empty store"
    (let [result (sg/generate *store*
                   {:output-dir *out-dir*
                    :base-namespace "myapp"})]
      (is (some? result))
      (is (empty? (:files result))))))
