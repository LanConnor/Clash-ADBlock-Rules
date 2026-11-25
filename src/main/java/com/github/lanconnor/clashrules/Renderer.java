package com.github.lanconnor.clashrules;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 规则与 YAML 片段的渲染工具。
 *
 * <p>所有产物都用手工拼接而不是 YAML 库输出，保证字节稳定，
 * 这样 content_hash 在规则没变化时不会漂移。</p>
 *
 * @author Connor.Wang
 */
final class Renderer {

    static final Set<String> REJECT_ALIASES = Set.of(
            "reject", "reject-drop", "reject-no-drop", "reject-img", "reject-tinygif");
    static final Set<String> DIRECT_ALIASES = Set.of("direct", "directly");

    private static final Set<String> DOMAIN_TYPES = Set.of(
            "DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX",
            "DOMAIN-WILDCARD", "PROCESS-NAME", "GEOIP");

    private static final Pattern RULESET_EXT = Pattern.compile("\\.(list|yaml|yml|txt|conf)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSAFE_NAME = Pattern.compile("[^0-9A-Za-z_.-]");
    private static final Pattern BM7_SHADOWROCKET = Pattern.compile(
            "^(https://raw\\.githubusercontent\\.com/blackmatrix7/ios_rule_script/[^/]+/rule/)Shadowrocket/([^/]+)/[^/]+$");

    private static final String DNS_TEMPLATE = String.join("\n",
            "dns:",
            "  enable: true",
            "  ipv6: {{IPV6}}",
            "  enhanced-mode: fake-ip",
            "  fake-ip-range: 198.18.0.1/16",
            "  fake-ip-filter:",
            "    - '*.lan'",
            "    - '*.local'",
            "    - '+.msftconnecttest.com'",
            "    - '+.msftncsi.com'",
            "    - 'time.*.com'",
            "    - 'ntp.*.com'",
            "  default-nameserver:",
            "    - 223.5.5.5",
            "    - 119.29.29.29",
            "  nameserver:",
            "    - https://dns.alidns.com/dns-query",
            "    - https://doh.pub/dns-query",
            "  proxy-server-nameserver:",
            "    - https://dns.alidns.com/dns-query",
            "  fallback:",
            "    - https://1.1.1.1/dns-query",
            "    - https://dns.google/dns-query",
            "  fallback-filter:",
            "    geoip: true",
            "    geoip-code: CN") + "\n";

    static final String PROXIES_BLOCK = String.join("\n",
            "proxies:",
            "  # ↓↓↓ 换成你自己的节点，或用 proxy-providers 拉你的机场订阅 ↓↓↓",
            "  - name: \"占位节点\"",
            "    type: ss",
            "    server: 1.2.3.4",
            "    port: 8388",
            "    cipher: aes-256-gcm",
            "    password: \"password\"",
            "    udp: true") + "\n";

    /** sr2clash 单文件模式沿用的占位节点说明。 */
    static final String SR2CLASH_PROXIES_BLOCK = String.join("\n",
            "proxies:",
            "  # ↓↓↓ 这里换成你自己的节点（也可以用订阅转换后只保留 rules 段） ↓↓↓",
            "  - name: \"占位节点\"",
            "    type: ss",
            "    server: 1.2.3.4",
            "    port: 8388",
            "    cipher: aes-256-gcm",
            "    password: \"password\"",
            "    udp: true") + "\n";

    private Renderer() {
    }

    /**
     * 生成 DNS 段，ipv6 与上游 [General] 的开关保持一致。
     *
     * @param ipv6 是否启用 IPv6
     * @return DNS 段文本
     */
    static String dnsBlock(boolean ipv6) {
        return DNS_TEMPLATE.replace("{{IPV6}}", ipv6 ? "true" : "false");
    }

    /**
     * 把策略名归一成 REJECT / DIRECT / 代理组名。
     *
     * @param raw        原始策略
     * @param proxyGroup 代理组名
     * @return 归一后的策略
     */
    static String mapPolicy(String raw, String proxyGroup) {
        String p = Utils.strip(raw);
        String lower = p.toLowerCase(Locale.ROOT);
        if (REJECT_ALIASES.contains(lower)) {
            return "REJECT";
        }
        if (DIRECT_ALIASES.contains(lower)) {
            return "DIRECT";
        }
        return proxyGroup;
    }

    /**
     * 渲染一条带策略的 Clash 规则（sr2clash 单文件模式使用）。
     *
     * @param rule       规则
     * @param proxyGroup 代理组名
     * @return 规则行，类型不支持时返回 null
     */
    static String toClashRule(ConfParser.Rule rule, String proxyGroup) {
        String policy = mapPolicy(rule.policy(), proxyGroup);
        String type = rule.type();
        String value = rule.value();
        if (DOMAIN_TYPES.contains(type) && !"GEOIP".equals(type)) {
            return type + "," + value + "," + policy;
        }
        if (isIpRule(type)) {
            String tail = rule.extra().isEmpty() ? "no-resolve" : String.join(",", rule.extra());
            return ipType(type, value) + "," + value + "," + policy + "," + tail;
        }
        return switch (type) {
            case "GEOIP" -> "GEOIP," + value + "," + policy;
            case "FINAL" -> "MATCH," + policy;
            case "RULE-SET" -> "RULE-SET," + rulesetName(value) + "," + policy;
            default -> null;
        };
    }

    /**
     * 渲染 rule-provider payload 里的一条规则（不带策略）。
     *
     * @param type  规则类型
     * @param value 规则值
     * @param extra 参数列表
     * @return payload 行，类型不支持时返回 null
     */
    static String renderPayload(String type, String value, List<String> extra) {
        if (isIpRule(type)) {
            String tail = extra == null || extra.isEmpty() ? "no-resolve" : String.join(",", extra);
            return ipType(type, value) + "," + value + "," + tail;
        }
        if (DOMAIN_TYPES.contains(type)) {
            return type + "," + value;
        }
        return null;
    }

    /**
     * 渲染一条配置里的规则；policy 为 null 时用于 provider payload。
     *
     * @param type   规则类型
     * @param value  规则值
     * @param extra  参数列表
     * @param policy 策略，可为 null
     * @return 规则行，类型不支持时返回 null
     */
    static String ruleLine(String type, String value, List<String> extra, String policy) {
        if ("FINAL".equals(type)) {
            return policy == null ? null : "MATCH," + policy;
        }
        if (isIpRule(type)) {
            String tail = extra == null || extra.isEmpty() ? "no-resolve" : String.join(",", extra);
            String head = ipType(type, value) + "," + value;
            return policy == null ? head + "," + tail : head + "," + policy + "," + tail;
        }
        if (DOMAIN_TYPES.contains(type)) {
            String head = type + "," + value;
            return policy == null ? head : head + "," + policy;
        }
        return null;
    }

    /**
     * 生成一组 proxy-group（sr2clash 单文件模式使用）。
     *
     * @param proxyGroup 代理组名
     * @param nodeNames  节点名列表
     * @return proxy-groups 段
     */
    static String groupsBlock(String proxyGroup, List<String> nodeNames) {
        String nodes = nodeNames.stream().map(name -> "\"" + name + "\"").collect(Collectors.joining(", "));
        return "proxy-groups:\n"
                + "  - name: \"" + proxyGroup + "\"\n"
                + "    type: select\n"
                + "    proxies: [\"⚡ 自动选择\", " + nodes + ", DIRECT]\n"
                + "  - name: \"⚡ 自动选择\"\n"
                + "    type: url-test\n"
                + "    url: http://www.gstatic.com/generate_204\n"
                + "    interval: 300\n"
                + "    tolerance: 50\n"
                + "    proxies: [" + nodes + "]\n";
    }

    /**
     * 写出 rule-provider 文件。
     *
     * @param path    文件路径
     * @param entries payload 条目
     * @param title   注释标题
     * @throws IOException 写入失败
     */
    static void writeSet(Path path, List<String> entries, String title) throws IOException {
        List<String> unique = Utils.dedupe(entries);
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append('\n');
        sb.append("# ").append(unique.size()).append(" 条规则，供 Clash rule-provider 使用（behavior: classical）\n");
        sb.append("payload:\n");
        for (String entry : unique) {
            sb.append("  - '").append(entry).append("'\n");
        }
        Utils.writeUtf8(path, sb.toString());
    }

    /**
     * 从规则集 URL 取 provider 名（sr2clash 单文件模式）。
     *
     * @param url 规则集地址
     * @return provider 名
     */
    static String rulesetName(String url) {
        String path = url.split("\\?", 2)[0];
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int slash = path.lastIndexOf('/');
        String seg = slash >= 0 ? path.substring(slash + 1) : path;
        seg = RULESET_EXT.matcher(seg).replaceFirst("");
        seg = UNSAFE_NAME.matcher(seg).replaceAll("_");
        return seg.isEmpty() ? "ruleset" : seg;
    }

    /**
     * blackmatrix7 的 Shadowrocket 规则集换成同仓库的 Clash 版本。
     *
     * @param url 原始地址
     * @return Clash 版地址
     */
    static String clashUrlForRuleset(String url) {
        var matcher = BM7_SHADOWROCKET.matcher(url);
        if (matcher.matches()) {
            return matcher.group(1) + "Clash/" + matcher.group(2) + "/" + matcher.group(2) + ".yaml";
        }
        return url;
    }

    /**
     * 规则片段对应的 provider 文件名 slug。
     *
     * @param policy 策略
     * @return slug
     */
    static String chunkSlug(String policy) {
        return switch (policy) {
            case "REJECT" -> "adblock";
            case "DIRECT" -> "direct";
            default -> "proxy";
        };
    }

    private static boolean isIpRule(String type) {
        return "IP-CIDR".equals(type) || "IP-CIDR6".equals(type);
    }

    private static String ipType(String type, String value) {
        return "IP-CIDR6".equals(type) || value.contains(":") ? "IP-CIDR6" : "IP-CIDR";
    }
}
