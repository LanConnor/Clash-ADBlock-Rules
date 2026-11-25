package com.github.lanconnor.clashrules;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 构建全部 14 个变体的 Clash 规则包。
 *
 * <p>核心约束：按上游原始顺序把连续的同策略规则切成片段，
 * 每个片段一个 rule-provider，RULE-SET 按原顺序排列。
 * 不能按 REJECT / DIRECT / PROXY 分桶后重排，否则会改变首条匹配结果。</p>
 *
 * @author Connor.Wang
 */
@Component
public class BuildService {

    /** 片段内规则数不超过该值时直接内联。 */
    private static final int INLINE_CHUNK_LIMIT = 60;

    private static final Pattern CLASH_RULESET_URL = Pattern.compile(
            "(https://raw\\.githubusercontent\\.com/blackmatrix7/ios_rule_script)/([^/]+)"
                    + "/rule/(?:Shadowrocket|QuantumultX)/([^/]+)/[^/]+$");

    /**
     * 一条待渲染的规则。
     */
    private record RuleEntry(String type, String value, List<String> extra) {
    }

    /**
     * 输出规划项。
     */
    private enum Kind {
        CHUNK, INLINE, MATCH, RULESET
    }

    private record PlanItem(Kind kind, String policy, List<RuleEntry> entries, String text, String url) {

        static PlanItem chunk(String policy, List<RuleEntry> entries) {
            return new PlanItem(Kind.CHUNK, policy, entries, null, null);
        }

        static PlanItem inline(String text) {
            return new PlanItem(Kind.INLINE, null, null, text, null);
        }

        static PlanItem match(String policy) {
            return new PlanItem(Kind.MATCH, policy, null, null, null);
        }

        static PlanItem ruleset(String url, String policy) {
            return new PlanItem(Kind.RULESET, policy, null, null, url);
        }
    }

    private record Plan(List<PlanItem> items, Map<String, Integer> dropped, Set<String> customs) {
    }

    /**
     * 单个变体内部的 provider 注册表。
     */
    private static final class Registry {
        private final Set<String> usedNames = new HashSet<>();
        private final Map<String, String> urlNames = new LinkedHashMap<>();
        private final List<String[]> providers = new ArrayList<>();

        String uniq(String name) {
            String candidate = name;
            int suffix = 2;
            while (usedNames.contains(candidate)) {
                candidate = name + "-" + suffix;
                suffix++;
            }
            usedNames.add(candidate);
            return candidate;
        }

        String register(String name, String url) {
            String existing = urlNames.get(url);
            if (existing != null) {
                return existing;
            }
            String unique = uniq(name);
            providers.add(new String[]{unique, url});
            urlNames.put(url, unique);
            return unique;
        }
    }

    /**
     * 执行 build 子命令。
     *
     * @param args 命令行参数
     * @return 退出码
     * @throws Exception 构建失败
     */
    public int run(String[] args) throws Exception {
        Cli cli = Cli.parse(args, Set.of("--conf-dir", "--out", "--group", "--remote-base", "--variant"), Set.of());
        Path confDir = Path.of(require(cli, "--conf-dir"));
        Path out = Path.of(require(cli, "--out"));
        String group = cli.value("--group", "🚀 代理");
        String base = trimTrailingSlashes(require(cli, "--remote-base"));
        Set<String> wanted = new HashSet<>(cli.values("--variant"));
        Files.createDirectories(out);
        Files.createDirectories(out.resolve("common"));

        Map<String, Object> index = new LinkedHashMap<>();
        List<Object> variantsJson = new ArrayList<>();
        Map<String, Object> commonJson = new LinkedHashMap<>();
        index.put("variants", variantsJson);
        index.put("common", commonJson);

        Map<String, String> setRegistry = new LinkedHashMap<>();
        Set<String> fetchedRulesets = new HashSet<>();
        String upstreamTime = "";
        List<String> adReference = null;
        List<String> adBlockEntries = null;
        int commonAdUsers = 0;

        for (Variants.Entry variant : Variants.ALL) {
            if (!wanted.isEmpty() && !wanted.contains(variant.id())) {
                continue;
            }
            Path confPath = confDir.resolve(variant.file());
            if (!Files.exists(confPath)) {
                System.out.println("[!] 跳过 " + variant.id() + "：缺少 " + variant.file());
                continue;
            }
            ConfParser.Conf conf = ConfParser.parse(Utils.readUtf8(confPath));
            if (upstreamTime.isEmpty() && !conf.buildTime().isEmpty()) {
                upstreamTime = conf.buildTime();
            }
            if (conf.rules().isEmpty()) {
                System.out.println("[!] 跳过 " + variant.id() + "：没解析到规则");
                continue;
            }
            boolean ipv6 = "true".equalsIgnoreCase(
                    Utils.strip(String.valueOf(conf.general().getOrDefault("ipv6", "false"))));

            Plan plan = planRules(conf.rules(), group);
            for (String type : Utils.sortedCopy(plan.dropped().keySet())) {
                System.out.printf("[!] %s：跳过 %d 条不支持的规则类型 %s%n",
                        variant.id(), plan.dropped().get(type), type);
            }

            int reject = 0;
            int direct = 0;
            int proxy = 0;
            for (PlanItem item : plan.items()) {
                if (item.kind() != Kind.CHUNK) {
                    continue;
                }
                if ("REJECT".equals(item.policy())) {
                    reject += item.entries().size();
                } else if ("DIRECT".equals(item.policy())) {
                    for (RuleEntry entry : item.entries()) {
                        if (!entry.type().startsWith("IP-CIDR")) {
                            direct++;
                        }
                    }
                } else {
                    proxy += item.entries().size();
                }
            }

            List<String> adEntriesThis = Utils.dedupe(plan.items().stream()
                    .filter(item -> item.kind() == Kind.CHUNK && "REJECT".equals(item.policy()))
                    .flatMap(item -> item.entries().stream())
                    .map(entry -> Renderer.renderPayload(entry.type(), entry.value(), entry.extra()))
                    .toList());
            if (!adEntriesThis.isEmpty()) {
                if (adReference == null) {
                    adReference = Utils.sortedCopy(adEntriesThis);
                    adBlockEntries = adEntriesThis;
                } else if (!Utils.sortedCopy(adEntriesThis).equals(adReference)) {
                    System.out.println("[i] " + variant.id() + " 的广告集合与首个变体不同，将使用该变体自己的 provider");
                }
            }

            Path variantDir = out.resolve(variant.id());
            Files.createDirectories(variantDir);
            Registry registry = new Registry();
            List<String> rulesOut = new ArrayList<>();
            for (PlanItem item : plan.items()) {
                if (item.kind() == Kind.CHUNK) {
                    handleChunk(item, registry, rulesOut, variant, variantDir, base, setRegistry, adReference);
                } else if (item.kind() == Kind.MATCH) {
                    rulesOut.add("MATCH," + item.policy());
                } else if (item.kind() == Kind.INLINE) {
                    rulesOut.add(item.text());
                } else {
                    handleRuleset(item, registry, rulesOut, out, base, fetchedRulesets);
                }
            }

            if (rulesOut.stream().noneMatch(rule -> rule.startsWith("MATCH,"))) {
                rulesOut.add("MATCH,DIRECT");
            }
            String commonUrl = base + "/common/adblock.yaml";
            if (registry.urlNames.containsKey(commonUrl)) {
                commonAdUsers++;
            }
            List<String> customGroups = Utils.sortedCopy(plan.customs());
            long other = rulesOut.stream().filter(rule -> !rule.startsWith("RULE-SET,")).count();

            List<String> cfg = new ArrayList<>();
            cfg.add("# ============================================================");
            cfg.add("# " + variant.name() + "（变体 id: " + variant.id() + "）");
            cfg.add("# " + variant.description());
            cfg.add("# 上游: " + variant.file() + "   构建时间: " + (upstreamTime.isEmpty() ? "unknown" : upstreamTime));
            cfg.add("# 规则: REJECT " + reject + " / DIRECT " + direct + " / 代理 " + proxy + " / 其他 " + other);
            cfg.add("# ============================================================");
            cfg.add("");
            cfg.add("mixed-port: 7890");
            cfg.add("allow-lan: false");
            cfg.add("mode: rule");
            cfg.add("log-level: info");
            cfg.add("ipv6: " + (ipv6 ? "true" : "false"));
            cfg.add("tcp-concurrent: true");
            cfg.add("unified-delay: true");
            cfg.add("external-controller: 127.0.0.1:9090");
            cfg.add("");
            cfg.add(Renderer.dnsBlock(ipv6));
            cfg.add(Renderer.PROXIES_BLOCK);
            cfg.add(buildGroups(group, customGroups));
            cfg.add("rule-providers:");
            for (String[] provider : registry.providers) {
                cfg.add("  " + provider[0] + ":");
                cfg.add("    type: http");
                cfg.add("    behavior: classical");
                cfg.add("    format: yaml");
                cfg.add("    url: " + provider[1]);
                cfg.add("    path: ./ruleset/" + variant.id() + "-" + provider[0] + ".yaml");
                cfg.add("    interval: 86400");
            }
            cfg.add("");
            cfg.add("rules:");
            for (String rule : rulesOut) {
                cfg.add("  - " + rule);
            }
            Utils.writeUtf8(variantDir.resolve("config.yaml"), String.join("\n", cfg) + "\n");

            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("reject", reject);
            stats.put("direct", direct);
            stats.put("proxy", proxy);
            stats.put("custom_groups", customGroups.size());
            stats.put("total_rules", rulesOut.size());
            Map<String, Object> variantJson = new LinkedHashMap<>();
            variantJson.put("id", variant.id());
            variantJson.put("name", variant.name());
            variantJson.put("category", variant.category());
            variantJson.put("description", variant.description());
            variantJson.put("recommended", variant.recommended());
            variantJson.put("upstream_file", variant.file());
            variantJson.put("stats", stats);
            variantJson.put("config_url", base + "/" + variant.id() + "/config.yaml");
            Map<String, Object> providerJson = new LinkedHashMap<>();
            for (String[] provider : registry.providers) {
                providerJson.put(provider[0], provider[1]);
            }
            variantJson.put("providers", providerJson);
            variantsJson.add(variantJson);

            System.out.printf("[%s] %-14s REJECT %-6d DIRECT %-5d PROXY %-5d 自定义组 %d -> %d 条规则%n",
                    variant.recommended() ? "★" : " ", variant.id(), reject, direct, proxy,
                    customGroups.size(), rulesOut.size());
        }

        if (adBlockEntries != null) {
            List<String> entries = Utils.dedupe(adBlockEntries);
            Path commonFile = out.resolve("common/adblock.yaml");
            Renderer.writeSet(commonFile, entries, "广告拦截规则（各含广告变体共用）");
            Map<String, Object> adblock = new LinkedHashMap<>();
            adblock.put("path", "common/adblock.yaml");
            adblock.put("entries", entries.size());
            adblock.put("url", base + "/common/adblock.yaml");
            adblock.put("bytes", Files.size(commonFile));
            commonJson.put("adblock", adblock);
            System.out.printf("[common] adblock %d 条（%d 个变体共用这一份）%n", entries.size(), commonAdUsers);
        }

        Path defaultConfig = out.resolve("whitelist-ad/config.yaml");
        if (Files.exists(defaultConfig)) {
            Files.copy(defaultConfig, out.resolve("config.yaml"), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[+] 根目录 config.yaml = whitelist-ad（默认推荐）");
        }

        index.put("upstream_build_time", upstreamTime);
        Utils.writeUtf8(out.resolve("index.json"), JsonWriter.write(index));
        System.out.printf("[+] index.json 已生成（%d 个变体）%n", variantsJson.size());
        return 0;
    }

    private void handleChunk(PlanItem item, Registry registry, List<String> rulesOut, Variants.Entry variant,
                             Path variantDir, String base, Map<String, String> setRegistry,
                             List<String> adReference) throws IOException {
        List<String> payload = chunkPayload(item.entries());
        if ("REJECT".equals(item.policy()) && adReference != null
                && Utils.sortedCopy(payload).equals(adReference)) {
            rulesOut.add("RULE-SET," + useCommonAd(registry, base) + ",REJECT");
            return;
        }
        if (item.entries().size() <= INLINE_CHUNK_LIMIT) {
            for (RuleEntry entry : item.entries()) {
                String line = Renderer.ruleLine(entry.type(), entry.value(), entry.extra(), item.policy());
                if (line != null) {
                    rulesOut.add(line);
                }
            }
            return;
        }
        String title = variant.name() + " " + Renderer.chunkSlug(item.policy()) + " 规则";
        String name = addChunk(registry, item.policy(), payload, title, variantDir, base, variant.id(), setRegistry);
        rulesOut.add("RULE-SET," + name + "," + item.policy());
    }

    private void handleRuleset(PlanItem item, Registry registry, List<String> rulesOut, Path out,
                               String base, Set<String> fetchedRulesets) throws IOException {
        String url = item.url();
        String clashUrl = clashRulesetUrl(url);
        String slug = rulesetSlug(url, registry.providers.size());
        if (clashUrl != null) {
            rulesOut.add("RULE-SET," + registry.register("rs-" + slug, clashUrl) + "," + item.policy());
            return;
        }
        Path localDir = out.resolve("rulesets");
        Files.createDirectories(localDir);
        String fileName = "rs-" + slug + ".yaml";
        Path localFile = localDir.resolve(fileName);
        if (!fetchedRulesets.contains(fileName)) {
            try {
                String body = Http.fetch(url);
                List<String> items = new ArrayList<>();
                Map<String, Integer> localDropped = new LinkedHashMap<>();
                for (String line : body.split("\\R", -1)) {
                    String s = Utils.strip(line);
                    if (s.isEmpty() || s.startsWith("#")) {
                        continue;
                    }
                    String[] raw = s.split(",", -1);
                    List<String> parts = new ArrayList<>(raw.length);
                    for (String part : raw) {
                        parts.add(Utils.strip(part));
                    }
                    if (parts.size() < 2) {
                        continue;
                    }
                    List<String> extra = parts.subList(2, parts.size()).stream()
                            .filter(param -> "no-resolve".equals(param) || "src".equals(param))
                            .toList();
                    String type = parts.get(0).toUpperCase(Locale.ROOT);
                    String rendered = Renderer.renderPayload(type, parts.get(1), extra);
                    if (rendered != null) {
                        items.add(rendered);
                    } else {
                        localDropped.merge(type, 1, Integer::sum);
                    }
                }
                Renderer.writeSet(localFile, items, slug + "（本地化）");
                fetchedRulesets.add(fileName);
                System.out.printf("    · 本地化规则集 %s <- %s（%d 条）%n", fileName, url, items.size());
                for (String type : Utils.sortedCopy(localDropped.keySet())) {
                    System.out.printf("    ! 跳过 %d 条不支持的规则类型: %s%n", localDropped.get(type), type);
                }
            } catch (IOException | InterruptedException e) {
                System.out.printf("    ! 抓取失败 %s: %s%n", url, e.getMessage());
                return;
            }
        }
        rulesOut.add("RULE-SET," + registry.register("rs-" + slug, base + "/rulesets/" + fileName)
                + "," + item.policy());
    }

    private List<String> chunkPayload(List<RuleEntry> entries) {
        List<String> rendered = new ArrayList<>(entries.size());
        for (RuleEntry entry : entries) {
            rendered.add(Renderer.renderPayload(entry.type(), entry.value(), entry.extra()));
        }
        return Utils.dedupe(rendered);
    }

    private String addChunk(Registry registry, String policy, List<String> payload, String title,
                            Path variantDir, String base, String variantId,
                            Map<String, String> setRegistry) throws IOException {
        String key = Utils.sha256Hex(String.join("\n", payload));
        String existing = setRegistry.get(key);
        if (existing != null) {
            return registry.register(Renderer.chunkSlug(policy), existing);
        }
        String name = registry.uniq(Renderer.chunkSlug(policy));
        String fileName = name + ".yaml";
        Renderer.writeSet(variantDir.resolve(fileName), payload, title);
        String url = base + "/" + variantId + "/" + fileName;
        setRegistry.put(key, url);
        registry.providers.add(new String[]{name, url});
        registry.urlNames.put(url, name);
        return name;
    }

    private String useCommonAd(Registry registry, String base) {
        String url = base + "/common/adblock.yaml";
        String existing = registry.urlNames.get(url);
        if (existing != null) {
            return existing;
        }
        String name = registry.uniq("sr-adblock");
        registry.providers.add(new String[]{name, url});
        registry.urlNames.put(url, name);
        return name;
    }

    private Plan planRules(List<ConfParser.Rule> rules, String group) {
        List<PlanItem> items = new ArrayList<>();
        Map<String, Integer> dropped = new LinkedHashMap<>();
        Set<String> customs = new LinkedHashSet<>();
        for (ConfParser.Rule rule : rules) {
            String policy = Variants.canonPolicy(rule.policy(), group);
            if (!"REJECT".equals(policy) && !"DIRECT".equals(policy) && !group.equals(policy)) {
                customs.add(policy);
            }
            String type = rule.type();
            if ("FINAL".equals(type)) {
                items.add(PlanItem.match(policy));
                continue;
            }
            if ("RULE-SET".equals(type)) {
                items.add(PlanItem.ruleset(rule.value(), policy));
                continue;
            }
            if ("GEOIP".equals(type)) {
                items.add(PlanItem.inline(Renderer.ruleLine(type, rule.value(), rule.extra(), policy)));
                continue;
            }
            boolean bucketable = Renderer.renderPayload(type, rule.value(), rule.extra()) != null
                    && ("REJECT".equals(policy) || "DIRECT".equals(policy) || group.equals(policy));
            if (bucketable) {
                PlanItem last = items.isEmpty() ? null : items.get(items.size() - 1);
                if (last != null && last.kind() == Kind.CHUNK && policy.equals(last.policy())) {
                    last.entries().add(new RuleEntry(type, rule.value(), rule.extra()));
                } else {
                    List<RuleEntry> entries = new ArrayList<>();
                    entries.add(new RuleEntry(type, rule.value(), rule.extra()));
                    items.add(PlanItem.chunk(policy, entries));
                }
                continue;
            }
            String text = Renderer.ruleLine(type, rule.value(), rule.extra(), policy);
            if (text != null) {
                items.add(PlanItem.inline(text));
            } else {
                dropped.merge(type, 1, Integer::sum);
            }
        }
        return new Plan(items, dropped, customs);
    }

    private String buildGroups(String defaultGroup, List<String> customNames) {
        List<String> out = new ArrayList<>();
        out.add("proxy-groups:");
        out.add("  - name: \"" + defaultGroup + "\"");
        out.add("    type: select");
        out.add("    proxies: [\"⚡ 自动选择\", \"占位节点\", DIRECT]");
        out.add("  - name: \"⚡ 自动选择\"");
        out.add("    type: url-test");
        out.add("    url: http://www.gstatic.com/generate_204");
        out.add("    interval: 300");
        out.add("    tolerance: 50");
        out.add("    proxies: [\"占位节点\"]");
        for (String name : customNames) {
            out.add("  - name: \"" + name + "\"");
            out.add("    type: select");
            out.add("    proxies: [\"" + defaultGroup + "\", \"⚡ 自动选择\", DIRECT]");
        }
        return String.join("\n", out) + "\n";
    }

    private String clashRulesetUrl(String url) {
        Matcher matcher = CLASH_RULESET_URL.matcher(url);
        if (matcher.matches()) {
            return matcher.group(1) + "/" + matcher.group(2) + "/rule/Clash/"
                    + matcher.group(3) + "/" + matcher.group(3) + ".yaml";
        }
        return null;
    }

    private String rulesetSlug(String url, int index) {
        String name = url.split("\\?", 2)[0];
        while (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        int slash = name.lastIndexOf('/');
        name = slash >= 0 ? name.substring(slash + 1) : name;
        name = name.replaceFirst("(?i)\\.(list|txt|yaml|yml)$", "");
        name = name.replaceAll("[^0-9A-Za-z_-]", "");
        if (name.isEmpty()) {
            name = "set" + index;
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static String require(Cli cli, String name) {
        String value = cli.value(name);
        if (value == null) {
            throw new IllegalArgumentException("缺少参数: " + name);
        }
        return value;
    }

    private static String trimTrailingSlashes(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
