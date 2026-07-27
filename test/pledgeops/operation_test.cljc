(ns pledgeops.operation-test
  (:require [clojure.test :refer [deftest is testing]]
            [crowdfunding.reward :as reward]
            [langgraph.graph :as g]
            [pledgeops.operation :as operation]
            [pledgeops.store :as store]))

(def ctx {:actor-id "pledge-test" :phase 3 :now "2026-08-20T00:00:00Z"})

(defn- run-req!
  ([actor tid request] (run-req! actor tid request ctx))
  ([actor tid request c] (g/run* actor {:request request :context c} {:thread-id tid})))

(def accept
  {:op :accept-pledge :campaign-id "cf-live" :pledge-id "pl-new"
   :patch {:backer "backer.two" :amount-minor 24800 :reward "standard"
           :ship-to :jp :placed-at "2026-08-20T00:00:00Z"}})

(defn- tier [s cid id]
  (first (filter #(= id (:reward/id %)) (store/rewards s cid))))

(deftest accepting-a-pledge-commits-an-authorization-and-claims-a-tier
  (let [s (store/seed-db)
        a (operation/build s)
        r (run-req! a "t1" accept)]
    (is (= :commit (:disposition (:state r))))
    (is (= :authorized (:pledge/state (store/pledge-of s "pl-new")))
        "authorized, not collected — no money has moved")
    (is (= 1 (:reward/claimed (tier s "cf-live" "standard")))
        "scarcity is taken through reward/claim, never by incrementing a counter")))

(deftest cancelling-returns-the-tier-to-the-pool
  (let [s (store/seed-db)
        a (operation/build s)]
    (run-req! a "t2" accept)
    (is (= 1 (:reward/claimed (tier s "cf-live" "standard"))))
    (let [r (run-req! a "t3" {:op :cancel-pledge :campaign-id "cf-live"
                              :pledge-id "pl-new"
                              :patch {:initiated-by :backer :at "2026-08-21T00:00:00Z"}})]
      (is (= :commit (:disposition (:state r))))
      (is (= :cancelled (:pledge/state (store/pledge-of s "pl-new"))))
      (is (zero? (:reward/claimed (tier s "cf-live" "standard")))
          "a campaign that sold out to backers who never paid is selling phantoms"))))

(deftest a-platform-cancellation-interrupts-and-a-named-human-completes-it
  (let [s (store/seed-db)
        a (operation/build s)
        held (run-req! a "t4" {:op :cancel-pledge :campaign-id "cf-live"
                               :pledge-id "pl-existing"
                               :patch {:initiated-by :platform
                                       :reason :suspected-card-testing
                                       :at "2026-08-21T00:00:00Z"}})]
    (is (= :authorized (:pledge/state (store/pledge-of s "pl-existing")))
        "nothing committed before approval")
    (is (some #(= :approval-requested (:t %)) (:audit (:state held))))
    (let [ok (g/run* a {:approval {:status :approved :by "risk-01"}}
                     {:thread-id "t4" :resume? true})]
      (is (= :commit (:disposition (:state ok))))
      (is (= :cancelled (:pledge/state (store/pledge-of s "pl-existing"))))
      (is (some #(and (= :approval-granted (:t %)) (= "risk-01" (:by %)))
                (:audit (:state ok)))))))

(deftest a-sold-out-tier-never-reaches-the-interrupt
  (let [s (store/seed-db)
        a (operation/build s)
        r (run-req! a "t5" (assoc-in accept [:patch :reward] "premium"))]
    (is (= :hold (:disposition (:state r))))
    (is (not-any? #(= :approval-requested (:t %)) (:audit (:state r))))
    (is (nil? (store/pledge-of s "pl-new")))
    (is (contains? (set (:basis (last (store/ledger s)))) :reward-sold-out))))

(deftest changing-a-pledge-moves-the-claim-with-it
  (let [s (store/mem-store
           (assoc-in (store/demo-data) [:rewards "cf-live"]
                     [(reward/reward {:id "standard" :title "Standard" :minimum-minor 24800
                                      :limit 10 :claimed 1 :estimated-delivery "2027-04-01"
                                      :shipping {:jp 800} :ships-to #{:jp}})
                      (reward/reward {:id "deluxe" :title "Deluxe" :minimum-minor 40000
                                      :limit 10 :estimated-delivery "2027-05-01"
                                      :shipping {:jp 800} :ships-to #{:jp}})]))
        a (operation/build s)
        r (run-req! a "t6" {:op :change-pledge :campaign-id "cf-live"
                            :pledge-id "pl-existing"
                            :patch {:backer "backer.one" :amount-minor 40000
                                    :reward "deluxe" :ship-to :jp
                                    :placed-at "2026-08-10T00:00:00Z"}})]
    (is (= :commit (:disposition (:state r))))
    (is (= "deluxe" (:pledge/reward (store/pledge-of s "pl-existing"))))
    (is (zero? (:reward/claimed (tier s "cf-live" "standard"))) "old tier released")
    (is (= 1 (:reward/claimed (tier s "cf-live" "deluxe"))) "new tier claimed")))

(deftest the-ledger-records-holds-with-a-stated-basis
  (let [s (store/seed-db)
        a (operation/build s)]
    (run-req! a "t7" (assoc accept :campaign-id "cf-closed")
              (assoc ctx :now "2026-10-01T00:00:00Z"))
    (let [f (last (store/ledger s))]
      (is (= :governor-hold (:t f)))
      (is (seq (:violations f))))))

(deftest phase-zero-writes-nothing
  (let [s (store/seed-db)
        a (operation/build s)
        r (run-req! a "t8" accept (assoc ctx :phase 0))]
    (is (= :hold (:disposition (:state r))))
    (is (nil? (store/pledge-of s "pl-new")))))

(deftest a-pass-through-pledge-carries-its-cap-into-the-store
  (let [s (store/seed-db)
        a (operation/build s)
        req {:op :accept-pledge :campaign-id "cf-slot" :pledge-id "sl-1"
             :patch {:backer "backer.slot" :amount-minor 100000 :reward "standard"
                     :ship-to :jp :placed-at "2026-08-20T00:00:00Z"
                     :quote {:deposit-minor 100000 :margin-minor 80000
                             :cap-minor 600000 :estimate-minor 380000
                             :basis-note "B70 x1, DDR5 128GB — 2026-07 spot"}}}
        r (run-req! a "t9" req)]
    (is (= :commit (:disposition (:state r))))
    (is (= 600000 (:quote/cap-minor (store/quote-of s "sl-1")))
        "the cap is stored with the pledge, because it is what THIS backer agreed to")
    (testing "and the same request without a quote never reaches the store"
      (let [r2 (run-req! a "t10" (-> req
                                     (assoc :pledge-id "sl-2")
                                     (update :patch dissoc :quote)))]
        (is (= :hold (:disposition (:state r2))))
        (is (nil? (store/pledge-of s "sl-2")))
        (is (nil? (store/quote-of s "sl-2")))
        (is (contains? (set (:basis (last (store/ledger s)))) :missing-quote))))))

(deftest testing-note-this-actor-has-no-charge-path-at-all
  (testing "not policy — there is no op, no rail and no store field for it"
    (is (not-any? #{:charge-pledge :capture-payment :collect}
                  (keys (store/demo-data))))))
