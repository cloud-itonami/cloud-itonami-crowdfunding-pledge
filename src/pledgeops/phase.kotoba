(ns pledgeops.phase
  "Phase 0->3 staged rollout for the pledge actor.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- pledges may be accepted, every write
                                 needs human approval.
    Phase 2  assisted-change  -- adds changes and cancellations, still
                                 approval-gated.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:accept-pledge`, `:change-pledge` and
                                 `:cancel-pledge` may auto-commit.

  `:flag-pledge-concern` is deliberately ABSENT from every phase's
  `:auto` set, INCLUDING phase 3.

  `:cancel-pledge` IS auto-committable, and that is the deliberate part.
  A backer withdrawing inside the funding window is exercising a right,
  and routing a right through an approval queue is how a right quietly
  becomes a favour. The platform cancelling someone's pledge on its own
  initiative is a different act wearing the same op, and
  `pledgeops.governor/platform-initiated?` escalates it independently of
  this table — which is why the phase gate can afford to let the op
  through: the two layers are checking different things, and both have to
  agree before anything auto-commits.

  Nothing auto-committable here takes a payment. This actor has no rail
  and no charge op at all; `crowdfunding.collection` owns that moment,
  behind its own governor, in its own repo."
  (:require [pledgeops.governor :as governor]))

(def read-ops #{})
(def write-ops governor/allowed-ops)

(def phases
  {0 {:label "read-only"       :writes #{}                :auto #{}}
   1 {:label "assisted-intake" :writes #{:accept-pledge}  :auto #{}}
   2 {:label "assisted-change" :writes #{:accept-pledge :change-pledge :cancel-pledge}
      :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:accept-pledge :change-pledge :cancel-pledge}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a PledgeGovernor verdict to a base disposition before the phase
  gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
