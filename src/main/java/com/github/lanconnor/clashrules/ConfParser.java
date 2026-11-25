package com.github.lanconnor.clashrules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 解析 Shadowrocket / Surge 风格的 .conf 规则文件。
 *
 * @author Connor.Wang
 */
final class ConfParser {

    private static final Pattern LINE_COMMENT = Pattern.compile("\\s+#.*$", Pattern.UNICODE_CHARACTER_CLASS);

    private ConfParser() {
    }

    /**
     * 一条规则。
     */
    record Rule(String type, String value, String policy, List<String> extra) {
    }

    /**
     * 一个 .conf 的解析结果。
     */
    record Conf(List<Rule> rules, Map<String, String> general, String buildTime, List<String> ignored) {
    }

    /**
     * 解析 .conf 文本，保持规则原始顺序。
     *
     * <p>策略固定取第 3 列，no-resolve 等参数从第 4 列开始，
     * 避免把参数误当成策略。</p>
     *
     * @param text 文件内容
     * @return 解析结果
     */
    static Conf parse(String text) {
        List<Rule> rules = new ArrayList<>();
        Map<String, String> general = new LinkedHashMap<>();
        List<String> ignored = new ArrayList<>();
        String section = null;
        String buildTime = "";
        for (String line : text.split("\\R", -1)) {
            String s = Utils.removeBom(Utils.strip(line));
            if (buildTime.isEmpty() && s.toLowerCase(Locale.ROOT).startsWith("# build time")) {
                int colon = s.indexOf(':');
                buildTime = Utils.strip(colon >= 0 ? s.substring(colon + 1) : s);
            }
            if (s.startsWith("[") && s.endsWith("]")) {
                section = Utils.strip(s.substring(1, s.length() - 1)).toLowerCase(Locale.ROOT);
                continue;
            }
            if (s.isEmpty() || s.startsWith("#") || s.startsWith(";")) {
                continue;
            }
            if ("general".equals(section)) {
                int equals = s.indexOf('=');
                if (equals >= 0) {
                    general.put(Utils.strip(s.substring(0, equals)).toLowerCase(Locale.ROOT),
                            Utils.strip(s.substring(equals + 1)));
                }
                continue;
            }
            if (!"rule".equals(section)) {
                continue;
            }
            s = Utils.strip(LINE_COMMENT.matcher(s).replaceFirst(""));
            if (s.isEmpty()) {
                continue;
            }
            String[] rawParts = s.split(",", -1);
            List<String> parts = new ArrayList<>(rawParts.length);
            for (String part : rawParts) {
                parts.add(Utils.strip(part));
            }
            String type = parts.get(0).toUpperCase(Locale.ROOT);
            if (parts.size() == 2 && ("FINAL".equals(type) || "MATCH".equals(type))) {
                rules.add(new Rule("FINAL", "", parts.get(1), List.of()));
                continue;
            }
            if (parts.size() < 3) {
                ignored.add(s);
                continue;
            }
            rules.add(new Rule(type, parts.get(1), parts.get(2),
                    List.copyOf(parts.subList(3, parts.size()))));
        }
        return new Conf(rules, general, buildTime, ignored);
    }
}
