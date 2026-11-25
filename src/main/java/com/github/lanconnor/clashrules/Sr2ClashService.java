package com.github.lanconnor.clashrules;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单文件转换工具：Shadowrocket .conf -> Clash YAML（inline / provider 两种模式）。
 *
 * @author Connor.Wang
 */
@Component
public class Sr2ClashService {

    /**
     * 一条待渲染的规则。
     */
    private record RuleEntry(String type, String value, List<String> extra) {
    }

    private enum Kind {
        CHUNK, INLINE, RULESET
    }

    private record PlanItem(Kind kind, String policy, List<RuleEntry> entries, String text, String url) {

        static PlanItem chunk(String policy, List<RuleEntry> entries) {
            return new PlanItem(Kind.CHUNK, policy, entries, null, null);
        }

        static PlanItem inline(String text) {
            return new PlanItem(Kind.INLINE, null, null, text, null);
        }

        static PlanItem ruleset(String url, String policy) {
            return new PlanItem(Kind.RULESET, policy, null, null, url);
        }
    }

    private record Plan(List<PlanItem> items, Map<String, Integer> dropped) {
    }

    private record BuildResult(List<String> lines, Map<String, String> providers,
                               Map<String, Integer> stat, Map<String, Integer> dropped) {
    }

    /**
     * 执行 sr2clash 子命令。
     *
     * @param args 命令行参数
     * @return 退出码
     * @throws Exception 转换失败
     */
    public int run(String[] args) throws Exception {
        Cli cli = Cli.parse(args,
                Set.of("-i", "--input", "-u", "--url", "-o", "--out", "-g", "--group", "--mode", "--remote-base"),
                Set.of("--no-dedupe"));
        String url = firstNonNull(cli.value("-u"), cli.value("--url"));
        String input = firstNonNull(cli.value("-i"), cli.value("--input"));
        Path outDir = Path.of(cli.value("-o", cli.value("--out", "clash")));
        String group = cli.value("-g", cli.value("--group", "🚀 代理"));
        String mode = cli.value("--mode", "inline");
        boolean dedupe = !cli.has("--no-dedupe");
        String remoteBase = cli.value("--remote-base", "");

        String text;
        String src;
        if (url != null) {
            System.out.println("[*] 下载规则: " + url);
            text = Http.fetch(url);
            src = url;
        } else if (input != null) {
            Path inputPath = Path.of(input);
            text = Utils.readUtf8(inputPath);
            src = inputPath.toAbsolutePath().toString();
        } else {
            throw new IllegalArgumentException("必须指定 -i 或 -u");
        }
        ConfParser.Conf conf = ConfParser.parse(text);
        if (conf.rules().isEmpty()) {
            throw new IllegalStateException("[!] 没解析到任何规则，检查文件是否为 [Rule] 格式的 .conf");
        }
        boolean ipv6 = "true".equalsIgnoreCase(
                Utils.strip(String.valueOf(conf.general().getOrDefault("ipv6", "false"))));

        BuildResult built = buildRules(conf.rules(), group, dedupe);
        System.out.printf("[*] 解析到 %d 条规则，去重后 %d 条 (REJECT=%d DIRECT=%d 代理=%d)%n",
                conf.rules().size(), built.lines().size(),
                built.stat().get("REJECT"), built.stat().get("DIRECT"), built.stat().get("PROXY"));
        for (String type : Utils.sortedCopy(built.dropped().keySet())) {
            System.out.printf("[!] 跳过 %d 条不支持的规则类型: %s%n", built.dropped().get(type), type);
        }

        Files.createDirectories(outDir);
        String header = header(src, conf.buildTime().isEmpty() ? "unknown" : conf.buildTime(), built.stat(), group);
        if ("inline".equals(mode)) {
            writeInline(outDir, header, ipv6, group, built);
        } else if ("provider".equals(mode)) {
            writeProviders(outDir, header, ipv6, group, remoteBase, conf.rules());
        } else {
            throw new IllegalArgumentException("未知 mode: " + mode);
        }
        return 0;
    }

    private BuildResult buildRules(List<ConfParser.Rule> rules, String group, boolean dedupe) {
        List<String> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, String> providers = new LinkedHashMap<>();
        Map<String, Integer> stat = new LinkedHashMap<>();
        stat.put("REJECT", 0);
        stat.put("DIRECT", 0);
        stat.put("PROXY", 0);
        Map<String, Integer> dropped = new LinkedHashMap<>();
        for (ConfParser.Rule rule : rules) {
            String line = Renderer.toClashRule(rule, group);
            if (line == null) {
                dropped.merge(rule.type(), 1, Integer::sum);
                continue;
            }
            if ("RULE-SET".equals(rule.type())) {
                providers.put(Renderer.rulesetName(rule.value()), Renderer.clashUrlForRuleset(rule.value()));
            }
            if (dedupe && !seen.add(line)) {
                continue;
            }
            lines.add(line);
            String policy = Renderer.mapPolicy(rule.policy(), group);
            String bucket = "REJECT".equals(policy) ? "REJECT" : ("DIRECT".equals(policy) ? "DIRECT" : "PROXY");
            stat.merge(bucket, 1, Integer::sum);
        }
        return new BuildResult(lines, providers, stat, dropped);
    }

    private void writeInline(Path outDir, String header, boolean ipv6, String group, BuildResult built)
            throws IOException {
        StringBuilder sb = new StringBuilder(header);
        sb.append("\nmixed-port: 7890\nallow-lan: false\nmode: rule\nlog-level: info\n");
        sb.append("ipv6: ").append(ipv6 ? "true" : "false").append("\ntcp-concurrent: true\nunified-delay: true\n");
        sb.append("external-controller: 127.0.0.1:9090\n\n");
        sb.append(Renderer.dnsBlock(ipv6));
        sb.append("\n").append(Renderer.SR2CLASH_PROXIES_BLOCK).append("\n");
        sb.append(Renderer.groupsBlock(group, List.of("占位节点"))).append("\n");
        appendRuleProviders(sb, built.providers());
        appendRules(sb, built.lines());
        Path config = outDir.resolve("config.yaml");
        Utils.writeUtf8(config, sb.toString());
        System.out.printf("[+] 已生成 %s (%.1f KB)%n", config, Files.size(config) / 1024.0);

        StringBuilder rulesOnly = new StringBuilder(header);
        rulesOnly.append("# 用法: 把下面整段 rules: 替换/追加到你现有的 Clash 配置末尾，\n");
        rulesOnly.append("#       并保证配置里存在名为 \"").append(group).append("\" 的 proxy-group。\n");
        rulesOnly.append("#       如果只是追加，记得保留原来的 MATCH 行在最后一条。\n\n");
        appendRuleProviders(rulesOnly, built.providers());
        appendRules(rulesOnly, built.lines());
        Path rulesPath = outDir.resolve("rules-only.yaml");
        Utils.writeUtf8(rulesPath, rulesOnly.toString());
        System.out.printf("[+] 已生成 %s (%.1f KB)%n", rulesPath, Files.size(rulesPath) / 1024.0);
    }

    private void writeProviders(Path outDir, String header, boolean ipv6, String group,
                                String remoteBase, List<ConfParser.Rule> rules) throws IOException {
        Path providerDir = outDir.resolve("providers");
        Files.createDirectories(providerDir);
        Plan plan = planProviderChunks(rules, group);
        for (String type : Utils.sortedCopy(plan.dropped().keySet())) {
            System.out.printf("[!] 跳过 %d 条不支持的规则类型: %s%n", plan.dropped().get(type), type);
        }

        List<String[]> providerDefs = new ArrayList<>();
        List<String> rulesOut = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        Map<String, Integer> chunkCounts = new LinkedHashMap<>();
        for (PlanItem item : plan.items()) {
            if (item.kind() == Kind.CHUNK) {
                String slug = Renderer.chunkSlug(item.policy());
                chunkCounts.merge(slug, 1, Integer::sum);
                String name = uniq(usedNames, slug + "-" + chunkCounts.get(slug));
                String fileName = name + ".yaml";
                List<String> payload = Utils.dedupe(item.entries().stream()
                        .map(entry -> Renderer.renderPayload(entry.type(), entry.value(), entry.extra()))
                        .toList());
                StringBuilder chunk = new StringBuilder();
                chunk.append("# ").append(name).append(" rules, ").append(payload.size()).append(" entries\npayload:\n");
                for (String line : payload) {
                    chunk.append("  - '").append(line).append("'\n");
                }
                Utils.writeUtf8(providerDir.resolve(fileName), chunk.toString());
                providerDefs.add(new String[]{name, fileName});
                rulesOut.add("RULE-SET," + name + "," + item.policy());
                System.out.printf("[+] 已生成 %s (%d 条)%n", providerDir.resolve(fileName), payload.size());
            } else if (item.kind() == Kind.RULESET) {
                String name = uniq(usedNames, Renderer.rulesetName(item.url()));
                providerDefs.add(new String[]{name, Renderer.clashUrlForRuleset(item.url())});
                rulesOut.add("RULE-SET," + name + "," + item.policy());
            } else {
                rulesOut.add(item.text());
            }
        }

        StringBuilder sb = new StringBuilder(header);
        sb.append("\nmixed-port: 7890\nallow-lan: false\nmode: rule\nlog-level: info\n");
        sb.append("ipv6: ").append(ipv6 ? "true" : "false").append("\ntcp-concurrent: true\nunified-delay: true\n");
        sb.append("external-controller: 127.0.0.1:9090\n\n");
        sb.append(Renderer.dnsBlock(ipv6)).append("\n").append(Renderer.SR2CLASH_PROXIES_BLOCK).append("\n");
        sb.append(Renderer.groupsBlock(group, List.of("占位节点"))).append("\n");
        sb.append("rule-providers:\n");
        String remote = trimTrailingSlashes(remoteBase);
        for (String[] definition : providerDefs) {
            String name = definition[0];
            String target = definition[1];
            if (!target.startsWith("http")) {
                if (!remote.isEmpty()) {
                    target = remote + "/providers/" + target;
                } else {
                    sb.append("  ").append(name)
                            .append(":\n    type: file\n    behavior: classical\n    format: yaml\n")
                            .append("    path: ./providers/").append(target).append('\n');
                    continue;
                }
            }
            sb.append("  ").append(name)
                    .append(":\n    type: http\n    behavior: classical\n    format: yaml\n")
                    .append("    url: ").append(target).append('\n')
                    .append("    path: ./ruleset/").append(name).append(".yaml\n")
                    .append("    interval: 86400\n");
        }
        sb.append("\nrules:\n");
        for (String line : rulesOut) {
            sb.append("  - ").append(line).append('\n');
        }
        Path path = outDir.resolve("config-providers.yaml");
        Utils.writeUtf8(path, sb.toString());
        System.out.printf("[+] 已生成 %s (%.1f KB)%n", path, Files.size(path) / 1024.0);
    }

    private Plan planProviderChunks(List<ConfParser.Rule> rules, String group) {
        List<PlanItem> items = new ArrayList<>();
        Map<String, Integer> dropped = new LinkedHashMap<>();
        for (ConfParser.Rule rule : rules) {
            String policy = Renderer.mapPolicy(rule.policy(), group);
            String type = rule.type();
            if ("FINAL".equals(type)) {
                items.add(PlanItem.inline("MATCH," + policy));
                continue;
            }
            if ("RULE-SET".equals(type)) {
                items.add(PlanItem.ruleset(rule.value(), policy));
                continue;
            }
            boolean bucketable = !"GEOIP".equals(type)
                    && ("REJECT".equals(policy) || "DIRECT".equals(policy) || group.equals(policy))
                    && Renderer.renderPayload(type, rule.value(), rule.extra()) != null;
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
            String text = Renderer.toClashRule(rule, group);
            if (text != null) {
                items.add(PlanItem.inline(text));
            } else {
                dropped.merge(type, 1, Integer::sum);
            }
        }
        return new Plan(items, dropped);
    }

    private void appendRuleProviders(StringBuilder sb, Map<String, String> providers) {
        if (providers.isEmpty()) {
            return;
        }
        sb.append("rule-providers:\n");
        for (Map.Entry<String, String> entry : providers.entrySet()) {
            sb.append("  ").append(entry.getKey())
                    .append(":\n    type: http\n    behavior: classical\n")
                    .append("    url: ").append(entry.getValue()).append('\n')
                    .append("    path: ./ruleset/").append(entry.getKey()).append(".yaml\n")
                    .append("    interval: 86400\n");
        }
        sb.append("\n");
    }

    private void appendRules(StringBuilder sb, List<String> lines) {
        sb.append("rules:\n");
        for (String line : lines) {
            sb.append("  - ").append(line).append('\n');
        }
    }

    private String header(String src, String buildTime, Map<String, Integer> stat, String group) {
        int total = stat.get("REJECT") + stat.get("DIRECT") + stat.get("PROXY");
        return "# ============================================================\n"
                + "# 由 sr2clash.py 从 Shadowrocket 规则生成\n"
                + "# 源文件: " + src + "\n"
                + "# 规则构建时间: " + buildTime + "\n"
                + "# 规则条数: " + total + "  (REJECT=" + stat.get("REJECT")
                + ", DIRECT=" + stat.get("DIRECT") + ", 代理=" + stat.get("PROXY") + ")\n"
                + "# 代理组名: " + group + "\n"
                + "# ============================================================\n";
    }

    private static String uniq(Set<String> usedNames, String name) {
        String candidate = name;
        int suffix = 2;
        while (usedNames.contains(candidate)) {
            candidate = name + "-" + suffix;
            suffix++;
        }
        usedNames.add(candidate);
        return candidate;
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }

    private static String trimTrailingSlashes(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
