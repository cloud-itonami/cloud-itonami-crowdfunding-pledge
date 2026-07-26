(ns pledgeops.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [pledgeops.advisor :as advisor]
            [pledgeops.governor :as sut]
            [pledgeops.phase :as phase]
            [pledgeops.store :as store]))

(def ctx {:actor-id "pledge-test" :phase 3 :now "2026-08-20T00:00:00Z"})
(def after {:actor-id "pledge-test" :phase 3 :now "2026-10-01T00:00:00Z"})

(def a-request
  {:op :accept-pledge :campaign-id "cf-live" :pledge-id "pl-new"
   :patch {:backer "backer.two" :amount-minor 24800 :reward "standard"
           :ship-to :jp :placed-at "2026-08-20T00:00:00Z"}})

(defn- verdict
  ([st request] (verdict st request ctx))
  ([st request c] (sut/check request c (advisor/infer st request) st)))

(defn- rules [v] (set (map :rule (:violations v))))

;; ───────────────────────── the happy path ─────────────────────────

(deftest a-valid-pledge-commits-and-nothing-is-charged
  (let [st (store/seed-db)
        v  (verdict st a-request)]
    (is (true? (:ok? v)))
    (is (= [] (:violations v)))
    (is (= :commit (phase/verdict->disposition v)))
    (testing "and no op in the allowlist can capture a payment"
      (is (= #{:accept-pledge :change-pledge :cancel-pledge :flag-pledge-concern}
             sut/allowed-ops)))))

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (let [st (store/seed-db)]
    (doseq [req [a-request
                 {:op :cancel-pledge :campaign-id "cf-live" :pledge-id "pl-existing"}
                 {:op :change-pledge :campaign-id "cf-live" :pledge-id "pl-existing"
                  :patch {:amount-minor 32800 :reward "standard" :ship-to :jp}}
                 {:op :flag-pledge-concern :campaign-id "cf-live" :pledge-id "pl-existing"}]]
      (is (not (contains? (rules (verdict st req)) :scope-excluded))
          (str (:op req) " must not block itself")))))

;; ───────────────────────── hard checks ─────────────────────────

(deftest tier-availability-is-read-from-the-record-not-from-the-request
  (let [st (store/seed-db)
        v  (verdict st (assoc-in a-request [:patch :reward] "premium"))]
    (is (contains? (rules v) :reward-sold-out)
        "premium is limited to 1 and already claimed in the store")))

(deftest an-uncomputable-total-is-refused-and-reported-separately
  (let [st (store/seed-db)
        v  (verdict st (assoc-in a-request [:patch :add-ons]
                                 [{:add-on/reward "ghost" :add-on/qty 1}]))]
    (is (contains? (rules v) :total-uncomputable))
    (is (contains? (rules v) :unknown-add-on)
        "'we cannot price this' and 'that add-on does not exist' are different facts")))

(deftest a-closed-window-takes-no-new-pledges
  (let [st (store/seed-db)
        v  (verdict st (assoc a-request :campaign-id "cf-closed") after)]
    (is (contains? (rules v) :campaign-not-accepting-pledges))))

(deftest a-decided-campaign-takes-no-new-pledges-unless-late-is-enabled
  (let [st (store/seed-db)
        v  (verdict st (assoc a-request :campaign-id "cf-done") after)]
    (is (contains? (rules v) :campaign-not-accepting-pledges))))

(deftest accepting-an-existing-id-is-a-refusal-not-an-overwrite
  (let [st (store/seed-db)
        v  (verdict st (assoc a-request :pledge-id "pl-existing"))]
    (is (contains? (rules v) :duplicate-pledge-id))
    (is (= :authorized (:pledge/state (store/pledge-of st "pl-existing")))
        "and the existing commitment is untouched")))

(deftest cancelling-after-the-deadline-is-a-refund-question-not-a-cancellation
  (let [st (store/seed-db)
        v  (verdict st {:op :cancel-pledge :campaign-id "cf-live" :pledge-id "pl-existing"}
                    after)]
    (is (contains? (rules v) :outside-backer-window))))

(deftest an-effect-other-than-propose-is-a-claim-to-actuate
  (let [st (store/seed-db)
        p  (assoc (advisor/infer st a-request) :effect :execute)]
    (is (contains? (rules (sut/check {} ctx p st)) :effect-not-propose))))

(deftest claiming-to-have-charged-someone-is-permanently-blocked
  (let [st (store/seed-db)
        p  (advisor/infer st (assoc a-request :out-of-scope? true))]
    (is (contains? (rules (sut/check {} ctx p st)) :scope-excluded))))

(deftest ops-outside-the-allowlist-are-refused
  (let [st (store/seed-db)]
    (is (contains? (rules (sut/check {} ctx {:op :capture-payment :effect :propose} st))
                   :op-not-allowed))))

;; ───────────────────────── the right / favour distinction ─────────────────────────

(deftest a-backers-own-cancellation-is-a-right-and-does-not-queue-for-approval
  (let [st (store/seed-db)
        v  (verdict st {:op :cancel-pledge :campaign-id "cf-live" :pledge-id "pl-existing"
                        :patch {:initiated-by :backer}})]
    (is (false? (:high-stakes? v)))
    (is (= :commit (phase/verdict->disposition v))
        "routing a right through an approval queue is how a right becomes a favour")))

(deftest a-platform-initiated-cancellation-always-escalates
  (let [st (store/seed-db)
        v  (verdict st {:op :cancel-pledge :campaign-id "cf-live" :pledge-id "pl-existing"
                        :patch {:initiated-by :platform :reason :suspected-card-testing}})]
    (is (true? (:high-stakes? v)))
    (is (false? (:hard? v)))
    (is (= :escalate (phase/verdict->disposition v)))
    (testing "and the phase gate cannot undo that, because escalate is not commit"
      (is (= :escalate (:disposition (phase/gate 3 {:op :cancel-pledge} :escalate)))))))

;; ───────────────────────── soft gates and phases ─────────────────────────

(deftest low-confidence-escalates-without-being-a-violation
  (let [st (store/seed-db)
        p  (assoc (advisor/infer st a-request) :confidence 0.2)
        v  (sut/check {} ctx p st)]
    (is (false? (:hard? v)))
    (is (true? (:escalate? v)))))

(deftest the-concern-op-is-out-of-every-phase-auto-set
  (doseq [[p {:keys [auto]}] phase/phases
          op sut/always-escalate-ops]
    (is (not (contains? auto op))
        (str op " must never be auto-committable, including phase " p))))

(deftest a-governor-hold-survives-every-phase
  (doseq [p (keys phase/phases)]
    (is (= :hold (:disposition (phase/gate p {:op :accept-pledge} :hold))))))

(deftest early-phases-disable-writes-rather-than-quietly-allowing-them
  (is (= {:disposition :hold :reason :phase-disabled}
         (phase/gate 0 {:op :accept-pledge} :commit)))
  (is (= {:disposition :escalate :reason :phase-approval}
         (phase/gate 1 {:op :accept-pledge} :commit)))
  (is (= {:disposition :hold :reason :phase-disabled}
         (phase/gate 1 {:op :cancel-pledge} :commit)))
  (is (= {:disposition :commit :reason nil}
         (phase/gate 3 {:op :accept-pledge} :commit))))
