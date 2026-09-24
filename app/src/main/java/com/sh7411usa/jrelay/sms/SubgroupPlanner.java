package com.sh7411usa.jrelay.sms;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     *  still leaves a real peer conversation. Below this threshold we leave the remainder
     *  unassigned rather than spin up a thin new sub-group or overfill an existing one past
     *  target -- unassigned members simply get an individual SMS this pass, same as today,
     *  and a carrier's group-MMS recipient cap is never tested by surprise. */
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

        // Whatever is left forms new sub-groups of exactly targetSize. A trailing remainder that
        // cannot reach MIN_NEW_SUBGROUP_SIZE is left unassigned rather than pulled into the group
        // just created: doing so would grow that thread past targetSize, same overfill problem as
        // folding into an existing group (see the note below and the class javadoc).
        int nextGroupId = sizes.isEmpty() ? 1 : sizes.lastKey() + 1;
        while (!remaining.isEmpty()) {
            int take = Math.min(targetSize, remaining.size());
            if (take < MIN_NEW_SUBGROUP_SIZE) {
                // Too few members left to form a standalone sub-group, and folding them into an
                // existing sub-group is no longer an option: a "group of 9" already carries
                // jRelay as a 10th participant, and carriers cap how many recipients a group MMS
                // may have. Quietly growing an already-sized thread is how that cap gets
                // discovered in production, on a message to real people. Leftover members are
                // left unassigned instead; SubgroupRouter routes unassigned members to an
                // individual SMS, exactly like everyone gets today, so nobody is stranded -- they
                // just don't get folded into a group thread this pass.
                notes.add(remaining.size() + " member(s) left unassigned: not enough people to "
                        + "form a sub-group of at least " + MIN_NEW_SUBGROUP_SIZE + " and no "
                        + "existing under-full sub-group left to add them to. They will receive "
                        + "individual SMS instead of a group thread.");
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

    /**
     * At or below this size, a sub-group is a candidate to be folded into another one.
     *
     * <p>Chosen as 4 deliberately, and deliberately above {@link #MIN_NEW_SUBGROUP_SIZE}: the
     * point is <b>not</b> to rebalance every time somebody leaves. Every merge costs a fresh
     * roster to everyone involved and a brand-new thread for the people who moved, so churning
     * groups on each departure would cost more traffic than the thin group ever wastes. A group
     * only becomes a problem once it is small enough that a couple more departures would strand
     * whoever is left, and 4 is where that starts — one below it is
     * {@link #MIN_NEW_SUBGROUP_SIZE}, the size we would not have been willing to create in the
     * first place.
     */
    public static final int MERGE_THRESHOLD = 4;

    /**
     * Proposes folding under-populated sub-groups into other sub-groups that have room.
     *
     * <p>A sub-group is a candidate only when it has at most {@link #MERGE_THRESHOLD} members
     * <i>and</i> some other sub-group can absorb all of them without exceeding
     * {@code targetSize}. Both halves matter: without the second, a merge would push the
     * destination past the carrier's tested recipient ceiling, which is the one limit in this
     * system that fails silently. A candidate with nowhere to go is left exactly as it is and
     * reported in {@link Plan#advisoryNotes}, because a thin group that still works beats a
     * destination group that stops delivering.
     *
     * <p>Members are never split across destinations — the whole source group moves together or
     * not at all. Splitting would hand two sets of strangers to two existing threads and cost two
     * rosters to fix what is meant to be a simplification.
     *
     * <p>Sources are considered smallest-first so the thinnest group is rescued first when room is
     * scarce; ties and destinations both resolve by ascending sub-group id, so the same input
     * always yields the same plan. A merged-away source is removed from consideration as a
     * destination in the same pass, so two thin groups never propose absorbing each other.
     *
     * @param subgroupMembers sub-group id -> that sub-group's member ids. The member ids are
     *                        needed (unlike {@link #planFor}) because a merge moves specific
     *                        people, not a count.
     * @param targetSize      the per-sub-group ceiling a merge must not breach, same meaning as in
     *                        {@link #planFor}.
     * @return a {@link Plan} whose assignments move every member of each merged source into its
     *         destination. Empty if nothing should move.
     */
    public static Plan planMerges(Map<Integer, List<Long>> subgroupMembers, int targetSize) {
        List<String> notes = new ArrayList<>();
        List<Assignment> assignments = new ArrayList<>();

        SortedMap<Integer, List<Long>> groups = new TreeMap<>();
        if (subgroupMembers != null) {
            for (Map.Entry<Integer, List<Long>> e : subgroupMembers.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    groups.put(e.getKey(), new ArrayList<>(e.getValue()));
                }
            }
        }

        if (groups.size() < 2) {
            notes.add("Fewer than two sub-groups exist; nothing to merge.");
            return new Plan(assignments, notes);
        }

        // Smallest source first, ascending id to break ties. Collected up front so the live sizes
        // below can change underneath us without disturbing the order we consider sources in.
        List<Integer> sources = new ArrayList<>();
        for (Map.Entry<Integer, List<Long>> e : groups.entrySet()) {
            if (e.getValue().size() <= MERGE_THRESHOLD) {
                sources.add(e.getKey());
            }
        }
        sources.sort((a, b) -> {
            int bySize = Integer.compare(groups.get(a).size(), groups.get(b).size());
            return bySize != 0 ? bySize : Integer.compare(a, b);
        });

        if (sources.isEmpty()) {
            notes.add("No sub-group is at or below " + MERGE_THRESHOLD + " members; nothing to merge.");
            return new Plan(assignments, notes);
        }

        Set<Integer> dissolved = new LinkedHashSet<>();
        for (Integer sourceId : sources) {
            if (dissolved.contains(sourceId)) {
                continue;
            }
            List<Long> movers = groups.get(sourceId);
            Integer destinationId = null;
            for (Map.Entry<Integer, List<Long>> candidate : groups.entrySet()) {
                int candidateId = candidate.getKey();
                if (candidateId == sourceId || dissolved.contains(candidateId)) {
                    continue;
                }
                if (candidate.getValue().size() + movers.size() <= targetSize) {
                    destinationId = candidateId;
                    break;
                }
            }

            if (destinationId == null) {
                notes.add("Sub-group " + sourceId + " has only " + movers.size()
                        + " member(s), but no other sub-group has room for all of them without "
                        + "exceeding " + targetSize + "; left as-is.");
                continue;
            }

            for (Long memberId : movers) {
                assignments.add(new Assignment(memberId, destinationId, false));
            }
            groups.get(destinationId).addAll(movers);
            dissolved.add(sourceId);
            notes.add("Sub-group " + sourceId + " (" + movers.size()
                    + " member(s)) folds into sub-group " + destinationId + ", which becomes "
                    + groups.get(destinationId).size() + "/" + targetSize + ".");
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
