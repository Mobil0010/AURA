package dev.aura.player;

import android.content.Context;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import java.io.*;
import java.nio.file.*;

/** High-quality disk covers. Decode bounds first; never inflate tiny originals or unbounded images. */
final class ArtworkStore {
    static final int MAX_EDGE = 2048, QUALITY = 95, MAX_EMBEDDED_BYTES = 16 * 1024 * 1024;

    private static File stamp(File file) {
        return new File(file.getPath() + ".hq2");
    }

    /** Migrate optional legacy cache on background library/artwork workers, without a network read. */
    static void restoreDriveCover(Context c, String uri) {
        if (!DriveUris.is(Uri.parse(uri))) return;
        synchronized (DriveLibrary.LOCK) {
            File target = Library.artFile(c, uri), legacy = Library.legacyArtFile(c, uri);
            if (target.isFile() || !legacy.isFile()) return;
            boolean highQuality = false;
            try (DataInputStream in = new DataInputStream(new FileInputStream(stamp(legacy)))) {
                highQuality =
                        in.readLong() == legacy.length() && in.readLong() == legacy.lastModified();
            } catch (IOException ignored) {
            }
            try {
                target.getParentFile().mkdirs();
                Files.move(legacy.toPath(), target.toPath());
                if (highQuality) mark(target);
                stamp(legacy).delete();
            } catch (IOException unavailable) {
                /* Leave existing cached data intact when migration cannot complete. */
            }
        }
    }

    static void remove(Context c, String uri) {
        synchronized (DriveLibrary.LOCK) {
            File file = Library.artFile(c, uri);
            file.delete();
            stamp(file).delete();
            File legacy = Library.legacyArtFile(c, uri);
            legacy.delete();
            stamp(legacy).delete();
        }
    }

    static boolean current(Context c, String uri) {
        File file = Library.artFile(c, uri);
        if (!file.isFile()) return false;
        try (DataInputStream in = new DataInputStream(new FileInputStream(stamp(file)))) {
            return in.readLong() == file.length() && in.readLong() == file.lastModified();
        } catch (IOException e) {
            return false;
        }
    }

    static void mark(File file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(stamp(file)))) {
            out.writeLong(file.length());
            out.writeLong(file.lastModified());
        }
    }

    static void save(Context c, String uri, Bitmap bitmap) throws IOException {
        File file = Library.artFile(c, uri);
        file.getParentFile().mkdirs();
        File temp = File.createTempFile("cover-", ".tmp", file.getParentFile());
        try {
            try (OutputStream out = new FileOutputStream(temp)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out))
                    throw new IOException("앨범아트를 저장하지 못했습니다.");
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            mark(file);
        } finally {
            temp.delete();
        }
    }

    static void linkOrCopy(File source, File target) throws IOException {
        target.getParentFile().mkdirs();
        java.nio.file.Path tmp =
                target.getParentFile()
                        .toPath()
                        .resolve("cover-" + java.util.UUID.randomUUID() + ".link");
        try {
            try {
                Files.createLink(tmp, source.toPath());
            } catch (IOException | UnsupportedOperationException noHardLinks) {
                Files.copy(source.toPath(), tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            mark(target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static int sample(int width, int height, int target) {
        int sample = 1;
        while (Math.max(width, height) / (sample * 2) >= target && sample <= 1 << 20) sample *= 2;
        return sample;
    }

    static Bitmap thumbnail(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > MAX_EDGE)
            options.inSampleSize *= 2;
        try {
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (OutOfMemoryError exhausted) {
            return null;
        }
    }

    static Bitmap file(File file, int target) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.toString(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample(bounds.outWidth, bounds.outHeight, target);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            return fit(BitmapFactory.decodeFile(file.toString(), options), target);
        } catch (OutOfMemoryError exhausted) {
            return null;
        }
    }

    private static Bitmap fit(Bitmap bitmap, int edge) {
        if (bitmap == null) return null;
        int max = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (max <= edge) return bitmap;
        float scale = edge / (float) max;
        Bitmap resized;
        try {
            resized =
                    Bitmap.createScaledBitmap(
                            bitmap,
                            Math.max(1, Math.round(bitmap.getWidth() * scale)),
                            Math.max(1, Math.round(bitmap.getHeight() * scale)),
                            true);
        } catch (OutOfMemoryError exhausted) {
            bitmap.recycle();
            return null;
        }
        if (resized != bitmap) bitmap.recycle();
        return resized;
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    static void refresh(Context c, Track track) throws Exception {
        if (track.drive()) {
            DriveLibrary.refreshArtwork(c, track);
            return;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(c, Uri.parse(track.uri));
            byte[] picture = retriever.getEmbeddedPicture();
            if (picture == null) {
                LocalFolderArtwork.refresh(c, track);
                return;
            }
            if (picture.length > MAX_EMBEDDED_BYTES) return;
            Bitmap bitmap = thumbnail(picture);
            if (bitmap != null)
                try {
                    save(c, track.uri, bitmap);
                } finally {
                    bitmap.recycle();
                }
        } finally {
            retriever.release();
        }
    }
}
