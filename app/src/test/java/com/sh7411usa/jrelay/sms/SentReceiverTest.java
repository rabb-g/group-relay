package com.sh7411usa.jrelay.sms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.telephony.SmsManager;

import com.sh7411usa.jrelay.R;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Covers {@link SentReceiver#stringResFor}, the pure result-code-to-string mapping. Everything
 * else in SentReceiver needs a live Android environment (BroadcastReceiver, PendingIntent, the
 * DB-backed repositories) and isn't unit-testable here.
 */
public class SentReceiverTest {

    // Every code stringResFor is expected to recognize by name, not fall through to "unknown".
    private static final int[] KNOWN_CODES = {
            Activity.RESULT_OK,
            SentReceiver.RESULT_NOT_SENT,
            SmsManager.RESULT_ERROR_GENERIC_FAILURE,
            SmsManager.RESULT_ERROR_RADIO_OFF,
            SmsManager.RESULT_ERROR_NO_SERVICE,
            SmsManager.RESULT_ERROR_NULL_PDU,
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED,
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED,
    };

    // ---- stringResFor: each known code maps to its own string ----

    @Test
    public void stringResFor_resultOkMapsToSendResultOk() {
        assertEquals(R.string.send_result_ok, SentReceiver.stringResFor(Activity.RESULT_OK));
    }

    @Test
    public void stringResFor_resultNotSentMapsToSendResultNotSent() {
        assertEquals(R.string.send_result_not_sent, SentReceiver.stringResFor(SentReceiver.RESULT_NOT_SENT));
    }

    @Test
    public void stringResFor_genericFailureMapsToSendResultGeneric() {
        assertEquals(R.string.send_result_generic,
                SentReceiver.stringResFor(SmsManager.RESULT_ERROR_GENERIC_FAILURE));
    }

    @Test
    public void stringResFor_radioOffMapsToSendResultRadioOff() {
        assertEquals(R.string.send_result_radio_off,
                SentReceiver.stringResFor(SmsManager.RESULT_ERROR_RADIO_OFF));
    }

    @Test
    public void stringResFor_noServiceMapsToSendResultNoService() {
        assertEquals(R.string.send_result_no_service,
                SentReceiver.stringResFor(SmsManager.RESULT_ERROR_NO_SERVICE));
    }

    @Test
    public void stringResFor_nullPduMapsToSendResultNullPdu() {
        assertEquals(R.string.send_result_null_pdu, SentReceiver.stringResFor(SmsManager.RESULT_ERROR_NULL_PDU));
    }

    @Test
    public void stringResFor_limitExceededMapsToSendResultLimitExceeded() {
        assertEquals(R.string.send_result_limit_exceeded,
                SentReceiver.stringResFor(SmsManager.RESULT_ERROR_LIMIT_EXCEEDED));
    }

    @Test
    public void stringResFor_shortCodeNotAllowedMapsToSendResultShortCodeNotAllowed() {
        assertEquals(R.string.send_result_short_code_not_allowed,
                SentReceiver.stringResFor(SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED));
    }

    // ---- the collision this phase almost shipped ----

    @Test
    public void stringResFor_notSentDiffersFromOk() {
        // Regression test: RESULT_NOT_SENT was briefly -1 during development, the same value as
        // Activity.RESULT_OK, which would have rendered a message that never reached the radio as
        // "Delivered to carrier." Do NOT let RESULT_NOT_SENT's value drift back to -1 (or to
        // anything else that maps to send_result_ok).
        assertNotEquals(SentReceiver.stringResFor(Activity.RESULT_OK),
                SentReceiver.stringResFor(SentReceiver.RESULT_NOT_SENT));
    }

    // ---- no two known codes silently share a string ----

    @Test
    public void stringResFor_allKnownCodesMapToDistinctStrings() {
        Set<Integer> resIds = new HashSet<>();
        for (int code : KNOWN_CODES) {
            resIds.add(SentReceiver.stringResFor(code));
        }
        // Any duplicate here means a copy-paste error mapped two different result codes to the
        // same explanatory string, hiding one reason behind another's label.
        assertEquals(KNOWN_CODES.length, resIds.size());
    }

    // ---- unrecognised codes fall through to send_result_unknown, never a named reason ----

    @Test
    public void stringResFor_zeroIsUnknown() {
        assertEquals(R.string.send_result_unknown, SentReceiver.stringResFor(0));
    }

    @Test
    public void stringResFor_largePositiveIsUnknown() {
        assertEquals(R.string.send_result_unknown, SentReceiver.stringResFor(999_999));
    }

    @Test
    public void stringResFor_negativeCodeThatIsNeitherSentinelIsUnknown() {
        assertEquals(R.string.send_result_unknown, SentReceiver.stringResFor(-42));
    }

    // ---- RESULT_NOT_SENT's value itself ----

    @Test
    public void resultNotSent_isExactlyNegative1000() {
        assertEquals(-1000, SentReceiver.RESULT_NOT_SENT);
    }

    @Test
    public void resultNotSent_isOutsideTheResultErrorRange() {
        // RESULT_ERROR_* constants are all small positives; RESULT_NOT_SENT must stay clear of
        // that range too, not just clear of RESULT_OK.
        assertTrue(SentReceiver.RESULT_NOT_SENT < 0);
        assertNotEquals(SmsManager.RESULT_ERROR_GENERIC_FAILURE, SentReceiver.RESULT_NOT_SENT);
        assertNotEquals(SmsManager.RESULT_ERROR_RADIO_OFF, SentReceiver.RESULT_NOT_SENT);
        assertNotEquals(SmsManager.RESULT_ERROR_NO_SERVICE, SentReceiver.RESULT_NOT_SENT);
        assertNotEquals(SmsManager.RESULT_ERROR_NULL_PDU, SentReceiver.RESULT_NOT_SENT);
        assertNotEquals(SmsManager.RESULT_ERROR_LIMIT_EXCEEDED, SentReceiver.RESULT_NOT_SENT);
        assertNotEquals(SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SentReceiver.RESULT_NOT_SENT);
    }
}
