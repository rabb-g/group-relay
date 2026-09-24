package com.sh7411usa.jrelay.sms.mms;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Minimal ContentProvider over a single file in app-private cache storage.
 *
 * SmsManager.sendMultimediaMessage() takes a content:// Uri, not raw bytes --
 * this provider exists only to hand it one. It is not a general-purpose
 * content provider; query/insert/update/delete/getType are deliberately
 * unimplemented stubs because this spike never needs them.
 */
public class MmsFileProvider extends ContentProvider {

    private static final String TAG = "jRelay";
    private static final String AUTHORITY = "com.sh7411usa.jrelay.mmsfileprovider";
    private static final String CACHE_SUBDIR = "mms_spike_pdu";

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * Writes the given bytes to a file under getCacheDir()/mms_spike_pdu/
     * and returns a content:// Uri for this provider pointing at it.
     */
    public static Uri writeToCache(Context context, String fileName, byte[] bytes) throws IOException {
        File dir = new File(context.getCacheDir(), CACHE_SUBDIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create cache dir: " + dir);
        }
        File out = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(bytes);
        }
        Uri uri = Uri.parse("content://" + AUTHORITY + "/" + fileName);
        Log.i(TAG, "Wrote " + bytes.length + " bytes to " + out.getAbsolutePath() + " -> " + uri);
        return uri;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File dir = new File(getContext().getCacheDir(), CACHE_SUBDIR);
        File file = new File(dir, uri.getLastPathSegment());
        if (!file.exists()) {
            throw new FileNotFoundException("No such spike PDU file: " + file);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    // Deliberately unimplemented: this spike only ever needs openFile() to
    // hand SmsManager a readable Uri. No caller queries, inserts, updates,
    // or deletes rows through this provider.

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
