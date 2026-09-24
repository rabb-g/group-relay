package com.sh7411usa.jrelay.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.sh7411usa.jrelay.sms.PhoneNumberUtils;

/**
 * Knows the relay phone's own number, so it can never be added as a member.
 *
 * <p>Why this exists: a member whose number is the relay's own number is sent every post as an
 * individual SMS, Android delivers that SMS straight back to the relay, and the relay broadcasts it
 * again - one extra copy of every post, forever. Two independent guards stop that: refusing the
 * number as a member (here), and dropping any inbound text that is an echo of something the relay
 * itself just sent to that number ({@link MessageSalt#isEchoOf}, used by
 * CommandProcessor.handleIncoming).
 *
 * <p>Kept in its own SharedPreferences file ("relay_identity") so it never collides with Prefs.
 */
public final class RelayIdentity {

    private static final String TAG = "RelayIdentity";
    private static final String PREFS_NAME = "relay_identity";
    private static final String KEY_OWN_NUMBER = "own_number_e164";
    /** A number one group-MMS thread suggested, not yet confirmed by a second, different thread. */
    private static final String KEY_PENDING_NUMBER = "pending_own_number_e164";
    /** The thread signature {@link #KEY_PENDING_NUMBER} came from. */
    private static final String KEY_PENDING_SIGNATURE = "pending_own_number_signature";

    private RelayIdentity() {
    }

    /**
     * The relay's own number, normalized, or null if it is not known. Never throws.
     *
     * <p>The SIM is read first on every call and wins whenever it reports a number: a SIM swap
     * must not leave the old number stored, or the new one could be added as a member. The stored
     * value is only a fallback for when the SIM reports nothing.
     */
    public static String ownNumber(Context context) {
        try {
            SharedPreferences sp = prefs(context);
            String stored = sp.getString(KEY_OWN_NUMBER, null);
            String fromSim = readLine1Number(context);
            if (!TextUtils.isEmpty(fromSim)) {
                if (!fromSim.equals(stored)) {
                    sp.edit().putString(KEY_OWN_NUMBER, fromSim).apply();
                    Log.i(TAG, "Own number from the SIM is now " + PhoneNumberUtils.mask(fromSim)
                            + (TextUtils.isEmpty(stored) ? "" : ", was " + PhoneNumberUtils.mask(stored)));
                }
                return fromSim;
            }
            if (!TextUtils.isEmpty(stored)) {
                return stored;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not determine the relay's own number", e);
        }
        return null;
    }

    /** Outcome of one group-MMS observation of a possible own number; see {@link #decideObservation}. */
    static final int OBSERVE_IGNORE = 0;
    static final int OBSERVE_PEND = 1;
    static final int OBSERVE_PROMOTE = 2;

    /**
     * Records that a matched group-MMS thread (recorded signature {@code signature}) points at
     * {@code candidate} as the relay's own number. Stores it for real only after a second, agreeing
     * observation from a DIFFERENT thread.
     *
     * <p>Why so cautious: a thread whose participant list lacks the relay, matched against an older
     * and smaller signature, makes a real member look like the one "extra" participant. Storing
     * that would refuse the member everywhere and purge them from the member list. Two different
     * threads agreeing is far harder to get wrong. MMS never overwrites a stored number either;
     * only the SIM does (see {@link #ownNumber}).
     */
    public static void observeThreadCandidate(Context context, String candidate, String signature) {
        String normalized = PhoneNumberUtils.normalize(candidate);
        if (TextUtils.isEmpty(normalized) || TextUtils.isEmpty(signature)) {
            return;
        }
        // ownNumber, not a raw read, so a number the SIM reports is stored before the candidate is weighed.
        String stored = ownNumber(context);
        if (!TextUtils.isEmpty(stored)) {
            if (!stored.equals(normalized)) {
                Log.w(TAG, "Thread's extra participant " + PhoneNumberUtils.mask(normalized)
                        + " differs from the stored own number " + PhoneNumberUtils.mask(stored) + "; keeping the stored one");
            }
            return;
        }
        SharedPreferences sp = prefs(context);
        String pendingNumber = sp.getString(KEY_PENDING_NUMBER, null);
        String pendingSignature = sp.getString(KEY_PENDING_SIGNATURE, null);
        switch (decideObservation(pendingNumber, pendingSignature, normalized, signature)) {
            case OBSERVE_PROMOTE:
                sp.edit().putString(KEY_OWN_NUMBER, normalized)
                        .remove(KEY_PENDING_NUMBER).remove(KEY_PENDING_SIGNATURE).apply();
                Log.i(TAG, "Own number learned from two group threads: " + PhoneNumberUtils.mask(normalized));
                break;
            case OBSERVE_PEND:
                sp.edit().putString(KEY_PENDING_NUMBER, normalized)
                        .putString(KEY_PENDING_SIGNATURE, signature).apply();
                Log.i(TAG, "Possible own number " + PhoneNumberUtils.mask(normalized) + " seen in one group thread; waiting for another");
                break;
            default:
                break;
        }
    }

    /**
     * The pure two-observation rule. Promote when the pending candidate is the same number but was
     * seen in a different thread; ignore a repeat from the same thread; otherwise (nothing pending,
     * or a disagreeing number) replace the pending candidate with this one. Pure Java, no Android.
     */
    static int decideObservation(String pendingNumber, String pendingSignature,
                                 String candidate, String signature) {
        if (pendingNumber == null || !pendingNumber.equals(candidate)) {
            return OBSERVE_PEND;
        }
        if (signature.equals(pendingSignature)) {
            return OBSERVE_IGNORE;
        }
        return OBSERVE_PROMOTE;
    }

    /** True only when the own number is known and {@code e164} normalizes to it. */
    public static boolean isOwnNumber(Context context, String e164) {
        String own = ownNumber(context);
        if (own == null) {
            return false;
        }
        String normalized = PhoneNumberUtils.normalize(e164);
        return own.equals(normalized);
    }

    /**
     * getLine1Number() is allowed with READ_SMS on many API levels, but it is often null or empty
     * (it depends on the SIM), and on some devices it throws SecurityException without
     * READ_PHONE_NUMBERS, which this app does not hold. Every failure just means "unknown".
     */
    private static String readLine1Number(Context context) {
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) {
                return null;
            }
            @SuppressWarnings({"MissingPermission", "HardwareIds"})
            String raw = tm.getLine1Number();
            return TextUtils.isEmpty(raw) ? null : PhoneNumberUtils.normalize(raw);
        } catch (SecurityException e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
