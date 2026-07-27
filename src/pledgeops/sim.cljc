(ns pledgeops.sim
  "Offline demo: accept a pledge, watch a sold-out tier and a closed
  window get refused, watch a backer cancel as of right, and watch a
  platform-initiated cancellation wait for a human.
  `clojure -M:dev:run`."
  (:require [langgraph.graph :as g]
            [pledgeops.operation :as operation]
            [pledgeops.store :as store]))

(def ^:private ctx {:actor-id "pledge-demo" :phase 3 :now "2026-08-20T00:00:00Z"})

(defn- run-req!
  ([actor tid request] (run-req! actor tid request ctx))
  ([actor tid request c] (g/run* actor {:request request :context c} {:thread-id tid})))

(defn -main [& _]
  (let [s (store/seed-db)
        actor (operation/build s)]

    (println "\n=== 1. 支援の受付（自動コミット。ただし請求はしない）===")
    (let [r (run-req! actor "sim-1"
                      {:op :accept-pledge :campaign-id "cf-live" :pledge-id "pl-1"
                       :patch {:backer "backer.two" :amount-minor 24800
                               :reward "standard" :ship-to :jp
                               :placed-at "2026-08-20T00:00:00Z"}})
          p (store/pledge-of s "pl-1")]
      (println "  status   :" (:status r))
      (println "  状態      :" (:pledge/state p) "（= 承認済みであって決済済みではない）")
      (println "  請求予定額 :" (get-in (last (store/pledge-log s)) [:value :total-minor])))

    (println "\n=== 2. 売り切れのリワード（HARD hold。在庫は記録から再導出）===")
    (let [r (run-req! actor "sim-2"
                      {:op :accept-pledge :campaign-id "cf-live" :pledge-id "pl-2"
                       :patch {:backer "backer.three" :amount-minor 32800
                               :reward "premium" :ship-to :jp}})]
      (println "  status     :" (:status r))
      (println "  violations :" (mapv :rule (:violations (last (store/ledger s))))))

    (println "\n=== 3. 締切後は新規支援を受け付けない ===")
    (let [r (run-req! actor "sim-3"
                      {:op :accept-pledge :campaign-id "cf-closed" :pledge-id "pl-3"
                       :patch {:backer "backer.four" :amount-minor 24800
                               :reward "standard" :ship-to :jp}}
                      (assoc ctx :now "2026-10-01T00:00:00Z"))]
      (println "  status     :" (:status r))
      (println "  violations :" (mapv :rule (:violations (last (store/ledger s))))))

    (println "\n=== 4. 支援者本人の取消は権利（承認待ちにしない）===")
    (let [r (run-req! actor "sim-4"
                      {:op :cancel-pledge :campaign-id "cf-live" :pledge-id "pl-existing"
                       :patch {:initiated-by :backer :at "2026-08-21T00:00:00Z"}})]
      (println "  status   :" (:status r))
      (println "  状態      :" (:pledge/state (store/pledge-of s "pl-existing"))))

    (println "\n=== 5. プラットフォーム主導の取消は必ず人間を通る ===")
    (let [held (run-req! actor "sim-5"
                         {:op :cancel-pledge :campaign-id "cf-live" :pledge-id "pl-1"
                          :patch {:initiated-by :platform :reason :suspected-card-testing
                                  :at "2026-08-22T00:00:00Z"}})]
      (println "  status   :" (:status held))
      (println "  状態      :" (:pledge/state (store/pledge-of s "pl-1")) "（承認前）")
      (let [ok (g/run* actor {:approval {:status :approved :by "risk-01"}}
                       {:thread-id "sim-5" :resume? true})]
        (println "  --- 人間 risk-01 が承認 ---")
        (println "  status   :" (:status ok))
        (println "  状態      :" (:pledge/state (store/pledge-of s "pl-1")))))

    (println "\n=== 6. build slot（部材 pass-through）は上限の合意が無いと受け付けない ===")
    (let [base {:op :accept-pledge :campaign-id "cf-slot" :pledge-id "sl-1"
                :patch {:backer "backer.slot" :amount-minor 100000
                        :reward "standard" :ship-to :jp
                        :placed-at "2026-08-20T00:00:00Z"}}
          r (run-req! actor "sim-6a" base)]
      (println "  上限なし    :" (:status r)
               (mapv :rule (:violations (last (store/ledger s))))))
    (let [ok (run-req! actor "sim-6b"
                       {:op :accept-pledge :campaign-id "cf-slot" :pledge-id "sl-1"
                        :patch {:backer "backer.slot" :amount-minor 100000
                                :reward "standard" :ship-to :jp
                                :placed-at "2026-08-20T00:00:00Z"
                                :quote {:deposit-minor 100000 :margin-minor 80000
                                        :cap-minor 600000 :estimate-minor 380000
                                        :basis-note "B70 x1, DDR5 128GB, 2TB NVMe — 2026-07 spot"}}})
          q  (store/quote-of s "sl-1")]
      (println "  上限あり    :" (:status ok))
      (println "  頭金/差益/上限:" (:quote/deposit-minor q) "/" (:quote/margin-minor q)
               "/" (:quote/cap-minor q)))

    (println "\n=== 監査台帳 ===")
    (doseq [f (store/ledger s)]
      (println " " (:t f) (:op f) (or (:basis f) "")))))
