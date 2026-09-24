package com.sh7411usa.jrelay.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * Tracks the two things needed to bridge inbound group MMS safely:
 *
 * <ul>
 *   <li>{@code known_threads} - every recipient-set signature the app has ever sent a group MMS
 *       to, mapped to the sub-group it belongs to. An inbound group MMS is only bridged if its
 *       participant set matches one of these, i.e. a thread the app itself created. That is what
 *       stops a private group chat that happens to include the relay phone from being broadcast
 *       to everyone. Signatures are kept forever, never pruned, because people keep typing in an
 *       old thread after a member joins or leaves it.
 *   <li>{@code ingested_mms} - the seen-set of inbound MMS rows from {@code content://mms} already
 *       processed, so a message is bridged at most once.
 * </ul>
 */
public class ThreadRepository {

    private final DbHelper dbHelper;

    public ThreadRepository(Context context) {
        dbHelper = DbHelper.getInstance(context);
    }

    /** INSERT OR REPLACE (signature, subgroupId, now). Ignores null/empty signature. */
    public void recordThread(String signature, long subgroupId) {
        if (signature == null || signature.isEmpty()) {
            return;
        }
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("signature", signature);
        cv.put("subgroup_id", subgroupId);
        cv.put("last_used_at", System.currentTimeMillis());
        db.insertWithOnConflict(DbHelper.TABLE_KNOWN_THREADS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** subgroup id for this exact signature, or null. */
    public Long findSubgroupForSignature(String signature) {
        if (signature == null || signature.isEmpty()) {
            return null;
        }
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DbHelper.TABLE_KNOWN_THREADS, new String[]{"subgroup_id"},
                "signature = ?", new String[]{signature}, null, null, null);
        Long result = null;
        if (c.moveToFirst()) {
            result = c.getLong(0);
        }
        c.close();
        return result;
    }

    /** INSERT OR IGNORE; returns true ONLY if this call newly inserted the row (i.e. first time seen). */
    public boolean markIngested(long mmsId, String outcome) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("mms_id", mmsId);
        cv.put("ingested_at", System.currentTimeMillis());
        cv.put("outcome", outcome);
        long rowId = db.insertWithOnConflict(DbHelper.TABLE_INGESTED_MMS, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        return rowId != -1;
    }

    public boolean isIngested(long mmsId) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.query(DbHelper.TABLE_INGESTED_MMS, new String[]{"mms_id"},
                "mms_id = ?", new String[]{String.valueOf(mmsId)}, null, null, null);
        boolean exists = c.moveToFirst();
        c.close();
        return exists;
    }
}
