package ai.jackknife.killayue;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** Lets other apps read a library track that the user chose to share. */
public class TrackProvider extends ContentProvider {
    static Uri uri(String fileName) {
        return Uri.parse("content://ai.jackknife.killayue.tracks/" + Uri.encode(fileName));
    }

    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) throw new FileNotFoundException("bad name");
        File f = new File(Store.library(getContext()), name);
        if (!f.isFile()) throw new FileNotFoundException(name);
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String n = String.valueOf(uri.getLastPathSegment());
        if (n.endsWith(".lrc")) return "text/plain";
        return n.endsWith(".wav") ? "audio/wav" : "audio/mpeg";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[] {"_display_name", "_size"});
        try {
            File f = file(uri);
            c.addRow(new Object[] {f.getName(), Long.valueOf(f.length())});
        } catch (FileNotFoundException e) {
            // unknown file: an empty cursor tells the caller there is nothing to read
        }
        return c;
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
