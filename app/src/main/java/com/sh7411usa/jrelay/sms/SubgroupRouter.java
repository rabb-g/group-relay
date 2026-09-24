package com.sh7411usa.jrelay.sms;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides, for a member post or an in-thread reply under sub-group (GROUP_MMS) delivery,
 * which sub-groups receive a group MMS and which individual members receive a plain SMS.
 *
 * <p>Pure and Android-free: no {@code Context}, no database, no network. Every input is a
 * plain id or a caller-supplied set of ids; every output is a plain {@link OutboundPlan}.
 * Callers own actually sending anything and persisting the state this class reasons about.
 *
 * <h2>Why the poster's own sub-group is excluded</h2>
 * A sub-group is a live group-MMS thread. When a member posts (or replies) from inside that
 * thread, the carrier delivers the message to the thread's other participants directly,
 * peer-to-peer, before jRelay ever sees it (see docs/phase3-redesign.md §1-2). Re-sending a
 * relayed copy into that same sub-group would deliver the message twice to the people
 * closest to it. So the poster's (or replier's) own sub-group is always removed from the
 * relay set — not because it doesn't need the message, but because it already has it.
 *
 * <h2>Why unassigned members are returned separately, not dropped</h2>
 * A member with no {@code subgroup_id} (today, that is everyone) cannot receive a group MMS —
 * there is no thread for them to peer-deliver through. If a caller relays only to sub-groups
 * and forgets the unassigned set, that member receives nothing and nobody notices, because
 * there is no error: the send simply never happens for them. {@link OutboundPlan#individualMemberIds}
 * exists specifically so a caller cannot forget this without visibly ignoring part of the result.
 */
public final class SubgroupRouter {

    private SubgroupRouter() {
    }

    /** How an in-thread reply is bridged onward. See docs/phase3-redesign.md §2, "Replying back to the original author". */
    public enum ReplyRoute {
        /** Bridge to every other sub-group and every unassigned member. Most complete, most expensive. */
        EVERYONE,
        /** Bridge to the original poster only, by individual SMS. Cheapest; fragments the conversation. */
        POSTER_ONLY,
        /**
         * Bridge to the original poster's own sub-group only (one MMS), or by individual SMS if the
         * poster has no sub-group. Recommended default in the redesign doc: one send, lands where the
         * question was asked.
         */
        POSTERS_GROUP_ONLY
    }

    /**
     * The outcome of a routing decision.
     *
     * <p>{@link #subgroupIdsToRelay} — sub-groups that should receive one group MMS each.
     * {@link #individualMemberIds} — members with no sub-group (or otherwise unreachable by
     * group MMS) who must each receive one individual SMS.
     * {@link #notes} — human-readable reasons for anything excluded, for logging.
     */
    public static final class OutboundPlan {
        public final List<Long> subgroupIdsToRelay;
        public final List<Long> individualMemberIds;
        public final List<String> notes;

        OutboundPlan(List<Long> subgroupIdsToRelay, List<Long> individualMemberIds, List<String> notes) {
            this.subgroupIdsToRelay = Collections.unmodifiableList(subgroupIdsToRelay);
            this.individualMemberIds = Collections.unmodifiableList(individualMemberIds);
            this.notes = Collections.unmodifiableList(notes);
        }

        public boolean isEmpty() {
            return subgroupIdsToRelay.isEmpty() && individualMemberIds.isEmpty();
        }

        @Override
        public String toString() {
            return "OutboundPlan{subgroups=" + subgroupIdsToRelay
                    + ", individuals=" + individualMemberIds
                    + ", notes=" + notes + "}";
        }
    }

    /**
     * Routes an ordinary member (or admin) post.
     *
     * @param posterMemberId          the poster's member id; never included in the output.
     * @param posterSubgroupId        the poster's sub-group id, or {@code null} if the poster has
     *                                 no sub-group assigned (today's state for everyone).
     * @param activeSubgroupIds       every sub-group the caller considers eligible to relay to —
     *                                 i.e. it currently has at least one active member. A sub-group
     *                                 with no active members left must not appear in this set; the
     *                                 caller is expected to have already filtered it out, since only
     *                                 the caller's membership store knows which members are active.
     * @param unassignedActiveMemberIds active members with no sub-group, excluding the poster if the
     *                                 poster is themselves unassigned. Extra occurrences of the poster
     *                                 id are tolerated and stripped defensively.
     */
    public static OutboundPlan routePost(long posterMemberId,
                                          Long posterSubgroupId,
                                          Set<Long> activeSubgroupIds,
                                          Collection<Long> unassignedActiveMemberIds) {
        List<String> notes = new ArrayList<>();

        Set<Long> subgroups = new TreeSet<>(activeSubgroupIds == null ? Collections.<Long>emptySet() : activeSubgroupIds);
        if (posterSubgroupId != null) {
            boolean removed = subgroups.remove(posterSubgroupId);
            if (removed) {
                notes.add("excluded poster's own sub-group " + posterSubgroupId
                        + ": already delivered peer-to-peer");
            } else if (subgroups.isEmpty() && activeSubgroupIds != null && !activeSubgroupIds.isEmpty()) {
                // Poster's sub-group wasn't in the active set at all (e.g. it has no active
                // members left besides the poster, or the caller already excluded it).
                notes.add("poster's sub-group " + posterSubgroupId + " was not in the active set");
            }
        }

        if (subgroups.isEmpty()) {
            if (activeSubgroupIds == null || activeSubgroupIds.isEmpty()) {
                notes.add("no sub-groups configured; relaying by individual SMS only");
            } else if (posterSubgroupId != null && activeSubgroupIds.size() == 1) {
                notes.add("only sub-group is the poster's own; nobody else to relay to by MMS");
            }
        }

        List<Long> individuals = dedupeExcluding(unassignedActiveMemberIds, posterMemberId);

        return new OutboundPlan(new ArrayList<>(subgroups), individuals, notes);
    }

    /**
     * Routes an in-thread reply. The sub-group the reply arrived in has already seen it
     * peer-to-peer and is always excluded, regardless of {@code route}.
     *
     * @param route                    which members/sub-groups the reply should be bridged to.
     * @param replyFromSubgroupId      the sub-group whose thread the reply was posted in. Must be
     *                                 non-null — a reply with no originating sub-group (e.g. a
     *                                 plain 1:1 SMS reply) is not this class's concern.
     * @param originalPosterMemberId   the member id who authored the post being replied to.
     * @param originalPosterSubgroupId that member's sub-group id, or {@code null} if unassigned.
     * @param activeSubgroupIds        every sub-group eligible to relay to (see {@link #routePost}).
     * @param unassignedActiveMemberIds active members with no sub-group.
     */
    public static OutboundPlan routeReply(ReplyRoute route,
                                           long replyFromSubgroupId,
                                           long originalPosterMemberId,
                                           Long originalPosterSubgroupId,
                                           Set<Long> activeSubgroupIds,
                                           Collection<Long> unassignedActiveMemberIds) {
        List<String> notes = new ArrayList<>();
        boolean posterAlreadySaw = originalPosterSubgroupId != null
                && originalPosterSubgroupId.longValue() == replyFromSubgroupId;

        switch (route) {
            case EVERYONE: {
                Set<Long> subgroups = new TreeSet<>(activeSubgroupIds == null ? Collections.<Long>emptySet() : activeSubgroupIds);
                subgroups.remove(replyFromSubgroupId);
                notes.add("excluded replying sub-group " + replyFromSubgroupId
                        + ": already delivered peer-to-peer");
                List<Long> individuals = dedupeExcluding(unassignedActiveMemberIds, -1L);
                return new OutboundPlan(new ArrayList<>(subgroups), individuals, notes);
            }
            case POSTER_ONLY: {
                if (posterAlreadySaw) {
                    notes.add("original poster is in the replying sub-group " + replyFromSubgroupId
                            + "; already delivered peer-to-peer, nothing to send");
                    return new OutboundPlan(Collections.<Long>emptyList(), Collections.<Long>emptyList(), notes);
                }
                notes.add("bridging to original poster only, by individual SMS");
                return new OutboundPlan(Collections.<Long>emptyList(),
                        Collections.singletonList(originalPosterMemberId), notes);
            }
            case POSTERS_GROUP_ONLY: {
                if (posterAlreadySaw) {
                    notes.add("original poster is in the replying sub-group " + replyFromSubgroupId
                            + "; already delivered peer-to-peer, nothing to send");
                    return new OutboundPlan(Collections.<Long>emptyList(), Collections.<Long>emptyList(), notes);
                }
                if (originalPosterSubgroupId == null) {
                    notes.add("original poster has no sub-group; falling back to individual SMS");
                    return new OutboundPlan(Collections.<Long>emptyList(),
                            Collections.singletonList(originalPosterMemberId), notes);
                }
                notes.add("bridging to original poster's sub-group " + originalPosterSubgroupId + " only");
                return new OutboundPlan(Collections.singletonList(originalPosterSubgroupId),
                        Collections.<Long>emptyList(), notes);
            }
            default:
                throw new IllegalArgumentException("Unhandled route: " + route);
        }
    }

    private static List<Long> dedupeExcluding(Collection<Long> ids, long excludeId) {
        Set<Long> seen = new LinkedHashSet<>();
        List<Long> result = new ArrayList<>();
        if (ids == null) {
            return result;
        }
        for (Long id : ids) {
            if (id == null || id.longValue() == excludeId) {
                continue;
            }
            if (seen.add(id)) {
                result.add(id);
            }
        }
        Collections.sort(result);
        return result;
    }
}
