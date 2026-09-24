package com.sh7411usa.jrelay.sms.mms;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Hand-rolled composer for a single WSP/MMS "m-send-req" PDU carrying one
 * text/plain part addressed to multiple recipients via repeated "To" headers.
 *
 * This class does NOT vendor AOSP's PduComposer/SendReq/PduHeaders hierarchy.
 * It only encodes the exact fields this one spike needs, each annotated with
 * its byte value and a pointer to what it means, so a failed send can be
 * diagnosed field-by-field against the MMS Encapsulation spec (WAP-209-MMSEncapsulation)
 * and WSP spec (WAP-230-WSP) instead of by reverse-engineering this code.
 *
 * Pure Java, no Android APIs, no third-party dependencies -- so the output
 * bytes can be reasoned about (and unit-tested) in isolation from the device
 * and from ContentResolver/Telephony plumbing.
 */
public final class MmsPduWriter {

    // ---- WSP/MMS field codes used by this PDU (WAP-209 table for MMS headers) ----
    private static final int FIELD_MESSAGE_TYPE = 0x8C; // X-Mms-Message-Type
    private static final int FIELD_TRANSACTION_ID = 0x98; // X-Mms-Transaction-Id
    private static final int FIELD_MMS_VERSION = 0x8D; // X-Mms-MMS-Version
    private static final int FIELD_FROM = 0x89; // From
    private static final int FIELD_TO = 0x97; // To
    private static final int FIELD_CONTENT_TYPE = 0x84; // Content-Type (MUST be last header)

    // ---- Values for the fields above ----
    private static final int MESSAGE_TYPE_SEND_REQ = 0x80; // m-send-req
    private static final int MMS_VERSION_1_2 = 0x92; // short-integer encoding of version 1.2
    private static final int FROM_INSERT_ADDRESS_TOKEN = 0x81; // "insert-address-token": OS fills in the SIM's own number
    private static final int WELL_KNOWN_MULTIPART_MIXED = 0x23; // application/vnd.wap.multipart.mixed
    private static final int WELL_KNOWN_TEXT_PLAIN = 0x03; // text/plain
    private static final int PARAM_CHARSET = 0x81; // charset parameter field code
    private static final int CHARSET_UTF_8 = 0xEA; // IANA MIBenum 106 = UTF-8

    private MmsPduWriter() {
    }

    /**
     * Builds the bytes of an m-send-req PDU with one text/plain part addressed
     * to every recipient in {@code recipientsE164} via one "To" header per
     * recipient.
     *
     * @param recipientsE164 recipient phone numbers, e.g. "+15551234567". Each
     *                       is normalised to "<number>/TYPE=PLMN" if that suffix
     *                       is not already present. Must not be null/empty.
     * @param textBody       the message body (UTF-8). Must not be null/empty.
     * @param transactionId  the X-Mms-Transaction-Id value, an opaque ASCII token.
     * @return the raw PDU bytes.
     */
    public static byte[] buildSendReq(List<String> recipientsE164, String textBody, String transactionId) {
        if (recipientsE164 == null || recipientsE164.isEmpty()) {
            throw new IllegalArgumentException("recipientsE164 must not be null/empty");
        }
        if (textBody == null || textBody.isEmpty()) {
            throw new IllegalArgumentException("textBody must not be null/empty");
        }
        if (transactionId == null || transactionId.isEmpty()) {
            throw new IllegalArgumentException("transactionId must not be null/empty");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            // ---------------- HEADERS ----------------

            // 1) X-Mms-Message-Type = m-send-req (0x80).
            //    Field code 0x8C is the WSP "short-cut" application-header
            //    encoding for X-Mms-Message-Type; 0x80 marks this PDU as a
            //    send request (as opposed to m-notification-ind, etc.).
            out.write(FIELD_MESSAGE_TYPE);
            out.write(MESSAGE_TYPE_SEND_REQ);

            // 2) X-Mms-Transaction-Id = <transactionId>, NUL-terminated text-string.
            //    Field code 0x98. Value is a plain ASCII token the sender picks
            //    to correlate this request with its m-send-conf response.
            out.write(FIELD_TRANSACTION_ID);
            writeTextString(out, transactionId);

            // 3) X-Mms-MMS-Version = 1.2, short-integer 0x92 (0x80 | 0x12).
            //    Field code 0x8D. High bit of the value byte set = short-integer
            //    encoding; low 7 bits (0x12) = version 1.2 per MMS encapsulation spec.
            out.write(FIELD_MMS_VERSION);
            out.write(MMS_VERSION_1_2);

            // 4) From = insert-address-token, NOT a literal number.
            //    Field code 0x89. Value is a value-length of 1 (0x01) followed
            //    by a single byte 0x81, the well-known "insert-address-token"
            //    address-present-token that tells the platform to substitute
            //    the SIM's own MSISDN. We deliberately do not put a real number
            //    here -- the OS/carrier fills it in.
            out.write(FIELD_FROM);
            out.write(0x01); // value-length: 1 byte follows
            out.write(FROM_INSERT_ADDRESS_TOKEN);

            // 5) To, once per recipient -- THIS is the line the whole spike
            //    turns on. Emitting field code 0x97 repeatedly, once per
            //    recipient, each with its own text-string address, is what
            //    makes this ONE multi-recipient m-send-req rather than N
            //    separate single-recipient messages. If the platform collapses
            //    these into a single group MMS thread, sub-group delivery
            //    (~9 recipients/PDU) works; if it doesn't, the whole "cut 100
            //    sends to 12" design is dead. Each value is a text-string of
            //    the form "+1XXXXXXXXXX/TYPE=PLMN" (PLMN = the address is a
            //    normal telephone number per WAP addressing).
            for (String recipient : recipientsE164) {
                out.write(FIELD_TO);
                writeTextString(out, normalizeRecipient(recipient));
            }

            // 6) Content-Type -- MUST be the last header; the multipart body
            //    follows immediately after its value with no further headers.
            //    Field code 0x84. Value is short-integer 0xA3 (0x80 | 0x23),
            //    the well-known encoding for application/vnd.wap.multipart.mixed.
            //    (A "real" encoder would also emit a boundary/start parameter
            //    here for multipart/related; multipart/mixed with a single
            //    part needs none.)
            out.write(FIELD_CONTENT_TYPE);
            out.write(0x80 | WELL_KNOWN_MULTIPART_MIXED);

            // ---------------- BODY: multipart.mixed with one text/plain part ----------------

            byte[] bodyBytes = textBody.getBytes("UTF-8");

            // Part-entry-count, uintvar. We always emit exactly one part.
            writeUintvar(out, 1);

            // This one part's per-part Content-Type + headers:
            //   0x03 -> value-length 3 (three bytes follow: 0x83 0x81 0xEA)
            //   0x83 -> short-integer well-known content-type text/plain (0x80 | 0x03)
            //   0x81 -> "charset" parameter field code
            //   0xEA -> short-integer charset value, IANA MIBenum 106 = UTF-8 (0x80 | 0x6A)
            byte[] partHeaders = new byte[] {
                    0x03,
                    (byte) (0x80 | WELL_KNOWN_TEXT_PLAIN),
                    (byte) PARAM_CHARSET,
                    (byte) CHARSET_UTF_8
            };

            // HeadersLen, uintvar: length of partHeaders above (4).
            writeUintvar(out, partHeaders.length);
            // DataLen, uintvar: length of the UTF-8 body bytes.
            writeUintvar(out, bodyBytes.length);
            // Then HeadersLen bytes of content-type/headers, then DataLen bytes of data.
            out.write(partHeaders);
            out.write(bodyBytes);
        } catch (IOException e) {
            // ByteArrayOutputStream never actually throws; this satisfies write()'s signature.
            throw new RuntimeException(e);
        }

        return out.toByteArray();
    }

    /**
     * Appends "/TYPE=PLMN" to a recipient number if not already present.
     * PLMN = Public Land Mobile Network, the WAP address-type token meaning
     * "this is an ordinary telephone number" (as opposed to e.g. an IP or
     * shortcode address type).
     */
    private static String normalizeRecipient(String recipient) {
        if (recipient == null || recipient.isEmpty()) {
            throw new IllegalArgumentException("recipient must not be null/empty");
        }
        if (recipient.toUpperCase(Locale.US).contains("/TYPE=PLMN")) {
            return recipient;
        }
        return recipient + "/TYPE=PLMN";
    }

    /**
     * Encodes a text-string: the ASCII/UTF-8 bytes of {@code value} followed
     * by a single NUL (0x00) terminator. Per WAP-230 5.2.9, if the first byte
     * of the string would itself be >= 0x80 (i.e. could be confused with a
     * short-integer/other token), it must be prefixed with the "Quote"
     * character 0x7F. None of our values (transaction IDs, "+1.../TYPE=PLMN"
     * addresses) start with a byte >= 0x80, so that prefix is never emitted
     * here -- noted for anyone extending this to other field values.
     */
    private static void writeTextString(ByteArrayOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes("UTF-8");
        if (bytes.length > 0 && (bytes[0] & 0xFF) >= 0x80) {
            out.write(0x7F); // Quote prefix, per spec -- not expected to trigger for our inputs.
        }
        out.write(bytes);
        out.write(0x00); // NUL terminator
    }

    /**
     * Encodes a non-negative integer as a WSP uintvar: 7 bits of value per
     * byte, most-significant bit set on every byte except the last.
     * Examples: 0 -> 0x00; 127 (0x7F) -> 0x7F; 128 -> 0x81 0x00.
     */
    private static void writeUintvar(ByteArrayOutputStream out, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("uintvar value must be non-negative: " + value);
        }
        // Collect 7-bit groups, least-significant first, then emit most-significant first.
        int[] groups = new int[10]; // ample for any long
        int count = 0;
        long v = value;
        do {
            groups[count++] = (int) (v & 0x7F);
            v >>>= 7;
        } while (v != 0);

        for (int i = count - 1; i >= 0; i--) {
            int b = groups[i];
            if (i != 0) {
                b |= 0x80; // continuation bit set on every byte except the last emitted
            }
            out.write(b);
        }
    }
}
