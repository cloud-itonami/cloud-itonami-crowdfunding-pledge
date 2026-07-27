(ns pledgeops.store
  "SSoT for the pledge actor — who committed what to which campaign.

  Directories, all keyed by STRING ids (never keywords):

    campaigns  campaign id -> campaign record. Read for its state and
               deadline; this actor never writes one.
    rewards    campaign id -> reward tiers. Tier availability is read
               FROM HERE and never from a request, which is what makes
               'this tier is sold out' ground truth rather than a claim.
    pledges    pledge id -> pledge record.
    quotes     pledge id -> the pass-through quotation the backer agreed
               to, on `:deposit-plus-settlement` campaigns. A pledge on
               such a campaign with no quote here has no deposit, no
               margin and no CAP — the governor refuses it, because that
               is an unbounded commitment wearing the shape of a bounded
               one.

  This store holds no money, and that is the point of the whole actor: a
  pledge is an AUTHORIZATION. `crowdfunding.collection` charges it later,
  in a different repo, behind a different governor. Nothing here can take
  a payment, and no phase of this actor makes that possible.

  The ledger stays append-only."
  (:require [crowdfunding.campaign :as cf]
            [crowdfunding.passthrough :as passthrough]
            [crowdfunding.pledge :as pledge]
            [crowdfunding.reward :as reward]))

(defprotocol Store
  (campaign-of [s id] "Campaign record, or nil.")
  (rewards [s campaign-id] "Reward tiers for a campaign.")
  (pledge-of [s id] "Pledge record, or nil.")
  (pledges-for [s campaign-id] "Every pledge against a campaign.")
  (all-pledges [s])
  (quote-of [s pledge-id] "The pass-through quotation a backer agreed to, or nil.")
  (ledger [s])
  (pledge-log [s])
  (commit-record! [s record])
  (append-ledger! [s fact]))

;; ----------------------------- demo data -----------------------------

(def ^:private risks
  (str "Tooling is not finalised and the moulder quoted six weeks that "
       "could slip. The controller is single-source; a shortage delays "
       "every tier."))

(defn- a-campaign [id state]
  (assoc (cf/campaign {:id id :creator "creator.alpha" :title (str id)
                       :currency "JPY" :goal-minor 3000000 :category :technology
                       :duration-days 45 :launched-at "2026-08-01T00:00:00Z"
                       :deadline "2026-09-15T00:00:00Z"
                       :story "A split keyboard." :risks risks
                       :ships-to #{:jp :rest-of-world}})
         :campaign/state state))

(defn demo-data
  "Fixtures covering the happy path and each hard check.

    cf-live    live, inside the window       -> pledges accepted
    cf-closed  live but past its deadline    -> refuses new pledges
    cf-done    funding-succeeded, no late    -> refuses new pledges
    cf-slot    live, :deposit-plus-settlement -> every pledge needs a
               quotation, because a pass-through pledge without one has
               no cap at all

    tiers: `standard` unlimited, `premium` limited to 1 and already
    claimed (so a request naming it is refused from the RECORD, not from
    the request's own claim about availability)."
  []
  {:campaigns {"cf-live"   (a-campaign "cf-live" :live)
               "cf-closed" (a-campaign "cf-closed" :live)
               "cf-done"   (a-campaign "cf-done" :funding-succeeded)
               "cf-slot"   (assoc (a-campaign "cf-slot" :live)
                                  :campaign/pricing-model :deposit-plus-settlement)}
   :rewards
   (let [tiers [(reward/reward {:id "standard" :title "Standard" :minimum-minor 24800
                                :estimated-delivery "2027-04-01"
                                :shipping {:jp 800 :rest-of-world 3000}
                                :ships-to #{:jp :rest-of-world}})
                (reward/reward {:id "premium" :title "Premium" :minimum-minor 32800
                                :limit 1 :claimed 1 :estimated-delivery "2027-06-01"
                                :shipping {:jp 800} :ships-to #{:jp}})
                (reward/reward {:id "wrist-rest" :title "Wrist rest" :minimum-minor 3000
                                :add-on? true :estimated-delivery "2027-04-01"
                                :ships-to #{:jp :rest-of-world}})]]
     {"cf-live" tiers "cf-closed" tiers "cf-done" tiers "cf-slot" tiers})
   :pledges
   {"pl-existing" (pledge/pledge {:id "pl-existing" :campaign "cf-live"
                                  :backer "backer.one" :amount-minor 24800
                                  :reward "standard" :ship-to :jp
                                  :placed-at "2026-08-10T00:00:00Z"})}
   :quotes {}})

(def demo-quote
  "A well-formed pass-through quotation, in the MK-1 shape of
  ADR-2607268000: a build-slot deposit, a fixed margin in currency, and a
  cap the backer agreed to."
  (passthrough/quotation {:deposit-minor  100000
                          :margin-minor   80000
                          :cap-minor      600000
                          :estimate-minor 380000
                          :basis-note     "B70 x1, DDR5 128GB, 2TB NVMe — 2026-07 spot"}))

;; ----------------------------- MemStore -----------------------------

(defrecord MemStore [a]
  Store
  (campaign-of [_ id] (get-in @a [:campaigns id]))
  (rewards [_ id] (get-in @a [:rewards id] []))
  (pledge-of [_ id] (get-in @a [:pledges id]))
  (pledges-for [_ cid] (->> (vals (:pledges @a))
                            (filter #(= cid (:pledge/campaign %)))
                            (sort-by :pledge/id)
                            vec))
  (all-pledges [_] (sort-by :pledge/id (vals (:pledges @a))))
  (quote-of [_ id] (get-in @a [:quotes id]))
  (ledger [_] (:ledger @a))
  (pledge-log [_] (:pledge-log @a))
  (commit-record! [_ record]
    (swap! a update :pledge-log conj record)
    (let [{:keys [op value]} record
          p (:pledge value)]
      (case op
        ;; Accepting a pledge CLAIMS a unit of the tier. Claiming through
        ;; `crowdfunding.reward/claim` rather than by incrementing a
        ;; counter here is what keeps `:oversold` an invariant violation
        ;; to assert on instead of a state the system routinely reaches.
        :accept-pledge
        (when p
          (swap! a
                 (fn [m]
                   (let [cid (:pledge/campaign p)
                         rs  (get-in m [:rewards cid] [])
                         rid (:pledge/reward p)
                         rs' (mapv (fn [r]
                                     (if (and rid (= rid (:reward/id r)))
                                       (or (reward/claim r (:pledge/ship-to p)) r)
                                       r))
                                   rs)]
                     (cond-> m
                       true (assoc-in [:pledges (:pledge/id p)] p)
                       true (assoc-in [:rewards cid] rs')
                       ;; The quotation is stored with the pledge, not with
                       ;; the campaign: the cap is what THIS backer agreed
                       ;; to, and a later backer's cap is a different
                       ;; agreement even on the same campaign.
                       (:quote value) (assoc-in [:quotes (:pledge/id p)]
                                                (:quote value)))))))

        ;; A change is a cancel plus a new pledge, so the old tier's
        ;; scarcity goes back to the pool. A campaign that sold out to
        ;; backers who then changed tier would be selling phantoms.
        :change-pledge
        (when p
          (swap! a
                 (fn [m]
                   (let [cid  (:pledge/campaign p)
                         prev (get-in m [:pledges (:pledge/id p)])
                         rs   (get-in m [:rewards cid] [])
                         rs'  (mapv (fn [r]
                                      (cond-> r
                                        (and (:pledge/reward prev)
                                             (= (:pledge/reward prev) (:reward/id r)))
                                        (as-> r* (or (reward/release r*) r*))

                                        (and (:pledge/reward p)
                                             (= (:pledge/reward p) (:reward/id r)))
                                        (as-> r* (or (reward/claim r* (:pledge/ship-to p)) r*))))
                                    rs)]
                     (cond-> m
                       true (assoc-in [:pledges (:pledge/id p)] p)
                       true (assoc-in [:rewards cid] rs')
                       (:quote value) (assoc-in [:quotes (:pledge/id p)]
                                                (:quote value)))))))

        :cancel-pledge
        (swap! a
               (fn [m]
                 (let [id   (:pledge-id value)
                       prev (get-in m [:pledges id])
                       cid  (:pledge/campaign prev)
                       rs   (get-in m [:rewards cid] [])
                       rs'  (mapv (fn [r]
                                    (if (and prev (= (:pledge/reward prev) (:reward/id r)))
                                      (or (reward/release r) r)
                                      r))
                                  rs)]
                   (cond-> m
                     prev (-> (assoc-in [:pledges id]
                                        (assoc prev :pledge/state :cancelled
                                               :pledge/cancelled-at (:at value)))
                              (assoc-in [:rewards cid] rs'))))))
        nil))
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact))

(defn seed-db []
  (->MemStore (atom (assoc (demo-data) :ledger [] :pledge-log []))))

(defn mem-store [m]
  (->MemStore (atom (merge {:campaigns {} :rewards {} :pledges {} :quotes {}
                            :ledger [] :pledge-log []}
                           m))))

;; ----------------------------- derived views -----------------------------

(defn draft-pledge
  "Build the pledge a request describes, without committing it."
  [_s {:keys [pledge-id campaign-id patch]}]
  (pledge/pledge (merge {:id pledge-id :campaign campaign-id}
                        (dissoc patch :quote :initiated-by :reason :confidence))))

(defn draft-quote
  "The quotation a request carries, if any. Built through
  `passthrough/quotation` rather than passed through raw, so a request
  cannot smuggle extra keys onto the record a backer's cap lives on."
  [{:keys [patch]}]
  (when-let [q (:quote patch)]
    (passthrough/quotation q)))

(defn pass-through?
  "Does this campaign price by deposit plus settlement?"
  [s campaign-id]
  (= :deposit-plus-settlement
     (:campaign/pricing-model (campaign-of s campaign-id) :fixed)))
