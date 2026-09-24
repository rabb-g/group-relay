package com.sh7411usa.jrelay.sms.mms;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only helpers over the system MMS provider (content://mms).
 *
 * jRelay sends group MMS to sub-groups; replies inside those threads go
 * peer-to-peer to every participant in that sub-group, not back through
 * jRelay. This class is how jRelay learns what was said, by reading the
 * reply out of the device's own MMS store, so it can relay it on to the
 * members who were not in that sub-group.
 *
 * This class performs NO writes against the MMS provider (inserts/updates/
 * deletes there are reserved to the default SMS app, which jRelay is not)
 * and makes NO policy decisions -- it only returns plain data objects.
 *
 * Row shapes below were verified with `adb shell content query` against a
 * real inbound reply (Samsung SM-F711U, Android 15, AT&T):
 *  - content://mms, msg_box = 1 (inbox), m_type = 132 (m-retrieve-conf,
 *    fully retrieved). m_type = 130 (m-notification-ind) is a placeholder
 *    still waiting on download and is skipped -- reading it now would
 *    return an empty body, not a missing one.
 *  - content://mms/<id>/addr: type = 137 is PduHeaders.FROM (sender),
 *    type = 151 is PduHeaders.TO (recipient; may repeat).
 *  - content://mms/part: mid = <id> AND ct = 'text/plain' holds the body,
 *    normally inline in the `text` column. An application/smil part is
 *    always present alongside it, so the part MUST be selected by
 *    content type, never taken as "the first part".
 */
public final class MmsReader {

    private static final String TAG = "MmsReader";

    private static final Uri MMS_URI = Telephony.Mms.CONTENT_URI; // content://mms
    private static final String MMS_PART_PATH = "part";

    private static final int MSG_BOX_INBOX = 1;
    private static final int M_TYPE_RETRIEVE_CONF = 132; // fully retrieved, body readable
    private static final int M_TYPE_NOTIFICATION_IND = 130; // placeholder, not yet downloaded

    private static final int ADDR_TYPE_FROM = 137; // PduHeaders.FROM
    private static final int ADDR_TYPE_TO = 151; // PduHeaders.TO

    private static final String CT_TEXT_PLAIN = "text/plain";

    private final Context context;

    public MmsReader(Context context) {
        this.context = context.getApplicationContext();
    }

    /** One fully-retrieved inbound MMS: who sent it, who else already got it, and its text. */
    public static final class InboundMms {
        public final long id;
        public final long timestampMs;
        public final String sender;
        public final List<String> recipients;
        public final String body;

        public InboundMms(long id, long timestampMs, String sender, List<String> recipients, String body) {
            this.id = id;
            this.timestampMs = timestampMs;
            this.sender = sender;
            this.recipients = recipients;
            this.body = body;
        }
    }

    /**
     * Result of a read attempt against the MMS provider. A permission failure
     * and "nothing new" must never look the same to the caller -- one is a
     * broken relay, the other is a quiet Tuesday.
     */
    public static final class ReadResult {
        public final List<InboundMms> messages;
        public final boolean permissionDenied;

        private ReadResult(List<InboundMms> messages, boolean permissionDenied) {
            this.messages = messages;
            this.permissionDenied = permissionDenied;
        }

        static ReadResult ok(List<InboundMms> messages) {
            return new ReadResult(messages, false);
        }

        static ReadResult denied() {
            return new ReadResult(Collections.<InboundMms>emptyList(), true);
        }
    }

    /**
     * Fetches ids (and timestamps) of fully-retrieved inbox MMS received strictly
     * after {@code sinceEpochSeconds}. Telephony.Mms.DATE is stored in seconds.
     * Notification-only placeholders (m_type 130) are skipped, not returned.
     */
    public ReadResult fetchRecentInboxIds(long sinceEpochSeconds) {
        ContentResolver resolver = context.getContentResolver();
        List<InboundMms> ids = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = resolver.query(
                    MMS_URI,
                    new String[]{Telephony.Mms._ID, Telephony.Mms.DATE},
                    Telephony.Mms.MESSAGE_BOX + " = ? AND " + Telephony.Mms.MESSAGE_TYPE + " = ? AND " + Telephony.Mms.DATE + " > ?",
                    new String[]{
                            String.valueOf(MSG_BOX_INBOX),
                            String.valueOf(M_TYPE_RETRIEVE_CONF),
                            String.valueOf(sinceEpochSeconds)
                    },
                    Telephony.Mms.DATE + " ASC");
            if (cursor == null) {
                // Some OEM builds return null instead of throwing; treat as an
                // empty-but-readable result rather than crashing the caller.
                Log.w(TAG, "content://mms query returned null cursor");
                return ReadResult.ok(ids);
            }
            int idCol = cursor.getColumnIndex(Telephony.Mms._ID);
            int dateCol = cursor.getColumnIndex(Telephony.Mms.DATE);
            if (idCol < 0 || dateCol < 0) {
                Log.e(TAG, "content://mms cursor missing expected columns (_id=" + idCol + ", date=" + dateCol + ")");
                return ReadResult.ok(ids);
            }
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                long dateSeconds = cursor.getLong(dateCol);
                ids.add(new InboundMms(id, dateSeconds * 1000L, null, Collections.<String>emptyList(), null));
            }
            return ReadResult.ok(ids);
        } catch (SecurityException e) {
            Log.e(TAG, "READ_SMS denied while querying content://mms -- cannot read inbound MMS replies", e);
            return ReadResult.denied();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Reads sender, full recipient list, and text body for one already-known
     * MMS id (typically one returned by {@link #fetchRecentInboxIds}).
     * Returns null if the message, its addresses, or its text part cannot be
     * found -- callers should treat that as "skip this one", not as fatal.
     */
    public InboundMms readMessage(long id, long timestampMs) {
        try {
            String sender = null;
            List<String> recipients = new ArrayList<>();
            Cursor addrCursor = null;
            try {
                Uri addrUri = Uri.parse(MMS_URI.toString() + "/" + id + "/addr");
                addrCursor = context.getContentResolver().query(
                        addrUri,
                        new String[]{Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE},
                        null, null, null);
                if (addrCursor == null) {
                    Log.w(TAG, "content://mms/" + id + "/addr query returned null cursor");
                } else {
                    int addressCol = addrCursor.getColumnIndex(Telephony.Mms.Addr.ADDRESS);
                    int typeCol = addrCursor.getColumnIndex(Telephony.Mms.Addr.TYPE);
                    if (addressCol < 0 || typeCol < 0) {
                        Log.e(TAG, "content://mms/" + id + "/addr cursor missing expected columns");
                    } else {
                        while (addrCursor.moveToNext()) {
                            String address = addrCursor.getString(addressCol);
                            int type = addrCursor.getInt(typeCol);
                            if (address == null) {
                                continue;
                            }
                            if (type == ADDR_TYPE_FROM) {
                                sender = address;
                            } else if (type == ADDR_TYPE_TO) {
                                recipients.add(address);
                            }
                        }
                    }
                }
            } finally {
                if (addrCursor != null) {
                    addrCursor.close();
                }
            }

            if (sender == null) {
                Log.w(TAG, "no FROM address found for mms id=" + id + "; message has no parts missing check to run, skipping");
                return null;
            }

            String body = readTextBody(id);
            if (body == null) {
                Log.w(TAG, "no text/plain part found (or readable) for mms id=" + id);
                return null;
            }

            return new InboundMms(id, timestampMs, sender, recipients, body);
        } catch (SecurityException e) {
            Log.e(TAG, "READ_SMS denied while reading mms id=" + id, e);
            return null;
        }
    }

    /**
     * Finds the text/plain part for this message and returns its body. Body
     * is normally inline in the `text` column; if that's null (carrier-
     * dependent), falls back to streaming content://mms/part/<part_id>.
     */
    private String readTextBody(long id) {
        Cursor partCursor = null;
        try {
            Uri partUri = Uri.withAppendedPath(MMS_URI, MMS_PART_PATH);
            partCursor = context.getContentResolver().query(
                    partUri,
                    new String[]{Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT},
                    Telephony.Mms.Part.MSG_ID + " = ? AND " + Telephony.Mms.Part.CONTENT_TYPE + " = ?",
                    new String[]{String.valueOf(id), CT_TEXT_PLAIN},
                    null);
            if (partCursor == null) {
                Log.w(TAG, "content://mms/part query returned null cursor for mms id=" + id);
                return null;
            }
            int partIdCol = partCursor.getColumnIndex(Telephony.Mms.Part._ID);
            int textCol = partCursor.getColumnIndex(Telephony.Mms.Part.TEXT);
            if (partIdCol < 0 || textCol < 0) {
                Log.e(TAG, "content://mms/part cursor missing expected columns for mms id=" + id);
                return null;
            }
            if (!partCursor.moveToFirst()) {
                // No text/plain part at all -- e.g. an MMS with only an image.
                return null;
            }
            String inline = partCursor.getString(textCol);
            if (inline != null) {
                return inline;
            }
            long partId = partCursor.getLong(partIdCol);
            return readTextPartFromStream(partId);
        } finally {
            if (partCursor != null) {
                partCursor.close();
            }
        }
    }

    private String readTextPartFromStream(long partId) {
        Uri partContentUri = Uri.parse(MMS_URI.toString() + "/" + MMS_PART_PATH + "/" + partId);
        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(partContentUri);
            if (in == null) {
                Log.w(TAG, "openInputStream returned null for mms part id=" + partId);
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } catch (SecurityException e) {
            Log.e(TAG, "READ_SMS denied while streaming mms part id=" + partId, e);
            return null;
        } catch (IOException e) {
            Log.e(TAG, "failed reading mms part id=" + partId + " from stream", e);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // best-effort close
                }
            }
        }
    }
}
