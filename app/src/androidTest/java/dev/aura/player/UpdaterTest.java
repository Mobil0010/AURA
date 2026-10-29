package dev.aura.player;

import static org.junit.Assert.*;
import android.content.*;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.security.MessageDigest;

@RunWith(AndroidJUnit4.class)
public class UpdaterTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private File fixture(String name) throws Exception {
        File file = new File(context.getCacheDir(), name);
        try (InputStream in =
                        InstrumentationRegistry.getInstrumentation()
                                .getContext()
                                .getAssets()
                                .open(name);
                OutputStream out = new FileOutputStream(file)) {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
        }
        return file;
    }

    private AppUpdater.Release release(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) != -1) hash.update(b, 0, n);
        }
        return new AppUpdater.Release(
                17,
                "0.12.1",
                "https://github.com/Mobil0010/AURA/releases/download/v0.12.1/AURA.apk",
                Hex.encode(hash.digest()),
                file.length(),
                26,
                "업데이트 테스트");
    }

    @Test
    public void manifestRoundTripAndSource() throws Exception {
        var r = release(fixture("update-valid.apk"));
        var parsed = AppUpdater.Release.parse(r.json());
        assertEquals(r, parsed);
        assertEquals(
                "https://github.com/Mobil0010/AURA/releases/latest/download/update-v2.json",
                AppUpdater.SOURCE);
    }

    @Test
    public void rejectsUntrustedManifestFields() throws Exception {
        var valid = release(fixture("update-valid.apk"));
        for (String changed :
                new String[] {
                    valid.json().replace("https:", "http:"),
                    valid.json().replace(valid.sha(), "bad-hash"),
                    valid.json().replace("\"size\":" + valid.size(), "\"size\":0"),
                    valid.json().replace("\"versionCode\":17", "\"versionCode\":-1")
                }) {
            try {
                AppUpdater.Release.parse(changed);
                fail("Invalid manifest accepted");
            } catch (IOException expected) {
            }
        }
        for (String url :
                new String[] {
                    "file:///tmp/a.apk",
                    "https://user:password@github.com/a.apk",
                    "https://github.com/a.apk#fragment"
                })
            try {
                AppUpdater.https(url);
                fail("Invalid URL accepted");
            } catch (IOException expected) {
            }
    }

    @Test
    public void acceptsNewerApkWithMatchingSignature() throws Exception {
        File apk = fixture("update-valid.apk");
        AppUpdater.verify(context, apk, release(apk));
    }

    @Test
    public void rejectsDifferentSigningCertificate() throws Exception {
        File apk = fixture("update-wrong-signer.apk");
        try {
            AppUpdater.verify(context, apk, release(apk));
            fail("Wrong signer accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("서명"));
        }
    }

    @Test
    public void rejectsTamperedApk() throws Exception {
        File apk = fixture("update-valid.apk");
        var r = release(apk);
        try (RandomAccessFile f = new RandomAccessFile(apk, "rw")) {
            f.seek(100);
            int value = f.readUnsignedByte();
            f.seek(100);
            f.writeByte(value ^ 1);
        }
        try {
            AppUpdater.verify(context, apk, r);
            fail("Modified APK accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("검증"));
        }
    }

    @Test
    public void rejectsUnexpectedVersion() throws Exception {
        File apk = fixture("update-valid.apk");
        var r = release(apk);
        try {
            AppUpdater.verify(
                    context,
                    apk,
                    new AppUpdater.Release(
                            18, r.name(), r.url(), r.sha(), r.size(), 26, r.notes()));
            fail("Wrong version accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("버전"));
        }
    }

    @Test
    public void fileProviderExposesOnlyInternalUpdates() throws Exception {
        File dir = new File(context.getFilesDir(), "updates");
        dir.mkdirs();
        File file = new File(dir, "provider-test.apk");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(1);
        }
        Uri uri =
                androidx.core.content.FileProvider.getUriForFile(
                        context, context.getPackageName() + ".updates", file);
        assertEquals("content", uri.getScheme());
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            assertEquals(1, in.read());
        }
        try {
            androidx.core.content.FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".updates",
                    new File(context.getFilesDir(), "private.txt"));
            fail("Private file exposed");
        } catch (IllegalArgumentException expected) {
        } finally {
            file.delete();
        }
    }

    @Test
    public void verifiedFileRestoresWithoutDownloadRecord() throws Exception {
        Context isolated =
                new ContextWrapper(context) {
                    @Override
                    public android.content.SharedPreferences getSharedPreferences(
                            String name, int mode) {
                        return super.getSharedPreferences("updater-test-" + name, mode);
                    }
                };
        var prefs = isolated.getSharedPreferences("updates", Context.MODE_PRIVATE);
        File source = fixture("update-valid.apk");
        var release = release(source);
        prefs.edit()
                .clear()
                .putString("release", release.json())
                .putLong("download", 987654321L)
                .putBoolean("automatic", false)
                .commit();
        var constructor = AppUpdater.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        AppUpdater updater = constructor.newInstance(isolated);
        File ready = updater.file();
        ready.getParentFile().mkdirs();
        try {
            try (InputStream in = new FileInputStream(source);
                    OutputStream out = new FileOutputStream(ready)) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
            }
            updater.refresh();
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline
                    && updater.state().stage() != AppUpdater.Stage.READY
                    && updater.state().stage() != AppUpdater.Stage.ERROR) Thread.sleep(20);
            assertEquals(
                    updater.state().message(), AppUpdater.Stage.READY, updater.state().stage());
        } finally {
            ready.delete();
            prefs.edit().clear().commit();
            var executor = AppUpdater.class.getDeclaredField("executor");
            executor.setAccessible(true);
            ((java.util.concurrent.ExecutorService) executor.get(updater)).shutdownNow();
        }
    }

    @Test
    public void pieArchiveFlagsTriggerCertificateCollection() throws Exception {
        // Android 9's parser gates collectCertificates on GET_SIGNATURES, even when
        // GET_SIGNING_CERTIFICATES was requested. Exercise actual archive extraction too.
        for (int sdk : new int[] {26, 27, 28, 29, 36})
            assertTrue(
                    (AppUpdater.signingFlags(sdk)
                                    & android.content.pm.PackageManager.GET_SIGNATURES)
                            != 0);
        File apk = fixture("update-valid.apk");
        var info =
                context.getPackageManager()
                        .getPackageArchiveInfo(
                                apk.toString(),
                                AppUpdater.signingFlags(android.os.Build.VERSION.SDK_INT));
        assertNotNull(info);
        assertFalse(AppUpdater.certificates(info).isEmpty());
        AppUpdater.verify(context, apk, release(apk));
    }

    @Test
    public void legacyOnlyCertificateInfoIsAcceptedButEmptyInfoIsRejected() throws Exception {
        File apk = fixture("update-valid.apk");
        var info =
                context.getPackageManager()
                        .getPackageArchiveInfo(
                                apk.toString(),
                                AppUpdater.signingFlags(android.os.Build.VERSION.SDK_INT));
        var expected = AppUpdater.certificates(info);
        android.content.pm.PackageInfo legacy = new android.content.pm.PackageInfo();
        legacy.signatures = info.signatures;
        assertNotNull(legacy.signatures);
        assertEquals(expected, AppUpdater.certificates(legacy));
        try {
            AppUpdater.certificates(new android.content.pm.PackageInfo());
            fail("Missing certificates must never be accepted");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("서명"));
        }
    }

    @Test
    public void downloadStagingFileIsVerifiedBeforeRename() throws Exception {
        File apk = fixture("update-valid.apk"),
                staging = new File(context.getCacheDir(), "download.partial");
        try {
            java.nio.file.Files.copy(
                    apk.toPath(),
                    staging.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            AppUpdater.verify(context, staging, release(apk));
        } finally {
            staging.delete();
        }
    }
}
