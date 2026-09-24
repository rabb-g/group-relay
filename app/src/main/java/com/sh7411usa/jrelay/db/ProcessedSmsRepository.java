package com.sh7411usa.jrelay.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * The {@code processed_sms} seen-set: every inbound SMS jRelay has handled, from either the live
 * SMS_RECEIVED broadcast ({@code SmsReceiver}) or the content://sms catch-up scan
 * ({@code SmsCatchUp}). Both paths claim a message here BEFORE handing it to
 * {@code CommandProcessor.handleIncoming}, and only hand it on if the claim is new. That is
 * mark-first, at-most-once: a crash between the claim and the relay loses one post, which is
 * recoverable; the reverse order risks one post reaching ~100 people twice.
 *
 * <p>Keys are built by {@code SmsCatchUp}; this class only stores them.
 */
public class ProcessedSmsRepository {

    private final DbHelper dbHelper;

    public ProcessedSmsRepository(Context context) {
        dbHelper = DbHelper.getInstance(context);
    }

    /**
     * Atomically: refuse if any of {@code refuseIfAnyExists} is already recorded; otherwise
     * INSERT OR IGNORE {@code dedupeKey} and, only if that insert was new, also record
     * {@code alsoRecord}. Returns true only when the caller now owns this message and must process
     * it. Runs in one transaction, so a live claim and a catch-up claim of the same message can
     * never both see "not yet recorded" -- SQLite serializes write transactions on the one
     * connection {@link DbHelper#getInstance} hands out.
     */
    public boolean claim(String dedupeKey, String[] refuseIfAnyExists, String[] alsoRecord, String source) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (String key : refuseIfAnyExists) {
                if (exists(db, key)) {
                    db.setTransactionSuccessful();
                    return false;
                }
            }
            if (!insertOrIgnore(db, dedupeKey, source)) {
                db.setTransactionSuccessful();
                return false;
            }
            for (String key : alsoRecord) {
                insertOrIgnore(db, key, source);
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Deletes rows processed before {@code cutoffMillis}. Safe as long as the cutoff is well past
     * the catch-up lookback cap, since nothing older than that cap is ever examined again.
     */
    public void pruneOlderThan(long cutoffMillis) {
        dbHelper.getWritableDatabase().delete(DbHelper.TABLE_PROCESSED_SMS,
                "processed_at < ?", new String[]{String.valueOf(cutoffMillis)});
    }

    private static boolean insertOrIgnore(SQLiteDatabase db, String key, String source) {
        ContentValues cv = new ContentValues();
        cv.put("dedupe_key", key);
        cv.put("processed_at", System.currentTimeMillis());
        cv.put("source", source);
        return db.insertWithOnConflict(DbHelper.TABLE_PROCESSED_SMS, null, cv,
                SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    private static boolean exists(SQLiteDatabase db, String key) {
        Cursor c = db.query(DbHelper.TABLE_PROCESSED_SMS, new String[]{"dedupe_key"},
                "dedupe_key = ?", new String[]{key}, null, null, null);
        boolean found = c.moveToFirst();
        c.close();
        return found;
    }
}
