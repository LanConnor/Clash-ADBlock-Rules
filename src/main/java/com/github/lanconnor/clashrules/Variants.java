package com.github.lanconnor.clashrules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 上游 14 个变体的清单与策略归一规则。
 *
 * @author Connor.Wang
 */
final class Variants {

    /**
     * 一个上游变体。
     *
     * @param id          产物目录名
     * @param file        上游文件名
     * @param name        中文名
     * @param category    分类
     * @param description 一句话说明
     * @param recommended 是否推荐默认
     */
    record Entry(String id, String file, String name, String category, String description, boolean recommended) {
    }

    static final List<Entry> ALL = List.of(
            new Entry("whitelist-ad", "sr_top500_whitelist_ad.conf", "白名单 + 去广告", "白名单",
                    "top500 里可直连的走直连，其余境外站点走代理，同时拦截广告。日常最通用的选择。", true),
            new Entry("whitelist", "sr_top500_whitelist.conf", "白名单", "白名单",
                    "同上，但不含广告拦截规则。", false),
            new Entry("banlist-ad", "sr_top500_banlist_ad.conf", "黑名单 + 去广告", "黑名单",
                    "只代理 GFWList 命中的站点，其余（含国内）全部直连，同时拦截广告。", false),
            new Entry("banlist", "sr_top500_banlist.conf", "黑名单", "黑名单",
                    "同上，但不含广告拦截规则。", false),
            new Entry("cnip-ad", "sr_cnip_ad.conf", "国内外划分 + 去广告", "国内外划分",
                    "按 GeoIP 划内外：境外一律代理，国内直连，同时拦截广告。", false),
            new Entry("cnip", "sr_cnip.conf", "国内外划分", "国内外划分",
                    "同上，但不含广告拦截规则。", false),
            new Entry("backcn-ad", "sr_backcn_ad.conf", "回国 + 去广告", "回国",
                    "反过来的逻辑：国内站点走代理、境外直连。给海外用户访问国内服务用。", false),
            new Entry("backcn", "sr_backcn.conf", "回国", "回国",
                    "同上，但不含广告拦截规则。", false),
            new Entry("direct-ad", "sr_direct_banad.conf", "全局直连 + 去广告", "特殊规则",
                    "所有流量直连，只借这份配置的广告拦截能力。", false),
            new Entry("proxy-ad", "sr_proxy_banad.conf", "全局代理 + 去广告", "特殊规则",
                    "所有流量走代理，只有广告域名被拦。", false),
            new Entry("ad-only", "sr_ad_only.conf", "仅去广告", "特殊规则",
                    "只拦广告，其余全部直连。上游没有兜底规则，这里补了 MATCH,DIRECT。", false),
            new Entry("adb", "sr_adb.conf", "综合版", "特殊规则",
                    "上游另一套合并规则，黑白名单混合，行为与白名单版接近。", false),
            new Entry("lazy", "lazy.conf", "懒人配置", "懒人",
                    "39 条粗粒度规则，不依赖任何外部规则集，体积最小。", false),
            new Entry("lazy-group", "lazy_group.conf", "懒人配置（含策略组）", "懒人",
                    "39 条规则 + AI、流媒体、游戏平台、微软谷歌苹果等 17 个独立分流组。", false)
    );

    private static final Map<String, String> GROUP_MAP = new LinkedHashMap<>();

    static {
        GROUP_MAP.put("AI", "AI");
        GROUP_MAP.put("YOUTUBE", "YouTube");
        GROUP_MAP.put("NETFLIX", "Netflix");
        GROUP_MAP.put("DISNEY+", "Disney+");
        GROUP_MAP.put("MAX", "Max");
        GROUP_MAP.put("SPOTIFY", "Spotify");
        GROUP_MAP.put("TELEGRAM", "Telegram");
        GROUP_MAP.put("PAYPAL", "PayPal");
        GROUP_MAP.put("TWITTER", "Twitter");
        GROUP_MAP.put("FACEBOOK", "Facebook");
        GROUP_MAP.put("AMAZON", "Amazon");
        GROUP_MAP.put("游戏平台", "游戏平台");
        GROUP_MAP.put("微软服务", "微软服务");
        GROUP_MAP.put("谷歌服务", "谷歌服务");
        GROUP_MAP.put("苹果服务", "苹果服务");
        GROUP_MAP.put("哔哩哔哩", "哔哩哔哩");
        GROUP_MAP.put("TIKTOK", "TikTok");
    }

    private static final Pattern POLICY_COMMENT = Pattern.compile("\\s*#.*$", Pattern.UNICODE_CHARACTER_CLASS);

    private Variants() {
    }

    /**
     * 去掉策略名后面的行内注释。
     *
     * @param raw 原始策略
     * @return 去注释后的策略
     */
    static String cleanPolicy(String raw) {
        return Utils.strip(POLICY_COMMENT.matcher(raw).replaceFirst(""));
    }

    /**
     * 把上游策略名归一成 Clash 的三种取值之一，或返回自定义组名。
     *
     * @param raw          原始策略
     * @param defaultGroup 默认代理组名
     * @return 归一后的策略
     */
    static String canonPolicy(String raw, String defaultGroup) {
        String policy = cleanPolicy(raw);
        String lower = policy.toLowerCase(Locale.ROOT);
        if (Renderer.REJECT_ALIASES.contains(lower)) {
            return "REJECT";
        }
        if (Renderer.DIRECT_ALIASES.contains(lower)) {
            return "DIRECT";
        }
        if ("proxy".equals(lower) || "proxies".equals(lower)) {
            return defaultGroup;
        }
        String mapped = GROUP_MAP.get(policy.toUpperCase(Locale.ROOT));
        return mapped != null ? mapped : policy;
    }
}
