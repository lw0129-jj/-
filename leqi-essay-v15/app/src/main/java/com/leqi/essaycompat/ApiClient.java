package com.lensmind.essay;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ApiClient {
    interface Callback {
        void onProgress(String message);
        void onSuccess(String essay);
        void onError(String message);
    }

    // Kept in the APK as requested. Network requests use the same Rokid relay as
    // the confirmed-working LensMind build because YodaOS cannot reliably reach
    // api.openai.com directly.
    private static final String API_KEY = "REPLACE_WITH_LOCAL_API_KEY_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";
    private static final String RELAY_ENDPOINT = "https://lensmind-glasses.bhdlwzjj.chatgpt.site/api/analyze";
    private static final String RELAY_AUTH = "Bearer Gh3dop7505NFNMWT3fO-5nl-OSnDrlv4qi_DN3B5vAE";

    private static final String ESSAY_CONTEXT =
            "ESSAY MODE. The photographed page is a school writing assignment. Read every visible instruction accurately, then write only the finished response. " +
            "Follow the required language, genre, audience, source material, questions, and word limit. Unless another format is explicitly required, write exactly five paragraphs: " +
            "one introduction, three genuinely different body paragraphs, and one conclusion. For English, use natural B1-C1 vocabulary with B2 as the main level. " +
            "Do not output an outline, analysis, score, tips, vocabulary list, JSON, or paragraph labels. Return the complete essay as the answer.";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    void createEssay(final byte[] jpeg, final Callback callback) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    callback.onProgress("正在整理照片…");
                    String image = prepareImage(jpeg);
                    callback.onProgress("正在识别并写作文…");
                    JSONObject result = requestRelay(image);
                    String essay = extractEssay(result);
                    if (essay.length() == 0) throw new IllegalStateException("中转接口没有返回作文");
                    callback.onSuccess(essay);
                } catch (Throwable error) {
                    callback.onError(readableError(error));
                }
            }
        });
    }

    private static String prepareImage(byte[] jpeg) throws Exception {
        Bitmap source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (source == null) throw new IllegalStateException("照片解码失败");
        Bitmap resized = source;
        try {
            int max = Math.max(source.getWidth(), source.getHeight());
            if (max > 1400) {
                float scale = 1400f / (float) max;
                int width = Math.max(1, Math.round(source.getWidth() * scale));
                int height = Math.max(1, Math.round(source.getHeight() * scale));
                resized = Bitmap.createScaledBitmap(source, width, height, true);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!resized.compress(Bitmap.CompressFormat.JPEG, 76, output)) {
                throw new IllegalStateException("照片压缩失败");
            }
            return "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        } finally {
            if (resized != source) {
                try { resized.recycle(); } catch (Throwable ignored) {}
            }
            try { source.recycle(); } catch (Throwable ignored) {}
        }
    }

    private static JSONObject requestRelay(String image) throws Exception {
        JSONObject body = new JSONObject();
        body.put("image", image);
        body.put("memory", ESSAY_CONTEXT);
        body.put("context", ESSAY_CONTEXT);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(RELAY_ENDPOINT).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(25000);
            connection.setReadTimeout(180000);
            connection.setDoOutput(true);
            connection.setRequestProperty("OAI-Sites-Authorization", RELAY_AUTH);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(payload);
                output.flush();
            } finally {
                output.close();
            }

            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String raw = input == null ? "" : readAll(input).trim();
            if (input != null) input.close();

            JSONObject response = parseObject(raw);
            if (status < 200 || status >= 300) {
                String message = response.optString("error", "中转请求失败：" + status);
                if (message.length() == 0) message = "中转请求失败：" + status;
                throw new IllegalStateException(message);
            }
            return response;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONObject parseObject(String raw) throws Exception {
        if (raw == null || raw.length() == 0) throw new IllegalStateException("中转接口没有返回数据");
        try {
            return new JSONObject(raw);
        } catch (Throwable ignored) {
            int first = raw.indexOf('{');
            int last = raw.lastIndexOf('}');
            if (first >= 0 && last > first) return new JSONObject(raw.substring(first, last + 1));
            throw new IllegalStateException("中转接口返回格式异常");
        }
    }

    private static String extractEssay(JSONObject data) {
        String direct = firstNonEmpty(
                data.optString("answer", ""),
                data.optString("essay", ""),
                data.optString("result", ""),
                data.optString("content", ""),
                data.optString("response", ""),
                data.optString("output_text", "")
        );
        if (direct.length() > 0) return clean(direct);

        JSONObject resultObject = data.optJSONObject("result");
        if (resultObject != null) {
            String nested = extractEssay(resultObject);
            if (nested.length() > 0) return nested;
        }

        JSONArray items = data.optJSONArray("items");
        if (items == null) items = data.optJSONArray("results");
        if (items != null) {
            StringBuilder combined = new StringBuilder();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String answer = firstNonEmpty(
                        item.optString("answer", ""),
                        item.optString("essay", ""),
                        item.optString("content", ""),
                        item.optString("result", "")
                );
                if (answer.length() > 0) {
                    if (combined.length() > 0) combined.append("\n\n");
                    combined.append(answer.trim());
                }
            }
            if (combined.length() > 0) return clean(combined.toString());
        }

        JSONObject dataObject = data.optJSONObject("data");
        if (dataObject != null) return extractEssay(dataObject);
        return "";
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && value.trim().length() > 0 && !"null".equalsIgnoreCase(value.trim())) {
                return value.trim();
            }
        }
        return "";
    }

    private static String clean(String value) {
        String text = value == null ? "" : value.replace("\r", "").trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) text = text.substring(firstNewline + 1);
            if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
        }
        return text.trim();
    }

    private static String readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String readableError(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().length() == 0) return "识别失败，请重试";
        String lower = message.toLowerCase();
        if (lower.contains("unable to resolve host") || lower.contains("unknownhost")) {
            return "眼镜网络无法连接中转接口";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "识别超时，请检查网络后重试";
        }
        if (lower.contains("ssl") || lower.contains("handshake")) {
            return "眼镜系统无法建立安全连接";
        }
        return message.trim();
    }

    void shutdown() {
        executor.shutdownNow();
    }
}
