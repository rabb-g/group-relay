package com.sh7411usa.jrelay.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Schema note on {@code members.subgroup_id} (v8, phase 3 layer 1): stable, admin-assigned
 * grouping used to route group-MMS sends to ~9-person sub-groups instead of individual SMS to
 * everyone. NULL means unassigned and must remain a permanently safe, fully-functional state -
 * every existing member has NULL after this migration and the app must behave exactly as before
 * for them. See {@code docs/phase3-redesign.md}.
 *
 * <p>This table only records the assignment. It does not decide who gets assigned where (that is
 * separate, testable balancing logic) and it does not send anything. Whoever wires sending must
 * read docs/phase3-redesign.md &sect;4 and &sect;5.1 first: the moment a group MMS is sent to a
 * sub-group, that member's phone number becomes visible to the other eight, permanently and
 * irreversibly - the old thread survives on every handset even after a member is reassigned or
 * removed, and jRelay cannot recall or unsend it.
 */
public class DbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "jrelay.db";
    private static final int DB_VERSION = 8;

    public static final String TABLE_MEMBERS = "members";
    public static final String TABLE_MESSAGE_LOG = "message_log";
    public static final String TABLE_OUTBOX = "outbox";

    private static DbHelper instance;

    public static synchronized DbHelper getInstance(Context context) {
        if (instance == null) {
            instance = new DbHelper(context.getApplicationContext());
        }
        return instance;
    }

    private DbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_MEMBERS + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "phone_e164 TEXT UNIQUE NOT NULL," +
                "nickname TEXT NOT NULL," +
                "is_admin INTEGER NOT NULL DEFAULT 0," +
                "is_muted INTEGER NOT NULL DEFAULT 0," +
                "active INTEGER NOT NULL DEFAULT 1," +
                "added_by TEXT," +
                "created_at INTEGER NOT NULL," +
                "removed_at INTEGER," +
                "rate_limit_custom INTEGER NOT NULL DEFAULT 0," +
                "rate_burst_min INTEGER," +
                "rate_burst_max INTEGER," +
                "rate_min_wait INTEGER," +
                "rate_max_wait INTEGER," +
                "rate_initial_delay INTEGER," +
                "daily_limit_custom INTEGER NOT NULL DEFAULT 0," +
                "daily_limit_value INTEGER," +
                "daily_limit_bonus INTEGER NOT NULL DEFAULT 0," +
                "daily_limit_bonus_window_start INTEGER NOT NULL DEFAULT 0," +
                "failed_count INTEGER NOT NULL DEFAULT 0," +
                "last_post_received_id INTEGER," +
                "subgroup_id INTEGER" +
                ")");

        db.execSQL("CREATE TABLE " + TABLE_MESSAGE_LOG + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "member_id INTEGER," +
                "direction TEXT NOT NULL," +
                "category TEXT NOT NULL," +
                "body TEXT NOT NULL," +
                "timestamp INTEGER NOT NULL" +
                ")");

        db.execSQL("CREATE TABLE " + TABLE_OUTBOX + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "member_id INTEGER," +
                "phone_e164 TEXT NOT NULL," +
                "body TEXT NOT NULL," +
                "enqueued_at INTEGER NOT NULL," +
                "status TEXT NOT NULL DEFAULT 'PENDING'," +
                "attempts INTEGER NOT NULL DEFAULT 0," +
                "category TEXT," +
                "hold_until INTEGER NOT NULL DEFAULT 0," +
                "apply_salt INTEGER NOT NULL DEFAULT 0," +
                "last_result INTEGER," +
                "parts_pending INTEGER NOT NULL DEFAULT 0," +
                "handed_off_at INTEGER NOT NULL DEFAULT 0" +
                ")");

        createIndexes(db);
    }

    /**
     * Indexes for the query patterns MessageRepository and OutboxRepository actually run.
     * Kept in one place so onCreate (fresh install) and the version-7 migration (upgrade) create
     * an identical set - verified by diffing this method's statements against the version-7
     * onUpgrade block, which just calls it too.
     *
     * <ul>
     *   <li>{@code message_log(timestamp)} - {@code getRecent}'s {@code ORDER BY timestamp DESC
     *       LIMIT ?} (a full-table sort without it), plus the range scans in {@code countSince},
     *       {@code countRelayedSince}, and {@code countFailedSince}.
     *   <li>{@code message_log(member_id, timestamp)} - {@code countForMember} and
     *       {@code countForMemberSince}'s {@code member_id = ?} filters, {@code getRecentForMember}'s
     *       {@code member_id = ? ... ORDER BY timestamp DESC}, {@code lastActivityForMember}'s
     *       {@code MAX(timestamp) WHERE member_id = ?}, and {@code countRelayedForMemberSince}.
     *       Leading {@code member_id} serves the equality filter every one of these queries has;
     *       the trailing {@code timestamp} then satisfies the range/order-by without a second pass.
     *   <li>{@code outbox(status, hold_until)} - {@code takeBurst}'s
     *       {@code status = ? AND hold_until <= ?} and {@code takeReleasedRelayRows}'s
     *       {@code status = ? AND category = ? AND hold_until <= ?} (status is by far the more
     *       selective column here, so it leads).
     * </ul>
     *
     * No index was added for {@code members.subgroup_id} (v8). The queries against it -
     * {@code subgroup_id = ?}, {@code SELECT DISTINCT subgroup_id}, a {@code GROUP BY subgroup_id}
     * count, and {@code subgroup_id IS NULL} - all scan the {@code members} table, which this
     * deployment caps at roughly 100 rows (see {@code Prefs.getMaxMembers()}). A full scan of 100
     * rows is not worth the write-amplification of a permanent index; revisit only if that cap is
     * ever raised by an order of magnitude.
     */
    private static void createIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_message_log_timestamp ON " + TABLE_MESSAGE_LOG + "(timestamp)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_message_log_member_timestamp ON " + TABLE_MESSAGE_LOG + "(member_id, timestamp)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_outbox_status_hold ON " + TABLE_OUTBOX + "(status, hold_until)");
    }

    /** Migrations are additive so existing members, message history, and queued sends survive an app update. */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_MEMBERS + " ADD COLUMN rate_limit_custom INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_MEMBERS + " ADD COLUMN rate_burst_min INTEGER");
            db.execSQL("ALTER TABLE " + TABLE_MEMBERS + " ADD COLUMN rate_burst_max INTEGER");
            db.execSQL("ALTER TABLE " + TABLE_MEMBERS + " ADD COLUMN rate_min_wait INTEGER");
            db.execSQL("ALTER TABLE " + TABLE_MEMBERS + " ADD COLUMN rate_max_wait INTEGER");
            db.execSQL("ALTER TABLE " + TABLE_MEMBERS + " ADD COLUMN rate_initial_delay INTEGER");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE_MEMBERS + " ADD COLUMN daily_limit_custom INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_MEMBERS + " ADD COLUMN daily_limit_value INTEGER");
            db.execSQL("ALTER TABLE " + TABLE_MEMBERS + " ADD COLUMN daily_limit_bonus INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_MEMBERS + " ADD COLUMN daily_limit_bonus_window_start INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_MEMBERS + " ADD COLUMN failed_count INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_OUTBOX + " ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE " + TABLE_MEMBERS + " ADD COLUMN last_post_received_id INTEGER");
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE " + TABLE_OUTBOX + " ADD COLUMN category TEXT");
            db.execSQL("ALTER TABLE " + TABLE_OUTBOX + " ADD COLUMN hold_until INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_OUTBOX + " ADD COLUMN apply_salt INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE " + TABLE_OUTBOX + " ADD COLUMN last_result INTEGER");
            db.execSQL("ALTER TABLE " + TABLE_OUTBOX + " ADD COLUMN parts_pending INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_OUTBOX + " ADD COLUMN handed_off_at INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 7) {
            createIndexes(db);
        }
        if (oldVersion < 8) {
            // Nullable, additive: every pre-existing row gets NULL (unassigned), never 0 - see
            // Member#subgroupId and MemberRepository#fromCursor for how that is preserved on read.
            db.execSQL("ALTER TABLE " + TABLE_MEMBERS + " ADD COLUMN subgroup_id INTEGER");
        }
    }

    /** Permanently erases every member, message, and queued outbound message. Used only by "Disband Group". */
    public void wipeAllData() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_OUTBOX, null, null);
        db.delete(TABLE_MESSAGE_LOG, null, null);
        db.delete(TABLE_MEMBERS, null, null);
    }
}
