package dev.crossbar.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Read-only access to ROM files for emulators, used when no folder grant exists. */
public class RomProvider extends ContentProvider {
    static final String AUTHORITY = "dev.crossbar.launcher.roms";

    static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).path(f.getAbsolutePath()).build();
    }

    private static File fileFor(Uri uri) throws FileNotFoundException {
        String p = uri.getPath();
        if (p == null) throw new FileNotFoundException();
        File f = new File(p);
        try {
            String canon = f.getCanonicalPath();
            String root = Environment.getExternalStorageDirectory().getCanonicalPath();
            if (!canon.startsWith(root + "/") || canon.contains("/Android/data/")) throw new FileNotFoundException();
            File c = new File(canon);
            if (!c.isFile()) throw new FileNotFoundException();
            return c;
        } catch (IOException e) {
            throw new FileNotFoundException();
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, "mime_type", "last_modified", "document_id"};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            switch (cols[i]) {
                case OpenableColumns.DISPLAY_NAME: row[i] = f.getName(); break;
                case OpenableColumns.SIZE: row[i] = f.length(); break;
                case "mime_type": row[i] = "application/octet-stream"; break;
                case "last_modified": row[i] = f.lastModified(); break;
                case "document_id": row[i] = f.getAbsolutePath(); break;
                case "flags": row[i] = 0; break;
                default: row[i] = null;
            }
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
