package dev.aura.player;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

public final class AudioSettings {
    public record Band(double frequency, double gain, double q, String type) {}

    public record Config(
            String mode,
            int deviceId,
            int sampleRate,
            int bits,
            int bufferMs,
            boolean perfect,
            boolean sox,
            int quality,
            double phase,
            double cutoff,
            String dither,
            boolean eq,
            List<Band> bands,
            double preamp,
            double crossfeed,
            double balance,
            boolean limiter,
            String dsd,
            boolean autoEqPreamp,
            String eqMode) {}

    static final double[] GRAPHIC_FREQUENCIES = {60, 150, 400, 1000, 2500, 6000, 12000};
    public static volatile Config current;

    public record Extra(
            String replayGain, double bass, double bassFrequency, double volumeCeiling) {}

    public static volatile Extra extra = new Extra("off", 0, 90, 100);

    private static void loadExtra() {
        extra =
                new Extra(
                        prefs.getString("replayGain", "off"),
                        Math.max(0, Math.min(100, number("dynamicBass", 0))),
                        Math.max(40, Math.min(160, number("bassFrequency", 90))),
                        Math.max(0, Math.min(100, number("volumeCeiling", 100))));
    }

    private static SharedPreferences prefs;

    public static void init(Context c) {
        prefs = c.getSharedPreferences("audio", Context.MODE_PRIVATE);
        current = load();
        loadExtra();
    }

    private static double number(String key, double fallback) {
        Object v = prefs.getAll().get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : fallback;
    }

    private static Config load() {
        List<Band> bands = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("bands", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject b = a.getJSONObject(i);
                bands.add(
                        new Band(
                                b.getDouble("f"),
                                b.getDouble("g"),
                                b.getDouble("q"),
                                b.getString("type")));
            }
        } catch (Exception ignored) {
        }
        if (bands.isEmpty())
            for (double f : new double[] {60, 150, 400, 1000, 2500, 6000, 12000})
                bands.add(new Band(f, 0, 1, "peak"));
        String eqMode = prefs.getString("eqMode", "parametric");
        if (eqMode.equals("graphic")) {
            bands.clear();
            JSONArray gains;
            try {
                gains = new JSONArray(prefs.getString("graphicGains", "[]"));
            } catch (Exception ignored) {
                gains = new JSONArray();
            }
            for (int i = 0; i < GRAPHIC_FREQUENCIES.length; i++)
                bands.add(
                        new Band(
                                GRAPHIC_FREQUENCIES[i],
                                Math.max(-12, Math.min(12, gains.optDouble(i, 0))),
                                1,
                                "peak"));
        }
        return new Config(
                prefs.getString("mode", "android"),
                prefs.getInt("device", 0),
                prefs.getInt("rate", 0),
                prefs.getInt("bits", 0),
                prefs.getInt("buffer", 100),
                prefs.getBoolean("perfect", false),
                prefs.getBoolean("sox", false),
                prefs.getInt("quality", 4),
                number("phase", 50),
                number("cutoff", 95),
                prefs.getString("dither", "auto"),
                prefs.getBoolean("eq", false),
                List.copyOf(bands),
                number("preamp", 0),
                number("crossfeed", 0),
                number("balance", 0),
                prefs.getBoolean("limiter", false),
                prefs.getString("dsd", "pcm"),
                prefs.getBoolean("autoEqPreamp", true),
                eqMode);
    }

    public static void put(String key, Object value) {
        SharedPreferences.Editor e = prefs.edit();
        if (value instanceof Boolean) e.putBoolean(key, (Boolean) value);
        else if (value instanceof Number) {
            if (List.of("rate", "device", "buffer", "bits", "quality").contains(key))
                e.putInt(key, ((Number) value).intValue());
            else e.putFloat(key, ((Number) value).floatValue());
        } else e.putString(key, String.valueOf(value));
        e.apply();
        current = load();
        loadExtra();
    }

    public static void band(int index, Band band) {
        if (current.eqMode().equals("graphic")) {
            JSONArray gains = new JSONArray();
            for (int i = 0; i < GRAPHIC_FREQUENCIES.length; i++)
                gains.put(
                        Double.valueOf(
                                i == index
                                        ? Math.max(-12, Math.min(12, band.gain()))
                                        : current.bands().get(i).gain()));
            put("graphicGains", gains.toString());
            return;
        }
        try {
            List<Band> bs = new ArrayList<>(current.bands());
            bs.set(index, band);
            JSONArray a = new JSONArray();
            for (Band b : bs)
                a.put(
                        new JSONObject()
                                .put("f", b.frequency())
                                .put("g", b.gain())
                                .put("q", b.q())
                                .put("type", b.type()));
            put("bands", a.toString());
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static void saveProfile(String name) {
        try {
            JSONObject j = new JSONObject();
            for (Map.Entry<String, ?> e : prefs.getAll().entrySet())
                if (!e.getKey().startsWith("profile:")) j.put(e.getKey(), e.getValue());
            prefs.edit().putString("profile:" + name, j.toString()).apply();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean restoreProfile(String name) {
        String value = prefs.getString("profile:" + name, null);
        if (value == null) return false;
        try {
            JSONObject j = new JSONObject(value);
            SharedPreferences.Editor e = prefs.edit();
            e.putString("eqMode", j.optString("eqMode", "parametric"))
                    .putBoolean("autoEqPreamp", j.optBoolean("autoEqPreamp", true))
                    .putString("graphicGains", j.optString("graphicGains", "[]"));
            j.keys()
                    .forEachRemaining(
                            k -> {
                                Object v = j.opt(k);
                                if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
                                else if (v instanceof Number) {
                                    if (List.of("rate", "device", "buffer", "bits", "quality")
                                            .contains(k)) e.putInt(k, ((Number) v).intValue());
                                    else e.putFloat(k, ((Number) v).floatValue());
                                } else e.putString(k, String.valueOf(v));
                            });
            e.apply();
            current = load();
            loadExtra();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String profileKey(Context context) {
        if (current.mode().equals("usb")) return "usb:" + UsbRoute.identity;
        android.media.AudioManager manager =
                (android.media.AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        for (android.media.AudioDeviceInfo device :
                manager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS))
            if (device.getId() == current.deviceId())
                return current.mode() + ":" + device.getType() + ":" + device.getProductName();
        return current.mode() + ":system";
    }

    public static boolean dsp(Config c) {
        return !c.perfect()
                && (c.sox()
                        || c.eq()
                        || c.preamp() != 0
                        || c.crossfeed() != 0
                        || c.balance() != 0
                        || c.limiter()
                        || extra.bass() > 0
                        || extra.volumeCeiling() < 100
                        || !extra.replayGain().equals("off"));
    }
}
