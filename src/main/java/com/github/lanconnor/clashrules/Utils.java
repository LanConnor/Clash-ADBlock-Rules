package com.github.lanconnor.clashrules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 通用工具方法。
 *
 * @author Connor.Wang
 */
final class Utils {

    /** 按 Unicode 码点比较字符串，与原 Python 版本的 sorted() 行为一致。 */
    static final Comparator<String> CODE_POINT_ORDER = Utils::compareCodePoints;

    private Utils() {
    }

    /**
     * 等价于 Python str.strip()：去掉首尾空白字符。
     *
     * @param value 原字符串
     * @return 去空白后的字符串
     */
    static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && Character.isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * 去掉字符串开头的 BOM 字符。
     *
     * @param value 原字符串
     * @return 去 BOM 后的字符串
     */
    static String removeBom(String value) {
        int start = 0;
        while (start < value.length() && value.charAt(start) == '﻿') {
            start++;
        }
        return value.substring(start);
    }

    /**
     * 按首次出现顺序去重，过滤空值。
     *
     * @param entries 原始条目
     * @return 去重后的条目
     */
    static List<String> dedupe(Collection<String> entries) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String entry : entries) {
            if (entry == null || entry.isEmpty() || !seen.add(entry)) {
                continue;
            }
            out.add(entry);
        }
        return out;
    }

    /**
     * 按 Unicode 码点比较两个字符串。
     *
     * @param a 字符串 a
     * @param b 字符串 b
     * @return 比较结果
     */
    static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return ca < cb ? -1 : 1;
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        if (i < a.length()) {
            return 1;
        }
        if (j < b.length()) {
            return -1;
        }
        return 0;
    }

    /**
     * 返回按码点排序后的副本。
     *
     * @param values 原集合
     * @return 排序后的列表
     */
    static List<String> sortedCopy(Collection<String> values) {
        List<String> out = new ArrayList<>(values);
        out.sort(CODE_POINT_ORDER);
        return out;
    }

    /**
     * 计算字节内容的 SHA-256 十六进制摘要。
     *
     * @param data 字节内容
     * @return 小写十六进制摘要
     */
    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 计算字符串（UTF-8）的 SHA-256 十六进制摘要。
     *
     * @param text 文本
     * @return 小写十六进制摘要
     */
    static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 以 UTF-8 读取文本文件。
     *
     * @param path 文件路径
     * @return 文件内容
     * @throws IOException 读取失败
     */
    static String readUtf8(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /**
     * 以 UTF-8 写入文本文件，自动创建父目录。
     *
     * @param path 文件路径
     * @param text 文件内容
     * @throws IOException 写入失败
     */
    static void writeUtf8(Path path, String text) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }
}
