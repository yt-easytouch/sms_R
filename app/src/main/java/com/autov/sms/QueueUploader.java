package com.autov.sms;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles:
 * - Baseline (send only NEW sms after install/open)
 * - Offline queue stored in SharedPreferences (FIFO, capped)
 * - HTTP POST with headers to the active endpoint (Autov / Other)
 * - Async helpers so receivers never block the main thread
 *
 * Requires:
 * - Const.java with:
 *      PREF_NAME, PREF_LAST_SYNCED, PREF_QUEUE, PREF_BASELINE_SET, QUEUE_MAX,
 *      PREF_SERVER (1=Autov, 2=Sadar, 3=Other),
 *      PREF_AUTOV_TOKEN, PREF_OTHER_URL, PREF_ENABLED,
 *      AUTOV_SMS_UPLOAD (Autov SMS endpoint), verifyUrl(token) (used elsewhere)
 * - Iso.java with Iso.now() / Iso.fromMillis(long) helpers (UTC ISO-8601)
 */
public class QueueUploader {
    private static final String TAG = "QueueUploader";
    
    // Broadcast action for SMS status updates
    public static final String ACTION_SMS_STATUS_UPDATED = "com.autov.sms.SMS_STATUS_UPDATED";

    // CachedThreadPool allows parallel execution (so resends aren't blocked by background flushing)
    private static final ExecutorService EXEC = Executors.newCachedThreadPool();

    // Guard to avoid concurrent flushes
    private static volatile boolean isFlushing = false;

    /* ===========================
     * Public async helpers
     * =========================== */

    public static void flushQueueIfAnyAsync(Context ctx) {
        EXEC.execute(() -> flushQueueIfAny(ctx));
    }

    /* ===========================
     * Baseline utilities
     * =========================== */

    /** One-time baseline: mark "now" so older SMS are ignored */
    public static void ensureBaselineNow(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(Const.PREF_NAME, Context.MODE_PRIVATE);
        boolean already = sp.getBoolean(Const.PREF_BASELINE_SET, false);
        if (!already) {
            long now = System.currentTimeMillis();
            sp.edit()
                    .putLong(Const.PREF_LAST_SYNCED, now)
                    .putBoolean(Const.PREF_BASELINE_SET, true)
                    .apply();
        }
    }

    public static long getBaseline(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(Const.PREF_NAME, Context.MODE_PRIVATE);
        return sp.getLong(Const.PREF_LAST_SYNCED, 0L);
    }

    public static void maybeAdvanceBaseline(Context ctx, long ts) {
        SharedPreferences sp = ctx.getSharedPreferences(Const.PREF_NAME, Context.MODE_PRIVATE);
        long prev = sp.getLong(Const.PREF_LAST_SYNCED, 0L);
        if (ts > prev) sp.edit().putLong(Const.PREF_LAST_SYNCED, ts).apply();
    }

    /* ===========================
     * Queue persistence
     * =========================== */

    private static JSONArray loadQueue(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(Const.PREF_NAME, Context.MODE_PRIVATE);
        String raw = sp.getString(Const.PREF_QUEUE, "[]");
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private static void saveQueue(Context ctx, JSONArray arr) {
        SharedPreferences sp = ctx.getSharedPreferences(Const.PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(Const.PREF_QUEUE, arr.toString()).apply();
    }

    public static void enqueueOffline(Context ctx, JSONObject payload) {
        try {
            JSONArray q = loadQueue(ctx);
            // Cap size: drop oldest
            if (q.length() >= Const.QUEUE_MAX) {
                JSONArray nq = new JSONArray();
                for (int i = 1; i < q.length(); i++) {
                    nq.put(q.opt(i));
                }
                q = nq;
            }
            payload.put("_queuedAt", Iso.now());
            q.put(payload);
            saveQueue(ctx, q);
        } catch (JSONException ignore) {}
    }

    /* ===========================
     * Endpoint + headers
     * =========================== */

    private static @Nullable String resolveEndpoint(SmsDatabaseHelper.Config config) {
        if (config.serverType == 1) {
            return Const.AUTOV_SMS_UPLOAD;
        } else if (config.serverType == 3) {
            return config.url;
        }
        return null;
    }

    public static void clearQueue(Context ctx) {
        saveQueue(ctx, new JSONArray());
    }

    /* ===========================
     * Network: send + flush
     * =========================== */

    public static void sendToConfigAsync(Context ctx, JSONObject payload, SmsDatabaseHelper.Config config, String source) {
        EXEC.execute(() -> sendToConfig(ctx, payload, config, source));
    }

    private static void sendToConfig(Context ctx, JSONObject payload, SmsDatabaseHelper.Config config, String source) {
        try {
            String from = payload.optString("from", "");
            if (!isAllowedByWhitelist(config.whitelist, from)) {
                Log.d(TAG, "Config '" + config.title + "' blocks '" + from + "'.");
                return;
            }

            Log.d(TAG, "➡️ Config '" + config.title + "' sending [" + source + "] " + from);

            // Ping verifyUrl to notify the server
            if (config.token != null && !config.token.isEmpty()) {
                try {
                    String mobileAddress = DeviceIdUtil.get(ctx);
                    String urlStr = Const.verifyUrl(config.token) + "&mobile_address=" + mobileAddress;
                    HttpURLConnection vc = (HttpURLConnection) new URL(urlStr).openConnection();
                    vc.setRequestMethod("GET");
                    vc.setConnectTimeout(5000);
                    int code = vc.getResponseCode();
                    
                    String respMsg = "";
                    try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                            (code >= 200 && code < 300) ? vc.getInputStream() : vc.getErrorStream(),
                            StandardCharsets.UTF_8))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        respMsg = sb.toString();
                    } catch (Exception ignore) {}
                    
                    final String finalMsg = respMsg;
                    if (!finalMsg.isEmpty()) {
                        String displayMsg = finalMsg;
                        try {
                            JSONObject j = new JSONObject(finalMsg);
                            if (j.has("message")) displayMsg = j.getString("message");
                        } catch (Exception ignore) {}
                        
                        final String finalDisplay = displayMsg;
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                                android.widget.Toast.makeText(ctx, finalDisplay, android.widget.Toast.LENGTH_LONG).show()
                        );
                    }
                    vc.disconnect();
                } catch (Exception ignore) {}
            }

            // Ensure we use the correct endpoint based on type
            String endpoint = config.url;
            if (config.serverType == 1) {
                endpoint = Const.AUTOV_SMS_UPLOAD;
            }

            String token = config.serverType == 1 ? config.token : null;

            if (endpoint == null || endpoint.isEmpty()) {
                Log.e(TAG, "Config '" + config.title + "' has no URL configured!");
                return;
            }

            if (config.token != null && !config.token.isEmpty()) {
                payload.put("token", config.token);
            }

            // Note: We might want a separate queue per config, but for now we'll just try to send
            ResponseData res = postJson(endpoint, payload, source, null, token);

            if (res.code >= 200 && res.code < 300) {
                Log.d(TAG, "✅ sent status=" + res.code);
                updateDbStatus(ctx, payload, SmsDatabaseHelper.STATUS_SENT, res.body, endpoint, config.token);
            } else {
                Log.d(TAG, "❌ server status=" + res.code);
                // Include HTTP code in the failure reason
                String reason = "HTTP " + res.code;
                if (res.body != null && !res.body.isEmpty()) reason += "\n" + res.body;
                
                updateDbStatus(ctx, payload, SmsDatabaseHelper.STATUS_FAILED, reason, endpoint, config.token);
                // Simple enqueue for now (global queue)
                enqueueOffline(ctx, payload);
            }
        } catch (Exception e) {
            Log.d(TAG, "❌ network error: " + e.getMessage());
            String errorMsg = "Network Error: " + e.getMessage();
            if (e instanceof java.net.SocketTimeoutException) errorMsg = "Connection Timeout";
            if (e instanceof java.net.UnknownHostException) errorMsg = "Unknown Host (Check URL)";
            if (e instanceof java.net.ConnectException) errorMsg = "Connection Refused (Server Down?)";
            
            updateDbStatus(ctx, payload, SmsDatabaseHelper.STATUS_FAILED, errorMsg, config.url, config.token);
            enqueueOffline(ctx, payload);
        }
    }

    private static boolean isAllowedByWhitelist(String whitelist, String from) {
        if (whitelist == null || whitelist.trim().isEmpty()) return true;
        String[] parts = whitelist.split(",");
        for (String p : parts) {
            if (p.trim().equalsIgnoreCase(from.trim())) return true;
        }
        return false;
    }

    public static void flushQueueIfAny(Context ctx) {
        if (isFlushing) return;
        isFlushing = true;
        try {
            JSONArray q = loadQueue(ctx);
            if (q.length() == 0) return;

            List<SmsDatabaseHelper.Config> configs = SmsDatabaseHelper.getInstance(ctx).getAllConfigs();
            if (configs.isEmpty()) return;

            JSONArray remain = new JSONArray();
            for (int i = 0; i < q.length(); i++) {
                JSONObject item = q.optJSONObject(i);
                if (item == null) continue;

                boolean sentAny = false;
                for (SmsDatabaseHelper.Config config : configs) {
                    if (!config.isActive) continue;

                    String from = item.optString("from", "");
                    if (!isAllowedByWhitelist(config.whitelist, from)) continue;

                    String endpoint = resolveEndpoint(config);
                    if (endpoint == null) continue;

                    try {
                        String queuedAt = item.optString("_queuedAt", "");
                        
                        JSONObject payloadToSend = new JSONObject(item.toString());
                        if (config.token != null && !config.token.isEmpty()) {
                            payloadToSend.put("token", config.token);
                            
                            // Ping verifyUrl to notify the server
                            try {
                                String mobileAddress = DeviceIdUtil.get(ctx);
                                String urlStr = Const.verifyUrl(config.token) + "&mobile_address=" + mobileAddress;
                                HttpURLConnection vc = (HttpURLConnection) new URL(urlStr).openConnection();
                                vc.setRequestMethod("GET");
                                vc.setConnectTimeout(5000);
                                int code = vc.getResponseCode();
                                
                                String respMsg = "";
                                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                                        (code >= 200 && code < 300) ? vc.getInputStream() : vc.getErrorStream(),
                                        StandardCharsets.UTF_8))) {
                                    StringBuilder sb = new StringBuilder();
                                    String line;
                                    while ((line = br.readLine()) != null) sb.append(line);
                                    respMsg = sb.toString();
                                } catch (Exception ignore) {}
                                
                                final String finalMsg = respMsg;
                                if (!finalMsg.isEmpty()) {
                                    String displayMsg = finalMsg;
                                    try {
                                        JSONObject j = new JSONObject(finalMsg);
                                        if (j.has("message")) displayMsg = j.getString("message");
                                    } catch (Exception ignore) {}
                                    
                                    final String finalDisplay = displayMsg;
                                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                                            android.widget.Toast.makeText(ctx, finalDisplay, android.widget.Toast.LENGTH_LONG).show()
                                    );
                                }
                                vc.disconnect();
                            } catch (Exception ignore) {}
                        }

                        ResponseData res = postJson(endpoint, payloadToSend, "flush", queuedAt, config.token);
                        if (res.code >= 200 && res.code < 300) {
                            updateDbStatus(ctx, item, SmsDatabaseHelper.STATUS_SENT, res.body, endpoint, config.token);
                            sentAny = true;
                        } else {
                            updateDbStatus(ctx, item, SmsDatabaseHelper.STATUS_FAILED, res.body, endpoint, config.token);
                        }
                    } catch (Exception ignore) {}
                }

                if (!sentAny) {
                    remain.put(item);
                }
            }
            saveQueue(ctx, remain);
        } finally {
            isFlushing = false;
        }
    }

    /** Low-level HTTP POST using HttpURLConnection */
    private static ResponseData postJson(String endpoint,
                                JSONObject body,
                                String source,
                                @Nullable String queuedAtHeaderOrNull,
                                @Nullable String autovTokenOrNull) throws Exception {
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("X-Source", source);
            if (queuedAtHeaderOrNull != null) {
                conn.setRequestProperty("X-Queued-At", queuedAtHeaderOrNull);
            }
            if (autovTokenOrNull != null) {
                // Only for Autov server; change header name if your backend expects a different one
                conn.setRequestProperty("X-Autov-Token", autovTokenOrNull);
            }

            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = new BufferedOutputStream(conn.getOutputStream());
            os.write(out);
            os.flush();
            os.close();

            int code = conn.getResponseCode();

            String respBody = "";
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream(),
                    StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                respBody = sb.toString();
                Log.d(TAG, "HTTP resp (" + code + "): " + respBody);
            } catch (Exception ignore) {}

            return new ResponseData(code, respBody);
        } finally {
            conn.disconnect();
        }
    }
    private static void updateDbStatus(Context ctx, JSONObject payload, int status, @Nullable String response, @Nullable String url, @Nullable String token) {
        long dbId = payload.optLong("_db_id", -1);
        if (dbId != -1) {
            SmsDatabaseHelper.getInstance(ctx).updateStatusResponseUrlAndToken(dbId, status, response, url, token);
            
            // Notify UI that SMS status was updated
            android.content.Intent intent = new android.content.Intent(ACTION_SMS_STATUS_UPDATED);
            intent.setPackage(ctx.getPackageName());
            intent.putExtra("sms_id", dbId);
            intent.putExtra("status", status);
            ctx.sendBroadcast(intent);
        }
    }

    private static class ResponseData {
        int code;
        String body;
        ResponseData(int code, String body) { this.code = code; this.body = body; }
    }
}
