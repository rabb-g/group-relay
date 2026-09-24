package com.sh7411usa.jrelay.sms.mms;

import com.sh7411usa.jrelay.sms.PhoneNumberUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Matches an inbound group MMS to the sub-group thread it belongs to, if any.
 *
 * <p>The relay phone does not know its own phone number. Every time jRelay itself sends a group
 * MMS on behalf of a sub-group, it records the "signature" of the recipient set it sent to
 * ({@link #signature}) alongside the sub-group id. An inbound MMS carries a FROM address (the
 * sender) and TO addresses (everyone else on the thread, normally including the relay phone
 * itself, since the relay is a participant). This class decides whether that inbound message's
 * participant set matches a signature jRelay itself created, and if so which sub-group.
 *
 * <p><b>Why "participants minus exactly one element":</b> the relay phone's own number is
 * unknown to the app, but it is guaranteed to be exactly one of the inbound participants (the
 * relay only ever sees MMS on threads it is part of). So the candidates are every "remove exactly
 * one participant" subset: removing the relay's own number reproduces exactly the signature
 * jRelay recorded when it created the thread. Removing more than one is never attempted, and
 * {@link #match} only trusts an EXACT signature hit.
 *
 * <p><b>The full, un-reduced participant set is deliberately NOT a candidate.</b> Verified on the
 * target device (2026-09-24, Galaxy Z Flip3, stock Samsung Messages): every inbound group MMS
 * lists the relay's own number among its TO addresses. A genuine thread therefore always has
 * exactly one extra. Accepting the full set as well would add nothing for a real thread, and it
 * would open a leak: a private chat of one whole sub-group plus one outsider (a spouse, say),
 * which does include the relay, reduces to "sub-group + outsider" and misses; but if the relay were
 * ever absent from the list, the same chat would reduce to exactly the sub-group and be broadcast
 * to everyone. If a future device is found that omits the relay's own number, the fix is to learn
 * that number, not to accept the full set.
 *
 * <p><b>Why this stops broadcast leakage:</b> the design rule is that a message is bridged only
 * if its participant set is exactly a set jRelay itself sent to, optionally plus one extra (the
 * host/relay number). Consider nine neighbours jRelay grouped into a sub-group, who then start a
 * side conversation among themselves plus one outsider who is not in that sub-group and not the
 * relay. That thread's participant set is the recorded signature plus TWO extras (the outsider
 * and the relay), not one. Removing any single element can never reproduce the recorded
 * signature, so no candidate hits, {@link #match} returns null, and the message is correctly left
 * unbridged instead of guessed at and broadcast to the wrong audience.
 *
 * <p>Pure Java, no Android imports, so it is testable on the plain JVM.
 */
public final class ThreadMatcher {

    private ThreadMatcher() {
    }

    /**
     * Normalizes each number with {@link PhoneNumberUtils#normalize}, drops entries that are
     * null or fail to normalize, de-duplicates, sorts ascending in String natural order, and
     * joins with {@code ","}. Null or empty input yields {@code ""}.
     */
    public static String signature(Collection<String> numbers) {
        TreeSet<String> normalized = new TreeSet<>();
        if (numbers != null) {
            for (String raw : numbers) {
                String n = PhoneNumberUtils.normalize(raw);
                if (n != null) {
                    normalized.add(n);
                }
            }
        }
        return String.join(",", normalized);
    }

    /** Looks up the sub-group id recorded for an exact participant-set signature, if any. */
    public interface SignatureLookup {
        /** Sub-group id recorded for this exact signature, or null if unknown. */
        Long subgroupFor(String signature);
    }

    /** A confirmed match: which sub-group, and the exact signature that was recorded for it. */
    public static final class Match {
        public final long subgroupId;
        public final String signature;

        public Match(long subgroupId, String signature) {
            this.subgroupId = subgroupId;
            this.signature = signature;
        }
    }

    /**
     * Determines which sub-group (if any) an inbound group MMS belongs to.
     *
     * <p>Participants = {@code {sender} ∪ recipients}, normalized and de-duplicated. Candidates
     * are {@code signature(participants minus exactly one element)} for each element, iterating
     * elements in sorted order. The full participant set is never itself a candidate (see the
     * class javadoc). A candidate is a hit only if {@code lookup.subgroupFor(candidate)}
     * is non-null AND the candidate's participant set still contains the normalized sender (a
     * sender cannot match a thread it has been removed from). If every hit agrees on one
     * sub-group id, that sub-group is returned (with the first hit's signature); if there are no
     * hits, or hits disagree on the sub-group id, this returns null rather than guess.
     *
     * @param sender     the MMS sender's address, in any format {@link PhoneNumberUtils#normalize}
     *                   accepts.
     * @param recipients the other addresses on the thread (normally includes the relay phone's
     *                   own number). Null or empty is treated as empty; never throws on it.
     * @param lookup     recorded-signature lookup. Null returns null.
     * @return the {@link Match}, or null if sender/lookup is unusable, there is no hit, or hits
     *         disagree.
     */
    public static Match match(String sender, Collection<String> recipients, SignatureLookup lookup) {
        String normalizedSender = sender == null ? null : PhoneNumberUtils.normalize(sender);
        if (normalizedSender == null || lookup == null) {
            return null;
        }

        TreeSet<String> participants = new TreeSet<>();
        participants.add(normalizedSender);
        if (recipients != null) {
            for (String raw : recipients) {
                String n = PhoneNumberUtils.normalize(raw);
                if (n != null) {
                    participants.add(n);
                }
            }
        }

        List<List<String>> candidateSets = new ArrayList<>();
        for (String toRemove : participants) {
            List<String> subset = new ArrayList<>(participants);
            subset.remove(toRemove);
            candidateSets.add(subset);
        }

        Long agreedSubgroupId = null;
        String agreedSignature = null;
        for (List<String> candidateSet : candidateSets) {
            if (!candidateSet.contains(normalizedSender)) {
                continue;
            }
            String candidateSignature = signature(candidateSet);
            Long subgroupId = lookup.subgroupFor(candidateSignature);
            if (subgroupId == null) {
                continue;
            }
            if (agreedSubgroupId == null) {
                agreedSubgroupId = subgroupId;
                agreedSignature = candidateSignature;
            } else if (!agreedSubgroupId.equals(subgroupId)) {
                return null;
            }
        }

        if (agreedSubgroupId == null) {
            return null;
        }
        return new Match(agreedSubgroupId, agreedSignature);
    }
}
