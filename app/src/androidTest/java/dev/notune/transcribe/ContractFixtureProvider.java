package dev.notune.transcribe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/** Private test documents; the caller activity grants only these exact fixture Uris. */
public final class ContractFixtureProvider extends ContentProvider {
    static final String AUTHORITY = "app.offlinespeechtotext.validation.test.contract_fixture";
    static Uri uri(String name) { return Uri.parse("content://" + AUTHORITY + "/" + name); }
    static File file(android.content.Context context, String name) { return new File(context.getCacheDir(), "contract-" + name); }
    @Override public boolean onCreate() { return true; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (!AUTHORITY.equals(uri.getAuthority()) || !"content".equals(uri.getScheme())
                || !("output.txt".equals(name) || "report.json".equals(name))
                || !uri.getPath().equals("/" + name)) throw new FileNotFoundException("Unknown fixture");
        boolean write = "w".equals(mode) || "wt".equals(mode);
        if (!(write || "r".equals(mode)) || (write && !"output.txt".equals(name))) throw new FileNotFoundException("Unsupported fixture access");
        return ParcelFileDescriptor.open(file(getContext(), name), write
                ? ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                : ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
