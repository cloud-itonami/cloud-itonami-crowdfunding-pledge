(ns pledgeops.advisor
  "PledgeAdvisor — the *contained intelligence node* for the pledge actor.

  It drafts exactly four kinds of proposal from a closed allowlist:
  accepting a pledge, changing one, cancelling one, and flagging a
  concern.

  CRITICAL: it is a smart-but-untrusted advisor. Every proposal's
  `:effect` is always `:propose`, and — structurally, not by policy —
  nothing in this actor can take a payment: a pledge is an authorization
  and charging it belongs to a different repo behind a different governor.
  Every output is censored downstream by `pledgeops.governor`.

  Note what the advisor does NOT decide. Tier availability, shipping
  eligibility and the pledge total are computed by
  `crowdfunding.pledge` over the STORE's reward records, and the governor
  re-runs the same validation. An advisor cannot talk a sold-out tier into
  being available.

  Like every sibling actor's advisor this is a deterministic mock so the
  actor graph runs offline. In production this calls a real LLM with the
  same proposal shape."
  (:require [crowdfunding.pledge :as pledge]
            [pledgeops.store :as store]))

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn- propose-accept
  [st {:keys [campaign-id pledge-id] :as request}]
  (let [p     (store/draft-pledge st request)
        q     (store/draft-quote request)
        total (pledge/total-minor p (store/rewards st campaign-id))]
    {:op          :accept-pledge
     :pledge-id   pledge-id
     :campaign-id campaign-id
     :summary     (if q
                    (str pledge-id " の支援受付を提案: 頭金 " (:quote/deposit-minor q)
                         " / 上限 " (:quote/cap-minor q) " (build slot)")
                    (str pledge-id " の支援受付を提案: " (or total "算定不能")
                         " (" (or (:pledge/reward p) "リワードなし") ")"))
     :rationale   "支援の意思表示の記録のみ。決済は締切後に別のアクターが行う。"
     :cites       (vec (keep identity [campaign-id (:pledge/reward p)]))
     :effect      :propose
     :value       (cond-> {:pledge-id pledge-id :campaign-id campaign-id
                           :pledge p :total-minor total}
                    q (assoc :quote q))
     :confidence  0.92}))

(defn- propose-change
  [st {:keys [campaign-id pledge-id patch] :as request}]
  (let [prev (store/pledge-of st pledge-id)
        p    (merge prev (pledge/pledge (merge {:id pledge-id :campaign campaign-id}
                                               (select-keys patch
                                                            [:backer :amount-minor :reward
                                                             :add-ons :ship-to :placed-at]))))
        ;; A change may restate the quotation; if it does not, the one the
        ;; backer already agreed to still stands. Dropping it silently
        ;; would remove their cap as a side effect of changing tier.
        q    (or (store/draft-quote request) (store/quote-of st pledge-id))]
    {:op          :change-pledge
     :pledge-id   pledge-id
     :campaign-id campaign-id
     :summary     (str pledge-id " の変更を提案: " (:pledge/amount-minor prev)
                       " -> " (:pledge/amount-minor p))
     :rationale   "締切前の支援内容の差し替えのみ。旧リワードの在庫は同時に戻す。"
     :cites       [pledge-id]
     :effect      :propose
     :value       (cond-> {:pledge-id pledge-id :campaign-id campaign-id :pledge p}
                    q (assoc :quote q))
     :confidence  0.88}))

(defn- propose-cancel
  "Draft a cancellation. A BACKER-initiated cancellation inside the window
  is a right, not a favour, and is auto-eligible. A PLATFORM-initiated one
  removes someone's pledge on the platform's own initiative, and always
  escalates — see `pledgeops.governor/platform-initiated?`."
  [_st {:keys [campaign-id pledge-id patch]}]
  (let [by (:initiated-by patch :backer)]
    {:op          :cancel-pledge
     :pledge-id   pledge-id
     :campaign-id campaign-id
     :summary     (str pledge-id " の取消を提案 (" (name by) ")")
     :rationale   (if (= :backer by)
                    "締切前の支援者による取消の記録のみ。締切後は取消でなく返金の判断になる。"
                    "プラットフォーム主導の取消の承認依頼。人間の裁定を要する。")
     :cites       [pledge-id]
     :effect      :propose
     :value       {:pledge-id pledge-id :campaign-id campaign-id
                   :initiated-by by :reason (:reason patch) :at (:at patch)}
     :confidence  (or (:confidence patch) 0.9)}))

(defn- propose-concern
  [_st {:keys [campaign-id pledge-id patch]}]
  {:op          :flag-pledge-concern
   :pledge-id   pledge-id
   :campaign-id campaign-id
   :summary     (str (or pledge-id campaign-id) " に関する懸念フラグ: "
                     (pr-str (:concern patch "unknown")))
   :rationale   "観察された懸念事実の報告のみ。取消・返金・請求の判断は行わない。"
   :cites       (vec (keep identity [campaign-id pledge-id]))
   :effect      :propose
   :value       (merge {:campaign-id campaign-id :pledge-id pledge-id} patch)
   :confidence  (or (:confidence patch) 0.78)})

(defn infer
  [st {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :accept-pledge       (propose-accept st request)
                   :change-pledge       (propose-change st request)
                   :cancel-pledge       (propose-cancel st request)
                   :flag-pledge-concern (propose-concern st request)
                   {})]
    ;; Test hook: inject scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Clear before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str
              " -- actually charged the backer and captured the payment")
      proposal)))

(defn trace [_request proposal]
  {:t           :advisor-proposal
   :op          (:op proposal)
   :pledge-id   (:pledge-id proposal)
   :campaign-id (:campaign-id proposal)
   :summary     (:summary proposal)
   :confidence  (:confidence proposal)})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request]
      (infer store request))))
