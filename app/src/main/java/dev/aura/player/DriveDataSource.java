package dev.aura.player;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;

/** Seekable, bounded-memory streaming: each HTTP request is at most 1 MiB. No audio files on disk. */
@androidx.media3.common.util.UnstableApi
final class DriveDataSource extends BaseDataSource implements AutoCloseable {
    static final int WINDOW = 1024 * 1024;
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private final DriveHttp http;
    private final java.util.function.Predicate<Uri> account;
    private Uri uri;
    private String path, etag;
    private DriveHttp.Response response;
    private long position, remaining, windowRemaining, total = -1;
    private boolean opened;

    DriveDataSource(Context c) {
        this(
                new DriveHttp(DriveAuth.get(c)),
                u -> DriveAuth.get(c).accountId().equals(u.getAuthority()));
    }

    DriveDataSource(DriveHttp http, java.util.function.Predicate<Uri> account) {
        super(true);
        this.http = http;
        this.account = account;
    }

    @Override
    public long open(DataSpec spec) throws IOException {
        close();
        transferInitializing(spec);
        uri = spec.uri;
        if (!account.test(uri)) throw new IOException("이 파일의 Google Drive 계정을 다시 연결해 주세요.");
        path = "files/" + DriveUris.fileId(uri) + "?alt=media&supportsAllDrives=true";
        position = spec.position;
        remaining = spec.length;
        total = -1;
        etag = null;
        try {
            if (remaining != 0) window();
            opened = true;
            transferStarted(spec);
            return remaining;
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    private void window() throws IOException {
        if (response != null) {
            response.close();
            response = null;
        }
        long wanted = remaining == C.LENGTH_UNSET ? WINDOW : Math.min(WINDOW, remaining);
        if (total >= 0) wanted = Math.min(wanted, total - position);
        if (wanted <= 0) {
            windowRemaining = 0;
            return;
        }
        var r = http.open(path, position, wanted, etag);
        try {
            long length;
            if (r.code() == 206) {
                Matcher match =
                        RANGE.matcher(
                                Objects.toString(
                                        r.connection().getHeaderField("Content-Range"), ""));
                if (!match.matches()) throw new IOException("Google Drive 구간 응답이 올바르지 않습니다.");
                long start = Long.parseLong(match.group(1)),
                        end = Long.parseLong(match.group(2)),
                        size = Long.parseLong(match.group(3));
                if (start != position || end < start || end - start + 1 > wanted || size <= end)
                    throw new IOException("Google Drive 재생 구간이 일치하지 않습니다.");
                if (total >= 0 && total != size)
                    throw new IOException("Google Drive 파일 크기가 변경되었습니다.");
                total = size;
                length = end - start + 1;
            } else {
                if (position != 0) throw new IOException("Google Drive 서버가 구간 재생을 지원하지 않습니다.");
                length = r.connection().getContentLengthLong();
                if (length < 0) throw new IOException("Google Drive 파일 크기를 확인할 수 없습니다.");
                total = length;
                length = Math.min(length, wanted);
            }
            String next = r.connection().getHeaderField("ETag");
            if (etag != null && next != null && !etag.equals(next))
                throw new IOException("Google Drive 파일이 변경되었습니다.");
            if (next != null) etag = next;
            if (remaining == C.LENGTH_UNSET) remaining = total - position;
            else remaining = Math.min(remaining, total - position);
            windowRemaining = Math.min(length, remaining);
            response = r;
        } catch (IOException | RuntimeException e) {
            r.close();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Google Drive 파일 구간을 읽지 못했습니다.");
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        if (windowRemaining == 0) window();
        if (windowRemaining == 0) return C.RESULT_END_OF_INPUT;
        int n = response.input().read(buffer, offset, (int) Math.min(length, windowRemaining));
        if (n < 0) throw new EOFException("Google Drive 연결이 중단되었습니다. 다시 재생해 주세요.");
        position += n;
        remaining -= n;
        windowRemaining -= n;
        bytesTransferred(n);
        return n;
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return response == null ? Map.of() : response.connection().getHeaderFields();
    }

    @Override
    public void close() {
        if (response != null) {
            response.close();
            response = null;
        }
        uri = null;
        windowRemaining = 0;
        remaining = 0;
        if (opened) {
            opened = false;
            transferEnded();
        }
    }
}
