package com.sh7411usa.jrelay.sms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Proposes how to place newly-unassigned members into sub-groups. Never decides for real.
 *
 * <p>Phase 3 (docs/phase3-redesign.md §5.2) makes sub-groups stable and admin-assigned on
 * purpose: inside a sub-group jRelay cannot prefix messages with names, so members are legible
 * to each other only if they already recognise the numbers, which is far more likely when the
 * nine people are actual neighbours or otherwise know one another. That is a social judgement
 * the admin makes; an algorithm has no access to it and must not pretend to. So this class only
 * ever computes a {@link Plan} — a proposal the caller may apply, edit, or discard. It never
 * touches a database, a Context, or a real assignment.
 *
 * <p><b>Never move an already-assigned member.</b> This is a hard rule, not a tuning choice.
 * Reshuffling an existing sub-group breaks a group MMS thread that already lives on nine
 * handsets, invalidates whatever contacts members saved from the roster send, and does so for
 * a social/admin reason this class cannot evaluate. §5.2 recommends rebalancing only as an
 * explicit, rare admin command with its own cost (12+ new threads) — never as a side effect of
 * placing new joiners. So {@link #planFor} only ever assigns members whose current sub-group is
 * unknown (i.e. newly unassigned); if the inputs imply an assigned member should move to
 * balance sizes, the plan reports that as {@link Plan#advisoryNotes} text only, never as an
 * assignment.
 *
 * <p>The admin's local knowledge of who lives near whom, attends the same shul, etc. beats any
 * balancing this class could do. This class's job is bookkeeping (which slots are open, which
 * group is undersized) so the admin does not have to count by hand — not judgement about who
 * belongs together.
 */
public final class SubgroupPlanner {

    /** Minimum number of members a newly created sub-group must receive in this planning pass.
     *  <p>Chosen as 3: a sub-group of one is pointless (no MMS thread at all) and a sub-group of
     *  two is little better — dev docs (§5.1/§5.2) treat a "thread of two" as effectively
     *  stranding someone, since the whole point of the sub-group is peer coverage, not just a
     *  channel back to jRelay. Three is the smallest group where losing or muting one member
     *  still leaves a real peer conversation. Below this threshold we prefer to overfill an
     *  existing under-full sub-group rather than spin up a new, thin one. */
    public static final int MIN_NEW_SUBGROUP_SIZE = 3;

    private SubgroupPlanner() {
    }

    /**
     * Computes a placement proposal for currently-unassigned members.
     *
     * @param unassignedMemberIds member ids with no current sub-group, in the order they should
     *                            be considered. Determinism comes from this order plus the
     *                            iteration order below (a {@link TreeMap} view of
     *                            {@code currentSizes}), never from hash-map iteration order.
     * @param currentSizes        sub-group id -> current member count, for every sub-group that
     *                            already exists (including full or over-full ones).
     * @param targetSize          desired size per sub-group (jRelay itself is not counted here;
     *                            callers pass e.g. 9 and jRelay is the additional 10th
     *                            participant). Not hardcoded in this class.
     * @return a {@link Plan} the caller may choose to apply.
     */
    public static Plan planFor(List<Long> unassignedMemberIds, Map<Integer, Integer> currentSizes,
                                int targetSize) {
        List<String> notes = new ArrayList<>();
        List<Assignment> assignments = new ArrayList<>();

        if (unassignedMemberIds == null || unassignedMemberIds.isEmpty()) {
            notes.add("No unassigned members; nothing to plan.");
            return new Plan(assignments, notes);
        }
        if (targetSize <= 1) {
            notes.add("Target size " + targetSize + " is too small to form a real sub-group "
                    + "(need at least " + MIN_NEW_SUBGROUP_SIZE + "); no placements proposed. "
                    + "Choose a target size before planning.");
            return new Plan(assignments, notes);
        }

        // TreeMap gives a fixed, sorted iteration order (by sub-group id) so the same inputs
        // always fill the same slots in the same order, regardless of the caller's Map type.
        SortedMap<Integer, Integer> sizes = new TreeMap<>();
        if (currentSizes != null) {
            sizes.putAll(currentSizes);
        }

        for (Map.Entry<Integer, Integer> e : sizes.entrySet()) {
            if (e.getValue() > targetSize) {
                notes.add("Sub-group " + e.getKey() + " is already over target ("
                        + e.getValue() + "/" + targetSize + "); left as-is. Consider an explicit "
                        + "admin rebalance if this should shrink.");
            }
        }

        List<Long> remaining = new ArrayList<>(unassignedMemberIds);

        // Fill existing under-full sub-groups first, in ascending sub-group-id order, before
        // creating anything new: reusing an existing thread costs nothing, a new sub-group means
        // a brand-new thread and roster for everyone in it.
        for (Map.Entry<Integer, Integer> e : sizes.entrySet()) {
            if (remaining.isEmpty()) {
                break;
            }
            int groupId = e.getKey();
            int openSlots = targetSize - e.getValue();
            if (openSlots <= 0) {
                continue;
            }
            int take = Math.min(openSlots, remaining.size());
            for (int i = 0; i < take; i++) {
                assignments.add(new Assignment(remaining.remove(0), groupId, false));
            }
        }

        // Whatever is left forms new sub-groups of targetSize, except the leftover remainder,
        // which must not be stranded below MIN_NEW_SUBGROUP_SIZE.
        int nextGroupId = sizes.isEmpty() ? 1 : sizes.lastKey() + 1;
        while (!remaining.isEmpty()) {
            int take = Math.min(targetSize, remaining.size());
            int leftoverAfter = remaining.size() - take;
            // If what would remain after this new group is a non-zero amount smaller than the
            // minimum viable sub-group, pull it into this group now instead of leaving a stub.
            if (leftoverAfter > 0 && leftoverAfter < MIN_NEW_SUBGROUP_SIZE) {
                take = remaining.size();
            }
            if (take < MIN_NEW_SUBGROUP_SIZE) {
                // Too few members left to form a standalone sub-group. Prefer overfilling the
                // most recently filled/created group over stranding people in a thread of one
                // or two.
                // Prefer a group this plan already touched: it had room a moment ago, so topping
                // it up by one or two is a small, deliberate overfill.
                int fallbackGroupId = -1;
                if (!assignments.isEmpty()) {
                    fallbackGroupId = assignments.get(assignments.size() - 1).subgroupId;
                } else {
                    // Nothing was placed, so every existing group was skipped as full. Fall back
                    // only onto one that is genuinely UNDER target -- never onto an over-target
                    // group. Doing so would contradict the advisory note this same plan emits
                    // about that group, and it has a practical cost: carriers cap the recipient
                    // count of a group MMS, so growing an already-oversized sub-group is how you
                    // discover that limit. Lowest id wins, for determinism.
                    for (Map.Entry<Integer, Integer> e : sizes.entrySet()) {
                        if (e.getValue() < targetSize) {
                            fallbackGroupId = e.getKey();
                            break;
                        }
                    }
                }
                if (fallbackGroupId != -1) {
                    for (Long id : remaining) {
                        assignments.add(new Assignment(id, fallbackGroupId, false));
                    }
                    notes.add(remaining.size() + " leftover member(s) added to sub-group "
                            + fallbackGroupId + " instead of forming a group of " + remaining.size()
                            + " (below minimum viable size " + MIN_NEW_SUBGROUP_SIZE + ").");
                } else {
                    notes.add(remaining.size() + " member(s) could not be placed: not enough "
                            + "people to form a sub-group of at least " + MIN_NEW_SUBGROUP_SIZE
                            + " and no existing sub-group to add them to. Left unassigned.");
                }
                remaining.clear();
                break;
            }
            List<Long> group = remaining.subList(0, take);
            for (Long id : group) {
                assignments.add(new Assignment(id, nextGroupId, true));
            }
            group.clear();
            nextGroupId++;
        }

        return new Plan(assignments, notes);
    }

    /** Renders a plan as a short, plain-text summary suitable for an admin preview. */
    public static String summarize(Plan plan) {
        if (plan.assignments.isEmpty() && plan.advisoryNotes.isEmpty()) {
            return "No changes proposed.";
        }
        // groupId -> [count, isNew]
        TreeMap<Integer, int[]> byGroup = new TreeMap<>();
        for (Assignment a : plan.assignments) {
            int[] entry = byGroup.get(a.subgroupId);
            if (entry == null) {
                entry = new int[]{0, a.isNewSubgroup ? 1 : 0};
                byGroup.put(a.subgroupId, entry);
            }
            entry[0]++;
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Integer, int[]> e : byGroup.entrySet()) {
            int count = e.getValue()[0];
            boolean isNew = e.getValue()[1] == 1;
            String member = count == 1 ? "member" : "members";
            parts.add(count + " " + member + " into " + (isNew ? "a new group " : "group ")
                    + e.getKey());
        }
        StringBuilder sb = new StringBuilder();
        if (!parts.isEmpty()) {
            sb.append(String.join(", ", parts)).append(".");
        }
        for (String note : plan.advisoryNotes) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(note);
        }
        return sb.toString();
    }

    /** One proposed member -> sub-group placement. */
    public static final class Assignment {
        public final long memberId;
        public final int subgroupId;
        public final boolean isNewSubgroup;

        Assignment(long memberId, int subgroupId, boolean isNewSubgroup) {
            this.memberId = memberId;
            this.subgroupId = subgroupId;
            this.isNewSubgroup = isNewSubgroup;
        }
    }

    /** A proposal: placements to apply, plus advisory text that is not an action. */
    public static final class Plan {
        public final List<Assignment> assignments;
        /** Advisory-only observations (e.g. an over-full group, or a note that a reshuffle would
         *  balance things better). These are never assignments and must never be auto-applied. */
        public final List<String> advisoryNotes;

        Plan(List<Assignment> assignments, List<String> advisoryNotes) {
            this.assignments = Collections.unmodifiableList(assignments);
            this.advisoryNotes = Collections.unmodifiableList(advisoryNotes);
        }
    }
}
