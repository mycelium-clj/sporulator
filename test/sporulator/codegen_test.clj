(ns sporulator.codegen-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [sporulator.codegen :as codegen]))

;; =============================================================
;; Cell source assembly
;; Translated from Go scaffold_test.go
;; =============================================================

(deftest assemble-cell-source-test
  (testing "full assembly with all options"
    (let [result (codegen/assemble-cell-source
                   {:cell-ns "example.cells.order"
                    :cell-id :order/validate
                    :doc "Validate input"
                    :schema {:input [:map] :output [:map]}
                    :requires [:db]
                    :extra-requires ['[clojure.string :as str]]
                    :helpers ['(defn helper [x] x)]
                    :fn-body '(fn [resources data] data)})]
      (is (str/includes? result "(ns example.cells.order"))
      (is (str/includes? result "[mycelium.cell :as cell]"))
      (is (str/includes? result "[clojure.string :as str]"))
      (is (str/includes? result "(defn helper [x] x)"))
      (is (str/includes? result "(cell/defcell :order/validate"))
      (is (str/includes? result "\"Validate input\""))
      (is (str/includes? result "(fn [resources data] data)"))))

  (testing "no helpers - no triple newlines"
    (let [result (codegen/assemble-cell-source
                   {:cell-ns "example.cells.order"
                    :cell-id :order/validate
                    :doc "Validate"
                    :schema {:input [:map] :output [:map]}
                    :fn-body '(fn [resources data] data)})]
      (is (not (str/includes? result "\n\n\n")))
      (is (str/includes? result "(cell/defcell :order/validate"))))

  (testing "escapes doc quotes"
    (let [result (codegen/assemble-cell-source
                   {:cell-ns "example.cells.order"
                    :cell-id :order/validate
                    :doc "Validate \"special\" input"
                    :schema {:input [:map] :output [:map]}
                    :fn-body '(fn [resources data] data)})]
      ;; pr-str handles quoting automatically
      (is (str/includes? result (pr-str "Validate \"special\" input")))))

  (testing "dispatched output with lite-map sub-schemas: emits vector-form sub-schemas"
    ;; mycelium.cell/output-dispatched? requires (every? vector? (vals output))
    ;; — so per-transition sub-schemas in the emitted file MUST be vector-form
    ;; [:map [:k v] ...], not lite-map {:k v}. Otherwise defcell normalizes the
    ;; schema as a flat map and the workflow's schema-chain validator sees
    ;; :success/:failure as data keys.
    (let [result (codegen/assemble-cell-source
                   {:cell-ns "app.cells.validate-handle"
                    :cell-id :guestbook/validate-handle
                    :doc "Validates a handle."
                    :schema {:input  {:handle :string}
                             :output {:success {:validated-handle :string}
                                      :failure {:error :string}}}
                    :fn-body '(fn [_ {:keys [handle]}]
                                (if (seq handle)
                                  {:validated-handle handle}
                                  {:error "empty"}))})]
      (is (str/includes? result ":output {:success [:map [:validated-handle :string]]")
          "success sub-schema must render as a [:map ...] vector")
      (is (str/includes? result ":failure [:map [:error :string]]")
          "failure sub-schema must render as a [:map ...] vector")))

  (testing "dispatched output with already-vector sub-schemas: emitted unchanged"
    (let [result (codegen/assemble-cell-source
                   {:cell-ns "app.cells.x"
                    :cell-id :x/y
                    :doc "..."
                    :schema {:input  [:map [:n :int]]
                             :output {:success [:map [:n :int]]
                                      :failure [:map [:error :string]]}}
                    :fn-body '(fn [_ d] d)})]
      (is (str/includes? result ":success [:map [:n :int]]"))
      (is (str/includes? result ":failure [:map [:error :string]]"))))

  (testing "flat output schema: emitted as-is, not wrapped"
    (let [result (codegen/assemble-cell-source
                   {:cell-ns "app.cells.flat"
                    :cell-id :x/flat
                    :doc "..."
                    :schema {:input  {:n :int}
                             :output {:status :keyword}}
                    :fn-body '(fn [_ _] {:status :ok})})]
      (is (str/includes? result ":output {:status :keyword}"))
      (is (not (str/includes? result ":output [:map"))
          "flat output must not be normalised to vector form by codegen"))))

;; =============================================================
;; Test source assembly
;; =============================================================

(deftest assemble-test-source-test
  (testing "full test assembly"
    (let [result (codegen/assemble-test-source
                   {:test-ns "example.cells.order-test"
                    :cell-ns "example.cells.order"
                    :cell-id :order/validate
                    :test-body "(deftest test-basic\n  (is (= 1 1)))"})]
      (is (str/includes? result "(ns example.cells.order-test"))
      (is (str/includes? result "[clojure.test :refer [deftest is testing]]"))
      (is (str/includes? result "[example.cells.order]"))
      (is (str/includes? result "(def handler (:handler cell-spec))"))
      (is (str/includes? result "(def cell-spec (cell/get-cell! :order/validate))"))
      (is (str/includes? result "(defn approx="))
      (is (str/includes? result "(deftest test-basic"))))

  (testing "test ns requires next.jdbc.result-set as rs"
    ;; Tests for cells reading back from JDBC datasources need access to
    ;; rs/as-unqualified-maps via the builder-fn. The test-gen prompt
    ;; encourages this pattern; the test ns must already require it so
    ;; the LLM doesn't have to add the require itself.
    (let [result (codegen/assemble-test-source
                   {:test-ns "x.y-test"
                    :cell-ns "x.y"
                    :cell-id :x/y
                    :test-body "(deftest t (is true))"})]
      (is (str/includes? result "[next.jdbc.result-set :as rs]")))))

;; =============================================================
;; Manifest assembly
;; =============================================================

(deftest assemble-manifest-test
  (testing "simple linear manifest"
    (let [result (codegen/assemble-manifest
                   {:id :order/placement
                    :ns-prefix "order"
                    :steps [{:name "parse-request"
                             :doc "Parse incoming request"
                             :input-schema {:raw-request :map}
                             :output-schema {:order-id :string}}
                            {:name "validate"
                             :doc "Validate order"
                             :input-schema {:order-id :string}
                             :output-schema {:valid? :boolean}}]
                    :edges {"parse-request" ":validate"
                            "validate" ":end"}})]
      (is (str/includes? result ":order/placement"))
      (is (str/includes? result ":start"))
      (is (str/includes? result ":order/parse-request"))
      (is (str/includes? result ":order/validate")))))
