package dev.aura.player;

import android.provider.*;
import android.database.*;
import android.os.*;
import java.io.*;
import java.nio.*;

/** Synthetic SAF provider installed only with the test APK. */
public class ScanDocuments extends android.content.ContentProvider {
    private final java.util.concurrent.atomic.AtomicInteger opens =
            new java.util.concurrent.atomic.AtomicInteger();
    private boolean photos, failB, changed, unknown;
    private final java.util.Map<String, Integer> byId =
            new java.util.concurrent.ConcurrentHashMap<>();
    private int delay;

    @Override
    public boolean onCreate() {
        getContext()
                .grantUriPermission(
                        "dev.aura.player",
                        android.net.Uri.parse("content://dev.aura.player.test.documents"),
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("test.reset".equals(method)) {
            opens.set(0);
            byId.clear();
            photos = false;
            unknown = false;
            failB = false;
            changed = false;
            delay = 0;
        }
        if ("test.photos".equals(method)) photos = true;
        if ("test.unknown".equals(method)) unknown = true;
        if ("test.change".equals(method)) changed = true;
        if ("test.fail".equals(method)) failB = true;
        if ("test.slow".equals(method)) delay = 500;
        Bundle b = new Bundle();
        b.putInt("opens", opens.get());
        for (var entry : byId.entrySet()) b.putInt("open:" + entry.getKey(), entry.getValue());
        if (method.startsWith("test.")) return b;
        return super.call(method, arg, extras);
    }

    private String[] columns(String[] projection) {
        return projection != null
                ? projection
                : new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_FLAGS,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                };
    }

    private void add(MatrixCursor c, String id, boolean dir) {
        var row = c.newRow();
        for (String column : c.getColumnNames())
            switch (column) {
                case "document_id" -> row.add(id);
                case "_display_name" -> row.add(id.substring(id.lastIndexOf('/') + 1));
                case "mime_type" ->
                        row.add(
                                dir
                                        ? DocumentsContract.Document.MIME_TYPE_DIR
                                        : id.endsWith(".png") ? "image/png" : "audio/wav");
                case "flags" -> row.add(0);
                case "_size" -> row.add(unknown ? null : dir ? 0 : 8864);
                case "last_modified" -> row.add(unknown ? null : 123456789L);
                default -> row.add(null);
            }
    }

    public Cursor queryRoots(String[] projection) {
        MatrixCursor c =
                new MatrixCursor(new String[] {"root_id", "document_id", "title", "flags"});
        c.addRow(
                new Object[] {
                    "A", "A", "Test music A", DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
                });
        c.addRow(
                new Object[] {
                    "B", "B", "Test music B", DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
                });
        return c;
    }

    public Cursor queryDocument(String id, String[] projection) {
        MatrixCursor c = new MatrixCursor(columns(projection));
        add(c, id, !id.endsWith(".wav"));
        return c;
    }

    public boolean isChildDocument(String parent, String child) {
        return child.startsWith(parent + "/");
    }

    public Cursor queryChildDocuments(String parent, String[] projection, String sort)
            throws FileNotFoundException {
        if (failB && parent.equals("B"))
            throw new FileNotFoundException("Synthetic disconnected folder");
        if (delay > 0)
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        MatrixCursor c = new MatrixCursor(columns(projection));
        if (parent.equals("A")) {
            add(c, changed ? "A/new.wav" : "A/a.wav", false);
            add(c, "A/sub", true);
        }
        if (parent.equals("A/sub")) {
            add(c, "A/sub/b.wav", false);
            if (photos) add(c, "A/sub/cover.png", false);
        }
        if (parent.equals("B")) add(c, "B/c.wav", false);
        return c;
    }

    public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal cancel)
            throws FileNotFoundException {
        opens.incrementAndGet();
        byId.merge(id, 1, Integer::sum);
        if (id.endsWith(".png")) {
            File image = new File(getContext().getCacheDir(), "scan-cover.png");
            if (!image.isFile())
                try {
                    var bitmap =
                            android.graphics.Bitmap.createBitmap(
                                    320, 320, android.graphics.Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(android.graphics.Color.CYAN);
                    try (var out = new FileOutputStream(image)) {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                    }
                    bitmap.recycle();
                } catch (IOException e) {
                    throw new FileNotFoundException();
                }
            return ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        File file = new File(getContext().getCacheDir(), "scan-test.wav");
        if (!file.exists())
            try {
                ByteBuffer b = ByteBuffer.allocate(8864).order(ByteOrder.LITTLE_ENDIAN);
                b.put("RIFF".getBytes())
                        .putInt(8856)
                        .put("WAVEfmt ".getBytes())
                        .putInt(16)
                        .putShort((short) 1)
                        .putShort((short) 1)
                        .putInt(44100)
                        .putInt(88200)
                        .putShort((short) 2)
                        .putShort((short) 16)
                        .put("data".getBytes())
                        .putInt(8820);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(b.array());
                }
            } catch (IOException e) {
                throw new FileNotFoundException(e.toString());
            }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(
            android.net.Uri uri,
            String[] projection,
            String selection,
            String[] args,
            String sort) {
        var parts = uri.getPathSegments();
        try {
            return parts.get(parts.size() - 1).equals("children")
                    ? queryChildDocuments(parts.get(parts.size() - 2), projection, sort)
                    : queryDocument(parts.get(parts.size() - 1), projection);
        } catch (FileNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public ParcelFileDescriptor openFile(android.net.Uri uri, String mode)
            throws FileNotFoundException {
        return openDocument(uri.getLastPathSegment(), mode, null);
    }

    @Override
    public String getType(android.net.Uri uri) {
        return "audio/wav";
    }

    @Override
    public android.net.Uri insert(android.net.Uri uri, android.content.ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(android.net.Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(
            android.net.Uri uri,
            android.content.ContentValues values,
            String selection,
            String[] args) {
        throw new UnsupportedOperationException();
    }
}
