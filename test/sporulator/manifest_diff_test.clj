(ns sporulator.manifest-diff-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [sporulator.manifest-diff :as diff]))

;; =============================================================
;; Fixture manifests
;; =============================================================

(def empty-manifest
  {:id :guestbook/submit
   :pipeline []
   :cells {}})

(def linear-manifest
  "Original guestbook: validate-handle → validate-message → persist-entry → confirm."
  {:id :guestbook/submit
   :pipeline [:start :vh :vm :pe :bc]
   :cells
   {:start {:id :guestbook/accept-input
            :doc "Accepts the initial handle and message from the user."
            :schema {:input  {:handle :string :message :string}
                     :output {:handle :string :message :string}}
            :on-error nil
            :requires []}
    :vh    {:id :guestbook/validate-handle
            :doc "Validates that handle is non-empty and only alphanumeric."
            :schema {:input  {:handle :string}
                     :output {:validated-handle :string}}
            :on-error nil
            :requires []}
    :vm    {:id :guestbook/validate-message
            :doc "Validates message is non-empty and ≤500 chars."
            :schema {:input  {:message :string}
                     :output {:validated-message :string}}
            :on-error nil
            :requires []}
    :pe    {:id :guestbook/persist-entry
            :doc "Persists handle/message via :db, returns id."
            :schema {:input  {:validated-handle :string :validated-message :string}
                     :output {:id :int}}
            :on-error nil
            :requires [:db]}
    :bc    {:id :guestbook/build-confirmation
            :doc "Returns id + preview map."
            :schema {:input  {:id :int :validated-message :string}
                     :output {:id :int :preview :string}}
            :on-error nil
            :requires []}}})

(def with-error-branches
  "Same workflow with three error cells added and validators dispatching
   on :success / :failure."
  {:id :guestbook/submit
   :pipeline []
   :edges {:start :vh
           :vh    {:success :vm :failure :he}
           :vm    {:success :pe :failure :me}
           :pe    {:success :bc :failure :se}}
   :cells
   {:start {:id :guestbook/accept-input
            :doc "Accepts the initial handle and message from the user."
            :schema {:input  {:handle :string :message :string}
                     :output {:handle :string :message :string}}
            :on-error nil
            :requires []}
    :vh    {:id :guestbook/validate-handle
            :doc "Validates handle, dispatches success/failure."
            :schema {:input  {:handle :string}
                     :output {:success {:validated-handle :string}
                              :failure {:error :string}}}
            :on-error nil
            :requires []}
    :vm    {:id :guestbook/validate-message
            :doc "Validates message, dispatches success/failure."
            :schema {:input  {:message :string}
                     :output {:success {:validated-message :string}
                              :failure {:error :string}}}
            :on-error nil
            :requires []}
    :pe    {:id :guestbook/persist-entry
            :doc "Persists handle/message via :db, dispatches success/failure."
            :schema {:input  {:validated-handle :string :validated-message :string}
                     :output {:success {:id :int}
                              :failure {:error :string}}}
            :on-error nil
            :requires [:db]}
    :bc    {:id :guestbook/build-confirmation
            :doc "Returns id + preview map."
            :schema {:input  {:id :int :validated-message :string}
                     :output {:id :int :preview :string}}
            :on-error nil
            :requires []}
    :he    {:id :guestbook/handle-error
            :doc "Formats handle validation error for response."
            :schema {:input  {:error :string}
                     :output {:error :string :status :keyword}}
            :on-error nil
            :requires []}
    :me    {:id :guestbook/message-error
            :doc "Formats message validation error for response."
            :schema {:input  {:error :string}
                     :output {:error :string :status :keyword}}
            :on-error nil
            :requires []}
    :se    {:id :guestbook/store-error
            :doc "Formats database error for response."
            :schema {:input  {:error :string}
                     :output {:error :string :status :keyword}}
            :on-error nil
            :requires []}}})

;; =============================================================
;; cells-by-id
;; =============================================================

(deftest cells-by-id-keys-on-cell-id
  (let [m (diff/cells-by-id linear-manifest)]
    (is (= 5 (count m)))
    (is (contains? m :guestbook/accept-input))
    (is (contains? m :guestbook/validate-handle))
    (is (= :guestbook/persist-entry (-> m :guestbook/persist-entry :id)))))

(deftest cells-by-id-empty-or-nil
  (is (= {} (diff/cells-by-id nil)))
  (is (= {} (diff/cells-by-id {:cells {}}))))

;; =============================================================
;; classify
;; =============================================================

(deftest classify-added
  (is (= :added
         (diff/classify nil
                        {:id :x :schema {:input {:a :int}}}))))

(deftest classify-removed
  (is (= :removed
         (diff/classify {:id :x :schema {:input {:a :int}}}
                        nil))))

(deftest classify-unchanged
  (let [c {:id :x :schema {:input {:a :int}} :doc "..." :requires [:db]}]
    (is (= :unchanged (diff/classify c c)))
    (is (= :unchanged (diff/classify c (assoc c :on-error :somewhere))))
    (is (= :unchanged (diff/classify (dissoc c :requires) (assoc c :requires []))))
    (is (= :unchanged (diff/classify (dissoc c :doc) (assoc c :doc ""))))))

(deftest classify-schema-changed
  (let [old {:id :x :schema {:input {:a :int}} :doc "d"}
        new (assoc old :schema {:input {:a :string}})]
    (is (= :schema-changed (diff/classify old new)))))

(deftest classify-requires-counts-as-schema-change
  (let [old {:id :x :schema {:input {:a :int}} :doc "d" :requires []}
        new (assoc old :requires [:db])]
    (is (= :schema-changed (diff/classify old new))
        "requires change implies different handler destructuring → schema-changed")))

(deftest classify-doc-changed
  (let [old {:id :x :schema {:input {:a :int}} :doc "old" :requires [:db]}
        new (assoc old :doc "new")]
    (is (= :doc-changed (diff/classify old new)))))

;; =============================================================
;; diff
;; =============================================================

(deftest diff-empty-vs-empty
  (let [d (diff/diff empty-manifest empty-manifest)]
    (is (diff/empty-diff? d))
    (is (= [] (:added d) (:removed d) (:schema-changed d) (:doc-changed d) (:unchanged d)))))

(deftest diff-fresh-build-from-empty
  (let [d (diff/diff empty-manifest linear-manifest)]
    (is (= 5 (count (:added d))))
    (is (= [] (:removed d)))
    (is (= [] (:schema-changed d)))
    (is (= [] (:doc-changed d)))
    (is (= [] (:unchanged d)))))

(deftest diff-nil-old-treated-as-empty
  (let [d (diff/diff nil linear-manifest)]
    (is (= 5 (count (:added d))))))

(deftest diff-identical-manifests
  (let [d (diff/diff linear-manifest linear-manifest)]
    (is (diff/empty-diff? d))
    (is (= 5 (count (:unchanged d))))))

(deftest diff-add-error-branches
  (testing "linear → with-error-branches: 3 new error cells, 3 validators schema-changed, 2 unchanged"
    (let [d (diff/diff linear-manifest with-error-branches)]
      (is (= [:guestbook/handle-error :guestbook/message-error :guestbook/store-error]
             (:added d)))
      (is (= [:guestbook/persist-entry :guestbook/validate-handle :guestbook/validate-message]
             (:schema-changed d)))
      (is (= [:guestbook/accept-input :guestbook/build-confirmation]
             (:unchanged d)))
      (is (= [] (:removed d)))
      (is (= [] (:doc-changed d))))))

(deftest diff-remove-cell
  (let [smaller (update linear-manifest :cells dissoc :bc)
        d (diff/diff linear-manifest smaller)]
    (is (= [:guestbook/build-confirmation] (:removed d)))
    (is (= [] (:added d)))
    (is (= 4 (count (:unchanged d))))))

(deftest diff-step-rename-with-stable-cell-id
  (testing "step name change but same cell-id → unchanged"
    (let [renamed (-> linear-manifest
                      (update :cells dissoc :vh)
                      (assoc-in [:cells :check-handle]
                                (get-in linear-manifest [:cells :vh])))
          d (diff/diff linear-manifest renamed)]
      (is (diff/empty-diff? d))
      (is (= 5 (count (:unchanged d)))))))

(deftest diff-cell-id-swap-at-same-step
  (testing "swap cell-id under a stable step name → remove + add"
    (let [swapped (assoc-in linear-manifest [:cells :vh :id] :guestbook/check-handle)
          d (diff/diff linear-manifest swapped)]
      (is (= [:guestbook/check-handle] (:added d)))
      (is (= [:guestbook/validate-handle] (:removed d))))))

(deftest diff-on-error-only-change
  (testing "changing :on-error doesn't touch the cell contract"
    (let [m2 (assoc-in linear-manifest [:cells :vh :on-error] :he)
          d (diff/diff linear-manifest m2)]
      (is (diff/empty-diff? d)))))

(deftest diff-edges-only-change
  (testing "changing edges/pipeline doesn't trigger cell regen"
    (let [m2 (assoc linear-manifest :pipeline [:start :vm :vh :pe :bc])
          d (diff/diff linear-manifest m2)]
      (is (diff/empty-diff? d)))))

(deftest diff-doc-only-change
  (let [m2 (assoc-in linear-manifest [:cells :bc :doc]
                     "Returns id + a longer preview string of the message.")
        d (diff/diff linear-manifest m2)]
    (is (= [:guestbook/build-confirmation] (:doc-changed d)))
    (is (= [] (:schema-changed d)))
    (is (= 4 (count (:unchanged d))))))

(deftest diff-mixed-changes
  (let [m2 (-> linear-manifest
               ;; Add a new cell
               (assoc-in [:cells :extra] {:id :guestbook/extra
                                          :doc "..."
                                          :schema {:input {} :output {}}
                                          :on-error nil :requires []})
               ;; Remove one
               (update :cells dissoc :bc)
               ;; Change schema
               (assoc-in [:cells :vh :schema :output] {:success {:vh :string}
                                                       :failure {:error :string}})
               ;; Doc-only change
               (assoc-in [:cells :pe :doc] "Persists. (revised)"))
        d (diff/diff linear-manifest m2)]
    (is (= [:guestbook/extra] (:added d)))
    (is (= [:guestbook/build-confirmation] (:removed d)))
    (is (= [:guestbook/validate-handle] (:schema-changed d)))
    (is (= [:guestbook/persist-entry] (:doc-changed d)))
    (is (= [:guestbook/accept-input :guestbook/validate-message] (:unchanged d)))))

;; =============================================================
;; affected-cells
;; =============================================================

(deftest affected-cells-buckets
  (let [d {:added           [:a]
           :schema-changed  [:b]
           :doc-changed     [:c]
           :unchanged       [:d]
           :removed         [:e]}
        a (diff/affected-cells d)]
    (is (= [:a :b]                  (:regen-tests-and-impl a)))
    (is (= [:c]                     (:regen-impl-only a)))
    (is (= [:d]                     (:carry-over a)))
    (is (= [:e]                     (:delete a)))))

;; =============================================================
;; format-diff
;; =============================================================

(deftest format-diff-renders-each-section
  (let [d (diff/diff linear-manifest with-error-branches)
        out (diff/format-diff d)]
    (is (str/includes? out "Workflow diff:"))
    (is (str/includes? out "+ 3 added"))
    (is (str/includes? out "~ 3 schema changed"))
    (is (str/includes? out "= 2 unchanged"))
    (is (not (str/includes? out "removed")))))

(deftest format-diff-says-no-changes-when-empty
  (let [out (diff/format-diff (diff/diff linear-manifest linear-manifest))]
    (is (str/includes? out "(no changes)"))))

;; =============================================================
;; change-summary
;; =============================================================

(deftest change-summary-nil-for-trivial-classes
  (is (nil? (diff/change-summary nil {:id :x})))
  (is (nil? (diff/change-summary {:id :x} nil)))
  (is (nil? (diff/change-summary {:id :x :doc "d"} {:id :x :doc "d"}))))

(deftest change-summary-describes-schema-delta
  (let [old (get-in linear-manifest [:cells :vh])
        new (get-in with-error-branches [:cells :vh])
        summary (diff/change-summary old new)]
    (is (str/includes? summary "schema:"))
    (is (str/includes? summary "previous:"))
    (is (str/includes? summary "new:"))
    (is (str/includes? summary "doc:"))))

(deftest change-summary-describes-requires-delta
  (let [old {:id :x :schema {:input {} :output {}} :doc "d" :requires []}
        new (assoc old :requires [:db])
        summary (diff/change-summary old new)]
    (is (str/includes? summary "requires:"))
    (is (str/includes? summary "previous: []"))
    (is (str/includes? summary "new:      [:db]"))))
