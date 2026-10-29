package dev.aura.player;

import android.content.Context;
import android.net.Uri;
import androidx.media3.datasource.DataSpec;
import java.io.*;
import java.util.function.*;

/** Bounded Drive range reads; tokens stay inside DriveHttp, never in the Cast URL or headers. */
@androidx.media3.common.util.UnstableApi
final class DriveRelaySource implements MediaFileServer.Source {
    private final Uri uri;
    private final String mime;
    private final DriveHttp http;
    private final BooleanSupplier allowed;
    private volatile MediaFileServer.Info cached;

    DriveRelaySource(Context c, Uri uri, String mime) {
        this(uri, mime, new DriveHttp(DriveAuth.get(c)), guard(c, uri));
    }

    private static BooleanSupplier guard(Context c, Uri uri) {
        DriveAuth auth = DriveAuth.get(c);
        long generation = auth.generation();
        return () -> generation == auth.generation() && uri.getAuthority().equals(auth.accountId());
    }

    DriveRelaySource(Uri uri, String mime, DriveHttp http, BooleanSupplier allowed) {
        this.uri = uri;
        this.mime = mime;
        this.http = http;
        this.allowed = allowed;
    }

    private void check() throws IOException {
        if (!allowed.getAsBoolean())
            throw new IOException("Google Drive 연결이 변경되었습니다. 다시 캐스트해 주세요.");
    }

    public synchronized MediaFileServer.Info info() throws Exception {
        check();
        if (cached != null) return cached;
        var row =
                http.json(
                        "files/"
                                + DriveUris.fileId(uri)
                                + "?supportsAllDrives=true&fields=size,mimeType,capabilities(canDownload)");
        long size = row.optLong("size", -1);
        if (size < 0
                || row.optJSONObject("capabilities") != null
                        && !row.getJSONObject("capabilities").optBoolean("canDownload", true))
            throw new IOException("이 Drive 음원은 스트리밍할 수 없습니다.");
        check();
        return cached =
                new MediaFileServer.Info(size, MediaTypes.of(uri, mime, row.optString("mimeType")));
    }

    public InputStream open(long from, long count) throws Exception {
        check();
        var data = new DriveDataSource(http, u -> allowed.getAsBoolean());
        try {
            long available =
                    data.open(new DataSpec.Builder().setUri(uri).setPosition(from).build());
            if (from + available != info().length())
                throw new IOException("Google Drive 음원이 변경되었습니다. 다시 캐스트해 주세요.");
            return new InputStream() {
                public int read() throws IOException {
                    byte[] b = new byte[1];
                    return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
                }

                public int read(byte[] b, int offset, int length) throws IOException {
                    check();
                    return data.read(b, offset, length);
                }

                public void close() {
                    data.close();
                }
            };
        } catch (Exception e) {
            data.close();
            throw e;
        }
    }
}
