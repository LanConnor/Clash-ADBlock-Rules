package com.github.lanconnor.clashrules;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 对构建产物做结构和语法体检，有问题就以非零退出码失败。
 *
 * <p>传入上游 conf 目录时，额外逐变体校验「每个规则键首次出现时的策略」
 * 与上游一致，等价于校验首条匹配语义。</p>
 *
 * @author Connor.Wang
 */
@Component
public class ValidateService {

    private static final Set<String> ALLOWED = Set.of(
            "DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX", "DOMAIN-WILDCARD",
            "GEOIP", "IP-CIDR", "IP-CIDR6", "SRC-IP-CIDR", "DST-PORT", "SRC-PORT",
            "PROCESS-NAME", "RULE-SET", "MATCH", "GEOSITE");

    private record RuleKey(String type, String value) {
    }

    private record PolicyEntry(RuleKey key, String policyClass) {
    }

    /**
     * 执行 validate 子命令。
     *
     * @param args 命令行参数：build 目录，可选上游 conf 目录
     * @return 退出码：有错误返回 1
     * @throws Exception 读取失败
     */
    public int run(String[] args) throws Exception {
        Path build = Path.of(args.length > 0 ? args[0] : "build");
        Path confDir = args.length > 1 ? Path.of(args[1]) : null;
        List<String> errors = new ArrayList<>();
        int configCount = 0;
        int payloadCount = 0;
        int entryCount = 0;

        List<Path> yamlFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(build)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .forEach(yamlFiles::add);
        }
        yamlFiles.sort((a, b) -> Utils.compareCodePoints(relative(build, a), relative(build, b)));

        for (Path path : yamlFiles) {
            String relative = relative(build, path);
            Object loaded;
            try {
                loaded = YamlSupport.load(path);
            } catch (Exception e) {
                errors.add(relative + " YAML 解析失败: " + e.getMessage());
                continue;
            }
            if (!(loaded instanceof Map<?, ?> document)) {
                errors.add(relative + " 顶层不是字典");
                continue;
            }
            if (document.containsKey("payload")) {
                payloadCount++;
                List<?> items = document.get("payload") instanceof List<?> list ? list : List.of();
                entryCount += items.size();
                for (Object item : items) {
                    String entry = String.valueOf(item);
                    String[] parts = entry.split(",", -1);
                    if (!ALLOWED.contains(parts[0])) {
                        errors.add(relative + " 未知规则类型: " + entry);
                        break;
                    }
                    if (parts[0].startsWith("IP-CIDR")) {
                        if (parts.length > 3 || (parts.length == 3 && !"no-resolve".equals(parts[2]))) {
                            errors.add(relative + " IP 规则参数异常: " + entry);
                            break;
                        }
                    } else if (parts.length > 2) {
                        errors.add(relative + " payload 里不应带策略: " + entry);
                        break;
                    }
                }
                continue;
            }

            configCount++;
            Set<String> groups = new HashSet<>();
            if (document.get("proxy-groups") instanceof List<?> groupList) {
                for (Object group : groupList) {
                    if (group instanceof Map<?, ?> groupMap && groupMap.get("name") != null) {
                        groups.add(String.valueOf(groupMap.get("name")));
                    }
                }
            }
            Map<?, ?> providers = document.get("rule-providers") instanceof Map<?, ?> map ? map : Map.of();
            Set<String> providerNames = new HashSet<>();
            for (Object key : providers.keySet()) {
                providerNames.add(String.valueOf(key));
            }
            List<?> rules = document.get("rules") instanceof List<?> list ? list : List.of();
            if (rules.isEmpty()) {
                errors.add(relative + " 没有 rules");
                continue;
            }
            Map<String, String> cachePaths = new HashMap<>();
            for (Map.Entry<?, ?> entry : providers.entrySet()) {
                String name = String.valueOf(entry.getKey());
                Map<?, ?> definition = entry.getValue() instanceof Map<?, ?> map ? map : Map.of();
                String cachePath = stringValue(definition, "path");
                String owner = cachePaths.putIfAbsent(cachePath, name);
                if (owner != null) {
                    errors.add(relative + " provider 缓存 path 重复: " + cachePath + " (" + owner + " / " + name + ")");
                }
            }
            for (Object ruleValue : rules) {
                String rule = String.valueOf(ruleValue);
                String[] parts = rule.split(",", -1);
                if (!ALLOWED.contains(parts[0])) {
                    errors.add(relative + " 未知规则类型: " + rule);
                    continue;
                }
                String policy;
                if ("RULE-SET".equals(parts[0])) {
                    if (parts.length < 3) {
                        errors.add(relative + " RULE-SET 缺少参数: " + rule);
                        continue;
                    }
                    if (!providerNames.contains(parts[1])) {
                        errors.add(relative + " 引用了未声明的 provider: " + parts[1]);
                    }
                    policy = parts[2];
                } else if ("MATCH".equals(parts[0])) {
                    policy = parts.length > 1 ? parts[1] : "";
                } else if (parts[0].startsWith("IP-CIDR")) {
                    policy = parts.length > 2 ? parts[2] : "";
                    for (int i = 3; i < parts.length; i++) {
                        if (!"no-resolve".equals(parts[i]) && !"src".equals(parts[i])) {
                            errors.add(relative + " IP 规则参数异常: " + rule);
                        }
                    }
                } else {
                    policy = parts[parts.length - 1];
                }
                if (!groups.contains(policy) && !"DIRECT".equals(policy) && !"REJECT".equals(policy)) {
                    errors.add(relative + " 策略不存在: " + policy + " -> " + rule);
                }
            }
            if (rules.stream().noneMatch(rule -> String.valueOf(rule).startsWith("MATCH,"))) {
                errors.add(relative + " 缺少 MATCH 兜底");
            }
        }

        int checked = confDir != null ? checkOrder(build, confDir, errors) : 0;
        System.out.printf("配置 %d 份 / 规则集 %d 份（%d 条）/ 顺序校验 %d 个变体 / 错误 %d 个%n",
                configCount, payloadCount, entryCount, checked, errors.size());
        for (int i = 0; i < Math.min(errors.size(), 20); i++) {
            System.out.println("  [x] " + errors.get(i));
        }
        return errors.isEmpty() ? 0 : 1;
    }

    /**
     * 逐变体比较「每个规则键首次出现时的策略类别」。
     *
     * @param build   构建目录
     * @param confDir 上游 conf 目录
     * @param errors  错误列表
     * @return 已校验的变体数
     * @throws IOException 读取失败
     */
    private int checkOrder(Path build, Path confDir, List<String> errors) throws IOException {
        int checked = 0;
        for (Variants.Entry variant : Variants.ALL) {
            Path config = build.resolve(variant.id()).resolve("config.yaml");
            Path conf = confDir.resolve(variant.file());
            if (!Files.exists(config) || !Files.exists(conf)) {
                continue;
            }
            Map<RuleKey, String> upstream = firstMap(firstPoliciesUpstream(conf));
            Map<RuleKey, String> generated = firstMap(expandConfig(config, build));
            List<String> bad = new ArrayList<>();
            for (Map.Entry<RuleKey, String> entry : upstream.entrySet()) {
                String actual = generated.get(entry.getKey());
                if (!entry.getValue().equals(actual)) {
                    bad.add(entry.getKey().type() + "," + entry.getKey().value()
                            + " 上游=" + entry.getValue() + " 生成=" + (actual == null ? "缺失" : actual));
                }
            }
            if (!bad.isEmpty()) {
                errors.add(variant.id() + " 首条匹配与上游不一致 " + bad.size() + " 处，例如: "
                        + String.join("; ", bad.subList(0, Math.min(8, bad.size()))));
            }
            checked++;
        }
        return checked;
    }

    private List<PolicyEntry> firstPoliciesUpstream(Path confPath) throws IOException {
        ConfParser.Conf conf = ConfParser.parse(Utils.readUtf8(confPath));
        List<PolicyEntry> entries = new ArrayList<>();
        for (ConfParser.Rule rule : conf.rules()) {
            if ("RULE-SET".equals(rule.type())) {
                continue;
            }
            entries.add(new PolicyEntry(ruleKey(rule.type(), rule.value()), policyClass(rule.policy())));
        }
        return entries;
    }

    private List<PolicyEntry> expandConfig(Path configPath, Path build) throws IOException {
        Map<String, Object> document = YamlSupport.loadMap(configPath);
        Map<String, List<String>> payloads = new HashMap<>();
        if (document.get("rule-providers") instanceof Map<?, ?> providers) {
            for (Map.Entry<?, ?> entry : providers.entrySet()) {
                Map<?, ?> definition = entry.getValue() instanceof Map<?, ?> map ? map : Map.of();
                String url = stringValue(definition, "url");
                Path local = localProviderFile(build, url);
                if (local == null) {
                    continue;
                }
                Map<String, Object> payloadDocument = YamlSupport.loadMap(local);
                List<String> payload = new ArrayList<>();
                if (payloadDocument.get("payload") instanceof List<?> items) {
                    for (Object item : items) {
                        payload.add(String.valueOf(item));
                    }
                }
                payloads.put(String.valueOf(entry.getKey()), payload);
            }
        }
        List<PolicyEntry> entries = new ArrayList<>();
        if (document.get("rules") instanceof List<?> rules) {
            for (Object ruleValue : rules) {
                String[] parts = String.valueOf(ruleValue).split(",", -1);
                if (parts.length < 2) {
                    continue;
                }
                String type = parts[0].toUpperCase(Locale.ROOT);
                if ("RULE-SET".equals(type)) {
                    if (parts.length < 3) {
                        continue;
                    }
                    List<String> payload = payloads.get(parts[1]);
                    if (payload == null) {
                        continue;
                    }
                    String policyClass = policyClass(parts[2]);
                    for (String item : payload) {
                        String[] fields = item.split(",", -1);
                        if (fields.length < 2) {
                            continue;
                        }
                        entries.add(new PolicyEntry(ruleKey(fields[0], fields[1]), policyClass));
                    }
                } else if ("MATCH".equals(type)) {
                    entries.add(new PolicyEntry(new RuleKey("MATCH", ""),
                            policyClass(parts.length > 1 ? parts[1] : "")));
                } else if (type.startsWith("IP-CIDR")) {
                    entries.add(new PolicyEntry(ruleKey(type, parts[1]),
                            policyClass(parts.length > 2 ? parts[2] : "")));
                } else {
                    entries.add(new PolicyEntry(ruleKey(type, parts[1]),
                            policyClass(parts[parts.length - 1])));
                }
            }
        }
        return entries;
    }

    private Map<RuleKey, String> firstMap(List<PolicyEntry> entries) {
        Map<RuleKey, String> map = new LinkedHashMap<>();
        for (PolicyEntry entry : entries) {
            map.putIfAbsent(entry.key(), entry.policyClass());
        }
        return map;
    }

    private static String policyClass(String raw) {
        String key = Utils.strip(raw).toLowerCase(Locale.ROOT);
        if (Renderer.REJECT_ALIASES.contains(key)) {
            return "REJECT";
        }
        if (Renderer.DIRECT_ALIASES.contains(key)) {
            return "DIRECT";
        }
        return "PROXY";
    }

    private static RuleKey ruleKey(String type, String value) {
        String upper = type.toUpperCase(Locale.ROOT);
        if ("FINAL".equals(upper) || "MATCH".equals(upper)) {
            return new RuleKey("MATCH", "");
        }
        if (upper.startsWith("IP-CIDR")) {
            upper = value.contains(":") ? "IP-CIDR6" : "IP-CIDR";
        }
        return new RuleKey(upper, value);
    }

    private static Path localProviderFile(Path build, String url) {
        String path = url.split("\\?", 2)[0];
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        List<String> pieces = new ArrayList<>();
        for (String piece : path.split("/")) {
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
        }
        if (pieces.size() < 2) {
            return null;
        }
        Path candidate = build.resolve(pieces.get(pieces.size() - 2)).resolve(pieces.get(pieces.size() - 1));
        return Files.exists(candidate) ? candidate : null;
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static String stringValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
