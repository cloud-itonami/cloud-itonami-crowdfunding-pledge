(ns pledgeops.governor
  "PledgeGovernor — the independent compliance layer standing between a
  proposed pledge and a campaign's funding total.

  The advisor has no notion of whether the tier it wants to give away is
  still available, whether the campaign is still taking pledges, whether
  the total it computed is even computable, or whether its own `:effect`
  secretly claims to have charged someone. So this MUST be a separate
  system able to *reject* a proposal and fall back to HOLD.

  ## Nothing here takes a payment

  A pledge is an AUTHORIZATION. This actor cannot charge a backer — not
  as policy but structurally: it has no rail, no charge op, and
  `crowdfunding.collection` (a separate repo, a separate governor) owns
  the moment money moves. Any proposal CLAIMING to have charged someone is
  a permanent scope exclusion.

  Seven HARD checks, ALL permanent, un-overridable by any human approval:

    1. Pledge invalid       -- `crowdfunding.pledge/pledge-errors`, run
                               against the STORE's campaign and reward
                               records. This is where 'that tier is sold
                               out' and 'we do not ship there' become
                               ground truth rather than the request's
                               claim about itself.
    2. Uncomputable total   -- a pledge whose total cannot be computed
                               must never be recorded. A total that is
                               nil today is a charge invented later.
    3. Pricing-model mismatch -- a pledge on a `:deposit-plus-settlement`
                               campaign with no quotation has no cap at
                               all; a quotation on a `:fixed` campaign is a
                               backer who thinks they have one. Neither is
                               visible from the campaign or the pledge
                               alone.
    4. Duplicate pledge id  -- accepting an id that already exists would
                               overwrite a backer's commitment. Idempotency
                               is a refusal, not an overwrite.
    5. Outside the window   -- cancelling or changing after the deadline.
                               The outcome was decided using this pledge;
                               after that point the question is a REFUND,
                               which is a different actor's decision.
    6. Effect not :propose  -- any other value is a claim to directly
                               actuate outside governance.
    7. Scope exclusion      -- any claim to have charged, captured or
                               refunded, plus any op outside the closed
                               allowlist.

  Two ESCALATE (SOFT) gates:
    - LLM confidence below the floor.
    - `:flag-pledge-concern` always escalates, and so does any
      PLATFORM-initiated cancellation. A backer cancelling their own
      pledge inside the window is exercising a right and needs no human;
      the platform removing someone's pledge on its own initiative is a
      different act wearing the same op, and `platform-initiated?` is what
      separates them."
  (:require [kotoba.lang.text :as str]
            [crowdfunding.passthrough :as passthrough]
            [crowdfunding.pledge :as pledge]
            [pledgeops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist. CRITICAL: no op that captures a
  payment is EVER a member — such an op would be a permanent scope
  violation, not merely un-implemented."
  #{:accept-pledge :change-pledge :cancel-pledge :flag-pledge-concern})

(def always-escalate-ops #{:flag-pledge-concern})

(defn platform-initiated?
  "Is this a cancellation the PLATFORM is initiating rather than the
  backer? Carried on the proposal because the two are the same op with
  entirely different consequences for the person whose pledge it is."
  [proposal]
  (and (= :cancel-pledge (:op proposal))
       (not= :backer (get-in proposal [:value :initiated-by] :backer))))

(def scope-excluded-terms
  "Case-insensitive substrings marking a proposal as claiming to have
  moved money.

  CRITICAL: every term is phrased as the COMPLETED act ('charged the
  backer'), never a bare noun like 'pledge' or 'payment' — a bare noun
  would match inside this actor's own legitimate proposals (whose whole
  job is to talk about pledges) and self-block the happy path. See
  `pledgeops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`."
  ["charged the backer" "charged the backers" "have charged"
   "captured the payment" "captured the funds" "took the payment"
   "collected the pledge" "collected the pledges" "have collected"
   "refunded the backer" "have refunded" "released the funds to"
   "支援者に請求した" "決済を実行した" "決済を完了した" "資金を回収した"
   "返金した" "引き落とした"])

;; ----------------------------- checks -----------------------------

(defn- subject
  "The pledge a proposal is about: the one it carries, or the stored
  record for the id it names."
  [proposal st]
  (or (get-in proposal [:value :pledge])
      (some->> (get-in proposal [:value :pledge-id]) (store/pledge-of st))))

(defn- validity-violations
  "Re-run `crowdfunding.pledge/pledge-errors` from the STORE. Applies to
  accepting and changing — the two ops that put a commitment into a
  campaign's total."
  [proposal st now]
  (when (contains? #{:accept-pledge :change-pledge} (:op proposal))
    (let [cid (get-in proposal [:value :campaign-id])
          c   (store/campaign-of st cid)
          p   (subject proposal st)]
      (cond
        (nil? c) [{:rule :campaign-unknown :detail (str (or cid "(id missing)") " は存在しない")}]
        (nil? p) [{:rule :pledge-missing :detail "対象の支援が特定できない"}]
        :else
        (when-let [errs (seq (pledge/pledge-errors p c (store/rewards st cid) now))]
          (mapv (fn [e] {:rule (:pledge.error/code e)
                         :detail (or (:pledge.error/detail e)
                                     (name (:pledge.error/code e)))})
                errs))))))

(defn- total-violations
  "A pledge whose total cannot be computed must never be recorded. Stated
  separately from `validity-violations` so the ledger records WHY — 'we
  cannot price this' and 'this tier is sold out' are different facts for a
  human reading the log."
  [proposal st]
  (when (contains? #{:accept-pledge :change-pledge} (:op proposal))
    (let [cid (get-in proposal [:value :campaign-id])
          p   (subject proposal st)]
      (when (and p (nil? (pledge/total-minor p (store/rewards st cid))))
        [{:rule :total-uncomputable
          :detail "アドオン未解決・配送不可などで請求額が確定できない"}]))))

(defn- pricing-model-violations
  "A pledge and its campaign must agree about how the backer's final price
  is determined.

  Two failures, and neither is visible from the campaign alone or the
  pledge alone:

  - a pledge on a `:deposit-plus-settlement` campaign with NO quotation
    has no deposit, no margin and — critically — **no cap**. It would
    settle at whatever the parts cost. That is an unbounded commitment
    wearing the shape of a bounded one, and it is the single failure
    `crowdfunding.passthrough` exists to prevent.
  - a quotation on a `:fixed` campaign is the mirror: a backer who
    believes they agreed to a cap that will never be applied.

  The campaign's pricing model is read from the STORE, never from the
  request — the same 'ground truth, not self-report' rule the tier
  availability check uses. `crowdfunding.passthrough/campaign-quote-errors`
  owns the rule; this function supplies the store lookups."
  [proposal st]
  (when (contains? #{:accept-pledge :change-pledge} (:op proposal))
    (let [cid (get-in proposal [:value :campaign-id])
          c   (store/campaign-of st cid)
          p   (subject proposal st)
          id  (get-in proposal [:value :pledge-id])
          q   (or (get-in proposal [:value :quote]) (store/quote-of st id))]
      (when (and c p)
        (when-let [errs (seq (passthrough/campaign-quote-errors
                              c [p] (cond-> {} q (assoc (:pledge/id p) q))))]
          (mapv (fn [e] {:rule   (:passthrough.error/code e)
                         :detail (or (:passthrough.error/detail e)
                                     (name (:passthrough.error/code e)))})
                errs))))))

(defn- duplicate-violations
  "Accepting an id that already exists would overwrite a backer's
  commitment. Idempotency here is a refusal, not an overwrite."
  [proposal st]
  (when (= :accept-pledge (:op proposal))
    (let [id (get-in proposal [:value :pledge-id])]
      (when (and id (store/pledge-of st id))
        [{:rule :duplicate-pledge-id :detail (str id " は既に存在する")}]))))

(defn- window-violations
  "Cancelling or changing after the funding window closed."
  [proposal st now]
  (when (contains? #{:cancel-pledge :change-pledge} (:op proposal))
    (let [id (get-in proposal [:value :pledge-id])
          p  (store/pledge-of st id)
          c  (store/campaign-of st (get-in proposal [:value :campaign-id]))]
      (cond
        (nil? p) [{:rule :pledge-unknown :detail (str (or id "(id missing)") " は存在しない")}]
        (nil? c) [{:rule :campaign-unknown :detail "対象のキャンペーンが存在しない"}]
        (not (pledge/cancellable? p c now))
        [{:rule :outside-backer-window
          :detail (str "締切(" (:campaign/deadline c) ")後・または状態 "
                       (pr-str (:campaign/state c))
                       " では取消・変更でなく返金の判断になる")}]))))

(defn- effect-not-propose-violations [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob [proposal]
  (str/lower (pr-str (select-keys proposal [:op :summary :rationale :cites]))))

(defn- scope-exclusion-violations [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "請求・決済・返金を実行済みと主張する提案は永久に禁止"}])))

(defn check
  "Censors a PledgeAdvisor proposal. `context` supplies `:now` (ISO-8601
  UTC) — this governor has no clock of its own.

  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
           :high-stakes? bool :hard? bool}."
  [_request context proposal store]
  (let [now  (:now context)
        hard (into []
                   (concat (validity-violations proposal store now)
                           (total-violations proposal store)
                           (pricing-model-violations proposal store)
                           (duplicate-violations proposal store)
                           (window-violations proposal store now)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                             (platform-initiated? proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact [request context verdict]
  {:t           :governor-hold
   :op          (:op request)
   :actor       (:actor-id context)
   :pledge-id   (:pledge-id request)
   :campaign-id (:campaign-id request)
   :disposition :hold
   :basis       (mapv :rule (:violations verdict))
   :violations  (:violations verdict)
   :confidence  (:confidence verdict)})
