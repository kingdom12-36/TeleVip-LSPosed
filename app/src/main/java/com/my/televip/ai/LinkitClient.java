package com.my.televip.ai;

import com.my.televip.logging.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * LinkitClient — thin HTTP wrapper for the Shamsaver Cloudflare AI worker.
 *
 * Endpoint : POST https://telegram-ai-bot.shamsaver1.workers.dev/api/chat
 * Request  : {"messages":[{"role":"system","content":"…"},{"role":"user","content":"…"}]}
 * Response : {"reply":"…"}
 *
 * No API key required.
 */
public class LinkitClient {

    private static final String TAG      = "LinkitClient";
    private static final String ENDPOINT = "https://telegram-ai-bot.shamsaver1.workers.dev/api/chat";

    public interface Callback {
        void onSuccess(String reply);
        void onError(String error);
    }

    /**
     * Sends a chat request asynchronously on a background thread.
     *
     * @param systemPrompt  Optional instructions for the AI (may be null/empty).
     * @param userMessage   The user's message / selected text.
     * @param callback      Called on the CALLING thread's message queue (UI-safe if called from UI).
     */
    public static void prompt(String systemPrompt, String userMessage, Callback callback) {
        new Thread(() -> {
            try {
                String reply = callWorker(systemPrompt, userMessage);
                callback.onSuccess(reply);
            } catch (Exception e) {
                Logger.e(e);
                callback.onError(e.getMessage() != null ? e.getMessage() : "Unknown error");
            }
        }, "TeleVipAI-Worker").start();
    }

    private static String callWorker(String systemPrompt, String userMessage) throws Exception {
        JSONArray messages = new JSONArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        }
        messages.put(new JSONObject().put("role", "user").put("content", userMessage));

        String bodyStr = new JSONObject().put("messages", messages).toString();

        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(120_000);
        conn.setDoOutput(true);
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream(), "UTF-8"))) {
            w.write(bodyStr);
        }

        int code = conn.getResponseCode();
        boolean ok = code >= 200 && code < 300;
        InputStream is = ok ? conn.getInputStream() : conn.getErrorStream();
        String raw = new String(is.readAllBytes(), "UTF-8").trim();
        conn.disconnect();

        if (!ok) throw new Exception("HTTP " + code + ": " + raw);

        try {
            JSONObject resp = new JSONObject(raw);
            if (resp.has("reply"))   return resp.getString("reply");
            if (resp.has("content")) return resp.getString("content");
            if (resp.has("text"))    return resp.getString("text");
        } catch (Exception ignored) {}
        return raw; // plain-text reply
    }
}
