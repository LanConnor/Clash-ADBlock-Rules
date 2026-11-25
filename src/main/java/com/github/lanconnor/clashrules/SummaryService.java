package com.github.lanconnor.clashrules;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 把 index.json 渲染成 GitHub Actions 运行摘要（Markdown 表格）。
 *
 * @author Connor.Wang
 */
@Component
public class SummaryService {

    /**
     * 执行 summary 子命令。
     *
     * @param args 命令行参数
     * @return 退出码
     * @throws Exception 读取失败
     */
    public int run(String[] args) throws Exception {
        Path path = Path.of(args.length > 0 ? args[0] : "index.json");
        Map<String, Object> index = YamlSupport.loadMap(path);
        List<?> variants = index.get("variants") instanceof List<?> list ? list : List.of();

        System.out.println("| 变体 | id | 分类 | REJECT | 直连 | 代理 | 自定义组 |");
        System.out.println("|---|---|---|---|---|---|---|");
        for (Object value : variants) {
            Map<?, ?> variant = value instanceof Map<?, ?> map ? map : Map.of();
            Map<?, ?> stats = variant.get("stats") instanceof Map<?, ?> map ? map : Map.of();
            boolean recommended = Boolean.TRUE.equals(variant.get("recommended"));
            System.out.printf("| %s%s | `%s` | %s | %d | %d | %d | %d |%n",
                    variant.get("name"), recommended ? " ★" : "", variant.get("id"), variant.get("category"),
                    asInt(stats.get("reject")), asInt(stats.get("direct")),
                    asInt(stats.get("proxy")), asInt(stats.get("custom_groups")));
        }
        System.out.println();

        Object adblockEntries = null;
        if (index.get("common") instanceof Map<?, ?> common
                && common.get("adblock") instanceof Map<?, ?> adblock) {
            adblockEntries = adblock.get("entries");
        }
        System.out.printf("上游构建时间：%s ｜ 变体数：%d ｜ 去重前广告规则：%s 条%n",
                index.getOrDefault("upstream_build_time", "?"), variants.size(),
                adblockEntries == null ? "?" : adblockEntries);
        return 0;
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
