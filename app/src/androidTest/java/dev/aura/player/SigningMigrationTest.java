package dev.aura.player;

import static org.junit.Assert.*;
import android.content.*;
import android.content.pm.*;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.*;
import java.security.*;

/** Run each phase separately across adb install -r; never clear target app data. */
@RunWith(AndroidJUnit4.class)
public class SigningMigrationTest {
    final Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();

    android.os.Bundle args() {
        return InstrumentationRegistry.getArguments();
    }

    PackageInfo installed() throws Exception {
        return c.getPackageManager()
                .getPackageInfo(c.getPackageName(), AppUpdater.signingFlags(Build.VERSION.SDK_INT));
    }

    @Test
    public void seedExistingInstallation() throws Exception {
        assertEquals(22, installed().versionCode);
        assertTrue(
                c.getSharedPreferences("aura", 0)
                        .edit()
                        .putBoolean("scanOnLaunch", false)
                        .putString("migration-marker", "keep-through-key-rotation")
                        .putString("lastFolderCursor.local", "test-folder-path")
                        .commit());
        assertTrue(
                c.getSharedPreferences("playback", 0)
                        .edit()
                        .putString("engineBeforeUsb", "hires")
                        .commit());
        Files.write(
                new File(c.getFilesDir(), "migration-data.json").toPath(),
                "{\"playlist\":[\"song-a\",\"song-b\"],\"listeningMs\":123456}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    public void dataAndSigningIdentitySurviveUpdate() throws Exception {
        assertEquals(
                "keep-through-key-rotation",
                c.getSharedPreferences("aura", 0).getString("migration-marker", ""));
        assertEquals(
                "test-folder-path",
                c.getSharedPreferences("aura", 0).getString("lastFolderCursor.local", ""));
        assertEquals(
                "hires", c.getSharedPreferences("playback", 0).getString("engineBeforeUsb", ""));
        assertEquals(
                "{\"playlist\":[\"song-a\",\"song-b\"],\"listeningMs\":123456}",
                new String(
                        Files.readAllBytes(
                                new File(c.getFilesDir(), "migration-data.json").toPath()),
                        java.nio.charset.StandardCharsets.UTF_8));
        int expected = Integer.parseInt(args().getString("expectedCode"));
        assertEquals(expected, installed().versionCode);
        String cert = args().getString("expectedCert");
        assertTrue(AppUpdater.certificates(installed()).contains(cert));
        if (Build.VERSION.SDK_INT >= 33 && expected >= 24)
            assertTrue(installed().signingInfo.hasPastSigningCertificates());
    }

    @Test
    public void candidatePassesInAppVerification() throws Exception {
        File f = new File(args().getString("candidate"));
        PackageInfo p =
                c.getPackageManager()
                        .getPackageArchiveInfo(
                                f.toString(), AppUpdater.signingFlags(Build.VERSION.SDK_INT));
        assertNotNull(p);
        String sha =
                Hex.encode(
                        MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(f.toPath())));
        AppUpdater.Release release =
                new AppUpdater.Release(
                        p.versionCode,
                        p.versionName,
                        "https://github.com/Mobil0010/AURA/releases/download/test/test.apk",
                        sha,
                        f.length(),
                        26,
                        "서명 전환 테스트");
        boolean reject = Boolean.parseBoolean(args().getString("reject", "false"));
        try {
            AppUpdater.verify(c, f, release);
            assertFalse("Legacy updater should reject the rotated signer", reject);
        } catch (IOException e) {
            if (!reject) throw e;
            assertTrue(e.getMessage().contains("서명"));
        }
    }

    @Test
    public void rejectsRetiredAndUnrelatedSigners() throws Exception {
        File old = new File(args().getString("oldApk"));
        var previous =
                c.getPackageManager()
                        .getPackageArchiveInfo(
                                old.toString(), AppUpdater.signingFlags(Build.VERSION.SDK_INT));
        if (Build.VERSION.SDK_INT >= 33)
            assertFalse(AppUpdater.compatibleSigners(previous, installed()));
        File wrong = new File(c.getCacheDir(), "unrelated.apk");
        try (var in =
                InstrumentationRegistry.getInstrumentation()
                        .getContext()
                        .getAssets()
                        .open("update-wrong-signer.apk")) {
            Files.copy(in, wrong.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        var unrelated =
                c.getPackageManager()
                        .getPackageArchiveInfo(
                                wrong.toString(), AppUpdater.signingFlags(Build.VERSION.SDK_INT));
        assertNotNull(unrelated);
        assertFalse(AppUpdater.compatibleSigners(unrelated, installed()));
        wrong.delete();
    }
}
