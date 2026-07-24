package com.leqi.essaycompat;

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

    private static final String API_KEY = "REPLACE_WITH_LOCAL_API_KEY_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX";
    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final String VISION_MODEL = "gpt-4o-mini";
    private static final String WRITING_MODEL = "gpt-5.6-luna";

    private static final String OCR_PROMPT =
            "Read the entire writing assignment in the image accurately. " +
            "Return only a faithful transcription of the prompt, source material, questions, format requirements, language, and word limit. " +
            "Do not answer the assignment. If a small part is unclear, infer conservatively and mark it [unclear].";

    private static final String ESSAY_RULES =
            "You are a writing assistant for a high-school student. Write the final response to the assignment below.\n\n" +
            "Follow every instruction in the assignment, including language, genre, audience, required ideas, source material, and word limit. " +
            "When the assignment is an ordinary essay, use exactly five substantial paragraphs: an introduction, three distinct body paragraphs, and a conclusion. " +
            "The three body paragraphs must develop genuinely different points and must not repeat one another. " +
            "For narrative writing, the three middle paragraphs should cover development, conflict or turning point, and outcome. " +
            "For emails, speeches, reports, or other required formats, preserve the required format while still organizing the main content into three clear middle sections where natural.\n\n" +
            "For English writing, use natural B1-C1 vocabulary, mainly B2, with occasional accurate C1 expressions. " +
            "Do not make the vocabulary childish, but do not pile up rare academic words. Mix clear simple sentences with natural complex sentences. " +
            "Avoid obvious AI phrases, fake quotations, invented studies, fabricated statistics, and empty introductions. " +
            "Use concrete explanations or examples. Keep the voice believable for a strong high-school student.\n\n" +
            "Output only the finished piece. Do not include an outline, analysis, score, vocabulary list, paragraph labels, notes, or commentary.";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    void createEssay(final byte[] jpeg, final Callback callback) {
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    if (API_KEY.startsWith("REPLACE_") || API_KEY.length() < 20) {
                        throw new IllegalStateException("API Key 未写入安装包");
                    }
                    callback.onProgress("正在识别题目…");
                    String task = recognizeTask(jpeg);
                    if (task.trim().length() == 0) throw new IllegalStateException("没有识别到作文题目");
                    callback.onProgress("正在写作文…");
                    String essay = writeEssay(task);
                    if (essay.trim().length() == 0) throw new IllegalStateException("模型没有返回作文");
                    callback.onSuccess(essay.trim());
                } catch (Throwable error) {
                    String message = error.getMessage();
                    if (message == null || message.trim().length() == 0) message = "请求失败，请重试";
                    callback.onError(message);
                }
            }
        });
    }

    private static String recognizeTask(byte[] jpeg) throws Exception {
        String base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP);
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", OCR_PROMPT));
        content.put(new JSONObject().put("type", "image_url").put("image_url",
                new JSONObject().put("url", "data:image/jpeg;base64," + base64).put("detail", "high")));

        JSONObject body = new JSONObject();
        body.put("model", VISION_MODEL);
        body.put("temperature", 0);
        body.put("max_tokens", 1400);
        body.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", content)));
        return send(body, 120000);
    }

    private static String writeEssay(String task) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", WRITING_MODEL);
        body.put("reasoning_effort", "none");
        body.put("max_completion_tokens", 5200);
        body.put("messages", new JSONArray()
                .put(new JSONObject().put("role", "developer").put("content", ESSAY_RULES))
                .put(new JSONObject().put("role", "user").put("content", "WRITING ASSIGNMENT:\n" + task)));
        return send(body, 220000);
    }

    private static String send(JSONObject body, int readTimeout) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(25000);
            connection.setReadTimeout(readTimeout);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + API_KEY);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(payload);
            } finally {
                output.close();
            }

            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String responseText = input == null ? "" : readAll(input);
            if (input != null) input.close();
            JSONObject response = new JSONObject(responseText.length() == 0 ? "{}" : responseText);

            if (status < 200 || status >= 300) {
                JSONObject error = response.optJSONObject("error");
                String message = error == null ? "API 请求失败：" + status : error.optString("message", "API 请求失败：" + status);
                throw new IllegalStateException(message);
            }

            JSONArray choices = response.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw new IllegalStateException("模型没有返回内容");
            JSONObject first = choices.optJSONObject(0);
            JSONObject message = first == null ? null : first.optJSONObject("message");
            if (message == null) throw new IllegalStateException("模型返回格式异常");
            Object content = message.opt("content");
            if (content instanceof String) return ((String) content).trim();
            if (content instanceof JSONArray) {
                StringBuilder text = new StringBuilder();
                JSONArray array = (JSONArray) content;
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item != null) {
                        String part = item.optString("text", "").trim();
                        if (part.length() > 0) {
                            if (text.length() > 0) text.append("\n");
                            text.append(part);
                        }
                    }
                }
                return text.toString();
            }
            throw new IllegalStateException("模型没有返回文字");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    void shutdown() {
        executor.shutdownNow();
    }
}
