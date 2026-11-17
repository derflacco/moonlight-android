package com.limelight;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** SteamGridDB v2 artwork lookup for the selected Sunshine application. */
public final class SteamGridDbClient {
    private static final String API_ROOT = "https://www.steamgriddb.com/api/v2";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build();

    public static final class Artwork {
        public final Bitmap hero;
        public final Bitmap logo;

        Artwork(Bitmap hero, Bitmap logo) {
            this.hero = hero;
            this.logo = logo;
        }
    }

    public interface Callback {
        void onArtworkLoaded(Artwork artwork);
    }

    private SteamGridDbClient() {
    }

    public static void loadArtwork(Context context, String gameName, String apiKey,
                                   Callback callback) {
        if (context == null || gameName == null || gameName.trim().isEmpty() ||
                apiKey == null || apiKey.trim().isEmpty()) {
            postResult(callback, new Artwork(null, null));
            return;
        }

        Context appContext = context.getApplicationContext();
        String key = apiKey.trim();
        String name = gameName.trim();

        EXECUTOR.execute(() -> {
            Bitmap hero = null;
            Bitmap logo = null;
            try {
                String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8.name());
                JSONObject search = getJson(API_ROOT + "/search/autocomplete/" + encodedName, key);
                JSONArray matches = search.optJSONArray("data");
                if (matches == null || matches.length() == 0) {
                    postResult(callback, new Artwork(null, null));
                    return;
                }

                int gameId = matches.getJSONObject(0).optInt("id", -1);
                if (gameId < 0) {
                    postResult(callback, new Artwork(null, null));
                    return;
                }

                JSONObject heroes = getJson(API_ROOT + "/heroes/game/" + gameId, key);
                JSONObject logos = getJson(API_ROOT + "/logos/game/" + gameId, key);
                String heroUrl = firstAssetUrl(heroes);
                String logoUrl = firstAssetUrl(logos);

                if (heroUrl != null) {
                    hero = downloadBitmap(appContext, heroUrl, "steamgriddb_hero_" + gameId);
                }
                if (logoUrl != null) {
                    logo = downloadBitmap(appContext, logoUrl, "steamgriddb_logo_" + gameId);
                }
            } catch (Exception e) {
                LimeLog.warning("SteamGridDB artwork lookup failed: " + e.getMessage());
            }
            postResult(callback, new Artwork(hero, logo));
        });
    }

    private static JSONObject getJson(String url, String apiKey) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .get()
                .build();

        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("SteamGridDB returned HTTP " + response.code());
            }
            return new JSONObject(response.body().string());
        } catch (org.json.JSONException e) {
            throw new IOException("Invalid SteamGridDB response", e);
        }
    }

    private static String firstAssetUrl(JSONObject response) {
        JSONArray data = response.optJSONArray("data");
        if (data == null || data.length() == 0) {
            return null;
        }

        for (int i = 0; i < data.length(); i++) {
            JSONObject asset = data.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            String url = asset.optString("url", "");
            if (url.isEmpty()) {
                url = asset.optString("thumb", "");
            }
            if (!url.isEmpty()) {
                return url;
            }
        }
        return null;
    }

    private static Bitmap downloadBitmap(Context context, String url, String cacheName) {
        File cacheFile = new File(context.getCacheDir(), cacheName + ".img");
        if (cacheFile.isFile() && cacheFile.length() > 0) {
            Bitmap cached = BitmapFactory.decodeFile(cacheFile.getAbsolutePath());
            if (cached != null) {
                return cached;
            }
        }

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            byte[] bytes = response.body().bytes();
            try (FileOutputStream output = new FileOutputStream(cacheFile)) {
                output.write(bytes);
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IOException e) {
            LimeLog.warning("Unable to download SteamGridDB artwork: " + e.getMessage());
            return null;
        }
    }

    private static void postResult(Callback callback, Artwork artwork) {
        if (callback != null) {
            MAIN_HANDLER.post(() -> callback.onArtworkLoaded(artwork));
        }
    }
}
