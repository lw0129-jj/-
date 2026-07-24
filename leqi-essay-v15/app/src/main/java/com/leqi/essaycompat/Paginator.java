package com.lensmind.rokid;

import java.util.ArrayList;
import java.util.List;

final class Paginator {
    private Paginator() {}

    static List<String> paginate(String raw) {
        String text = raw == null ? "" : raw.trim().replace("\r", "");
        List<String> result = new ArrayList<String>();
        if (text.length() == 0) return result;

        boolean mostlyCjk = countCjk(text) > text.length() / 5;
        int limit = mostlyCjk ? 240 : 620;
        String[] paragraphs = text.split("\\n\\s*\\n+");
        StringBuilder page = new StringBuilder();

        for (String paragraph : paragraphs) {
            String clean = paragraph.trim();
            if (clean.length() == 0) continue;
            if (clean.length() > limit) {
                if (page.length() > 0) {
                    result.add(page.toString().trim());
                    page.setLength(0);
                }
                splitLong(clean, limit, result);
                continue;
            }
            int extra = page.length() == 0 ? clean.length() : clean.length() + 2;
            if (page.length() + extra > limit && page.length() > 0) {
                result.add(page.toString().trim());
                page.setLength(0);
            }
            if (page.length() > 0) page.append("\n\n");
            page.append(clean);
        }
        if (page.length() > 0) result.add(page.toString().trim());
        if (result.isEmpty()) result.add(text);
        return result;
    }

    private static void splitLong(String text, int limit, List<String> output) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + limit);
            if (end < text.length()) {
                int breakAt = findBreak(text, start, end);
                if (breakAt > start + limit / 2) end = breakAt;
            }
            output.add(text.substring(start, end).trim());
            start = end;
            while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        }
    }

    private static int findBreak(String text, int start, int end) {
        for (int i = end; i > start; i--) {
            char c = text.charAt(i - 1);
            if (c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？' || c == '\n' || c == ' ') return i;
        }
        return end;
    }

    private static int countCjk(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4E00' && c <= '\u9FFF') count++;
        }
        return count;
    }
}
