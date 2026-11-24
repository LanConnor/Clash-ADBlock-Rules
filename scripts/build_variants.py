#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_variants.py —— 把上游 14 个变体一次性转成 Clash 规则包，供客户端自由挑选

产物布局（--out 目录下）：
    common/adblock.yaml        7 个「去广告」变体共用的广告规则（只存一份）
    <变体id>/direct.yaml       该变体直连集合
    <变体id>/proxy.yaml        该变体代理集合
    <变体id>/config.yaml       该变体的完整配置骨架
    rulesets/*.yaml            需要本地化的外部规则集（如 AI 列表）
    index.json                 变体清单：名称、分类、统计、订阅地址
    meta.json                  构建信息

用法：
    python build_variants.py --conf-dir confs --out dist \
        --remote-base https://raw.githubusercontent.com/USER/REPO/dist
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sr2clash import parse_conf, fetch, DNS_BLOCK  # noqa: E402

# ---------------------------------------------------------------------------
# 变体清单：id / 上游文件名 / 中文名 / 分类 / 一句话说明
# ---------------------------------------------------------------------------
VARIANTS = [
    ("whitelist-ad", "sr_top500_whitelist_ad.conf", "白名单 + 去广告", "白名单",
     "top500 里可直连的走直连，其余境外站点走代理，同时拦截广告。日常最通用的选择。", True),
    ("whitelist", "sr_top500_whitelist.conf", "白名单", "白名单",
     "同上，但不含广告拦截规则。", False),
    ("banlist-ad", "sr_top500_banlist_ad.conf", "黑名单 + 去广告", "黑名单",
     "只代理 GFWList 命中的站点，其余（含国内）全部直连，同时拦截广告。", False),
    ("banlist", "sr_top500_banlist.conf", "黑名单", "黑名单",
     "同上，但不含广告拦截规则。", False),
    ("cnip-ad", "sr_cnip_ad.conf", "国内外划分 + 去广告", "国内外划分",
     "按 GeoIP 划内外：境外一律代理，国内直连，同时拦截广告。", False),
    ("cnip", "sr_cnip.conf", "国内外划分", "国内外划分",
     "同上，但不含广告拦截规则。", False),
    ("backcn-ad", "sr_backcn_ad.conf", "回国 + 去广告", "回国",
     "反过来的逻辑：国内站点走代理、境外直连。给海外用户访问国内服务用。", False),
    ("backcn", "sr_backcn.conf", "回国", "回国",
     "同上，但不含广告拦截规则。", False),
    ("direct-ad", "sr_direct_banad.conf", "全局直连 + 去广告", "特殊规则",
     "所有流量直连，只借这份配置的广告拦截能力。", False),
    ("proxy-ad", "sr_proxy_banad.conf", "全局代理 + 去广告", "特殊规则",
     "所有流量走代理，只有广告域名被拦。", False),
    ("ad-only", "sr_ad_only.conf", "仅去广告", "特殊规则",
     "只拦广告，其余全部直连。上游没有兜底规则，这里补了 MATCH,DIRECT。", False),
    ("adb", "sr_adb.conf", "综合版", "特殊规则",
     "上游另一套合并规则，黑白名单混合，行为与白名单版接近。", False),
    ("lazy", "lazy.conf", "懒人配置", "懒人",
     "39 条粗粒度规则，不依赖任何外部规则集，体积最小。", False),
    ("lazy-group", "lazy_group.conf", "懒人配置（含策略组）", "懒人",
     "39 条规则 + AI、流媒体、游戏平台、微软谷歌苹果等 18 个独立分流组。", False),
]

# 自定义策略名 -> (显示名, 文件名 slug)
GROUP_MAP = {
    "AI": ("AI", "ai"),
    "YOUTUBE": ("YouTube", "youtube"),
    "NETFLIX": ("Netflix", "netflix"),
    "DISNEY+": ("Disney+", "disney"),
    "MAX": ("Max", "max"),
    "SPOTIFY": ("Spotify", "spotify"),
    "TELEGRAM": ("Telegram", "telegram"),
    "PAYPAL": ("PayPal", "paypal"),
    "TWITTER": ("Twitter", "twitter"),
    "FACEBOOK": ("Facebook", "facebook"),
    "AMAZON": ("Amazon", "amazon"),
    "游戏平台": ("游戏平台", "game"),
    "微软服务": ("微软服务", "microsoft"),
    "谷歌服务": ("谷歌服务", "google"),
    "苹果服务": ("苹果服务", "apple"),
    "哔哩哔哩": ("哔哩哔哩", "bilibili"),
    "TIKTOK": ("TikTok", "tiktok"),
}

REJECT_ALIASES = {"reject", "reject-drop", "reject-no-drop", "reject-img", "reject-tinygif"}
DIRECT_ALIASES = {"direct", "directly"}

PLACEHOLDER_PROXY = '''proxies:
  # ↓↓↓ 换成你自己的节点，或用 proxy-providers 拉你的机场订阅 ↓↓↓
  - name: "占位节点"
    type: ss
    server: 1.2.3.4
    port: 8388
    cipher: aes-256-gcm
    password: "password"
    udp: true
'''


def clean_policy(raw):
    """去掉策略名后面的行内注释，例如 'PROXY # Google AMP issue#237'"""
    return re.sub(r"\s*#.*$", "", raw).strip()


def canon_policy(raw, default_group):
    """把上游策略名归一成 Clash 的三种取值之一，或返回自定义组名"""
    p = clean_policy(raw)
    low = p.lower()
    if low in REJECT_ALIASES:
        return "REJECT"
    if low in DIRECT_ALIASES:
        return "DIRECT"
    if low in ("proxy", "proxies"):
        return default_group
    hit = GROUP_MAP.get(p.upper())
    return hit[0] if hit else p


def clash_ruleset_url(url):
    """blackmatrix7 的 Shadowrocket / QuantumultX 规则集换成同仓库的 Clash 版本"""
    m = re.match(
        r"(https://raw\.githubusercontent\.com/blackmatrix7/ios_rule_script)/([^/]+)"
        r"/rule/(?:Shadowrocket|QuantumultX)/([^/]+)/[^/]+$", url)
    if m:
        return "%s/%s/rule/Clash/%s/%s.yaml" % (m.group(1), m.group(2), m.group(3), m.group(3))
    return None


def ruleset_slug(url, idx):
    name = url.split("?")[0].rstrip("/").split("/")[-1]
    name = re.sub(r"\.(list|txt|yaml|yml)$", "", name, flags=re.I)
    name = re.sub(r"[^0-9A-Za-z_-]", "", name)
    return (name or "set%d" % idx).lower()


def rule_line(rtype, value, extra, policy=None):
    """拼一条 Clash 规则；policy 为 None 时用于 rule-provider 的 payload（不带策略）"""
    if rtype == "FINAL":
        return "MATCH,%s" % policy if policy else None
    if rtype == "IP-CIDR":
        t = "IP-CIDR6" if ":" in value else "IP-CIDR"
        tail = ",".join(extra) if extra else "no-resolve"
        if policy:
            # Clash 要求策略在参数之前：IP-CIDR,网段,策略,no-resolve
            return "%s,%s,%s,%s" % (t, value, policy, tail)
        return "%s,%s,%s" % (t, value, tail)
    if rtype in ("DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX", "PROCESS-NAME", "GEOIP"):
        head = "%s,%s" % (rtype, value)
        return head + ("," + policy if policy else "")
    return None


def split_rules(rules, default_group):
    """把规则分成：广告拦截 / 直连 / 代理 / 兜底段（GEOIP、RULE-SET、内网 IP、FINAL）"""
    ad, direct, proxy = [], [], []
    tail = []            # [(type, value, policy, extra)]
    customs = set()
    for rtype, value, pol_raw, extra in rules:
        pol = canon_policy(pol_raw, default_group)
        if pol == "REJECT":
            ad.append((rtype, value, extra))
        elif rtype in ("GEOIP", "RULE-SET", "FINAL"):
            tail.append((rtype, value, pol, extra))
            if rtype == "RULE-SET" and pol not in ("REJECT", "DIRECT", default_group):
                customs.add(pol)
        elif rtype.startswith("IP-CIDR") and pol == "DIRECT":
            tail.append((rtype, value, pol, extra))      # 内网网段留到最后
        elif pol == "DIRECT":
            direct.append((rtype, value, extra))
        elif pol == default_group:
            proxy.append((rtype, value, extra))
        else:
            customs.add(pol)
            tail.append((rtype, value, pol, extra))      # 极少数自定义策略直接内联
    return ad, direct, proxy, tail, customs


def dedupe(entries):
    seen, uniq = set(), []
    for e in entries:                 # 去重但保持原顺序
        if e and e not in seen:
            seen.add(e)
            uniq.append(e)
    return uniq


def write_set(path, entries, title):
    entries = dedupe(entries)
    with open(path, "w", encoding="utf-8") as f:
        f.write("# %s\n# %d 条规则，供 Clash rule-provider 使用（behavior: classical）\n" % (title, len(entries)))
        f.write("payload:\n")
        for e in entries:
            f.write("  - '%s'\n" % e)


def build_groups(default_group, custom_names):
    out = ['proxy-groups:']
    out.append('  - name: "%s"' % default_group)
    out.append('    type: select')
    out.append('    proxies: ["⚡ 自动选择", "占位节点", DIRECT]')
    out.append('  - name: "⚡ 自动选择"')
    out.append('    type: url-test')
    out.append('    url: http://www.gstatic.com/generate_204')
    out.append('    interval: 300')
    out.append('    tolerance: 50')
    out.append('    proxies: ["占位节点"]')
    for name in custom_names:
        out.append('  - name: "%s"' % name)
        out.append('    type: select')
        out.append('    proxies: ["%s", "⚡ 自动选择", DIRECT]' % default_group)
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--conf-dir", required=True, help="放 14 个 .conf 的目录")
    ap.add_argument("--out", required=True)
    ap.add_argument("--group", default="🚀 代理")
    ap.add_argument("--remote-base", required=True, help="产物对外访问前缀，例如 .../dist")
    ap.add_argument("--variant", action="append", help="只构建指定 id（可重复）")
    args = ap.parse_args()

    base = args.remote_base.rstrip("/")
    group = args.group
    os.makedirs(args.out, exist_ok=True)
    os.makedirs(os.path.join(args.out, "common"), exist_ok=True)

    wanted = set(args.variant) if args.variant else None
    ad_block, ad_hash = None, None
    set_registry = {}        # 内容哈希 -> 已写出的 URL，多个变体共用同一份
    index = {"variants": [], "common": {}}
    upstream_time = ""

    for vid, fname, cname, cat, desc, rec in VARIANTS:
        if wanted and vid not in wanted:
            continue
        path = os.path.join(args.conf_dir, fname)
        if not os.path.exists(path):
            print("[!] 跳过 %s：缺少 %s" % (vid, fname))
            continue
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            text = f.read()
        rules, _general, btime, _ = parse_conf(text)
        if btime and not upstream_time:
            upstream_time = btime
        if not rules:
            print("[!] 跳过 %s：没解析到规则" % vid)
            continue

        ad, direct, proxy, tail, customs = split_rules(rules, group)

        # 广告规则在所有变体间完全一致，只存一份
        if ad:
            h = hashlib.sha256(repr(sorted(ad)).encode()).hexdigest()[:16]
            if ad_block is None:
                ad_block, ad_hash = ad, h
            elif h != ad_hash:
                print("[!] %s 的广告规则与首个变体不一致（%s != %s），将单独成文件" % (vid, h[:8], ad_hash[:8]))

        vdir = os.path.join(args.out, vid)
        os.makedirs(vdir, exist_ok=True)
        has_ad = bool(ad)
        # 规则很少的变体直接内联，不折腾 provider
        inline_rest = (len(direct) + len(proxy)) <= 60

        providers, rules_out = [], []

        used_names = set()

        def uniq(name):
            n, i = name, 2
            while n in used_names:
                n = "%s-%d" % (name, i)
                i += 1
            used_names.add(n)
            return n

        def add_provider(name, entries, title):
            name = uniq(name)
            entries = dedupe(entries)
            key = hashlib.sha256("\n".join(entries).encode()).hexdigest()
            if key in set_registry:
                providers.append((name, set_registry[key]))   # 内容一致，复用已有文件
                return
            write_set(os.path.join(vdir, name + ".yaml"), entries, title)
            url = "%s/%s/%s.yaml" % (base, vid, name)
            set_registry[key] = url
            providers.append((name, url))

        if has_ad:
            providers.append(("sr-adblock", "%s/common/adblock.yaml" % base))
        if not inline_rest and direct:
            add_provider("direct", [rule_line(*e) for e in direct], "%s 直连集合" % cname)
        if not inline_rest and proxy:
            add_provider("proxy", [rule_line(*e) for e in proxy], "%s 代理集合" % cname)

        # 兜底段：GEOIP / 内网 IP / RULE-SET / MATCH，保持上游先后顺序
        seen_rs = set()
        for rtype, value, pol, extra in tail:
            if rtype == "RULE-SET":
                curl = clash_ruleset_url(value)
                slug = ruleset_slug(value, len(providers))
                if curl:
                    providers.append((uniq("rs-" + slug), curl))
                else:
                    # 非 mihomo 格式的清单，构建时抓下来本地化
                    local = os.path.join(args.out, "rulesets")
                    os.makedirs(local, exist_ok=True)
                    fn = "rs-%s.yaml" % slug
                    fp = os.path.join(local, fn)
                    if not os.path.exists(fp):
                        try:
                            body = fetch(value)
                            items = []
                            for line in body.splitlines():
                                s = line.strip()
                                if not s or s.startswith("#"):
                                    continue
                                parts = [x.strip() for x in s.split(",")]
                                ln = rule_line(parts[0].upper(), parts[1], parts[2:]) if len(parts) >= 2 else None
                                if ln:
                                    items.append(ln)
                            write_set(fp, items, "%s（本地化）" % slug)
                            print("    · 本地化规则集 %s <- %s（%d 条）" % (fn, value, len(items)))
                        except Exception as e:      # noqa: BLE001
                            print("    ! 抓取失败 %s: %s" % (value, e))
                            continue
                    providers.append((uniq("rs-" + slug), "%s/rulesets/%s" % (base, fn)))
                rules_out.append("RULE-SET,%s,%s" % (providers[-1][0], pol))
            elif rtype == "FINAL":
                rules_out.append("MATCH,%s" % pol)
            else:
                rules_out.append(rule_line(rtype, value, extra, pol))

        # 组装 rules
        rules_final = []
        if has_ad:
            rules_final.append("RULE-SET,sr-adblock,REJECT")
        if not inline_rest:
            if direct:
                rules_final.append("RULE-SET,direct,DIRECT")
            if proxy:
                rules_final.append("RULE-SET,proxy,%s" % group)
        else:
            rules_final += [rule_line(*e, "DIRECT") for e in direct]
            rules_final += [rule_line(*e, group) for e in proxy]
        rules_final += rules_out
        if not any(r.startswith("MATCH,") for r in rules_final):
            rules_final.append("MATCH,DIRECT")      # 上游 ad_only 没有兜底规则

        custom_groups = sorted(customs)
        cfg = []
        cfg.append("# ============================================================")
        cfg.append("# %s（变体 id: %s）" % (cname, vid))
        cfg.append("# %s" % desc)
        cfg.append("# 上游: %s   构建时间: %s" % (fname, upstream_time or "unknown"))
        cfg.append("# 规则: REJECT %d / DIRECT %d / 代理 %d / 其他 %d"
                   % (len(ad), len(direct), len(proxy), len(tail)))
        cfg.append("# ============================================================")
        cfg.append("")
        cfg.append("mixed-port: 7890")
        cfg.append("allow-lan: false")
        cfg.append("mode: rule")
        cfg.append("log-level: info")
        cfg.append("ipv6: false")
        cfg.append("tcp-concurrent: true")
        cfg.append("unified-delay: true")
        cfg.append("external-controller: 127.0.0.1:9090")
        cfg.append("")
        cfg.append(DNS_BLOCK)
        cfg.append(PLACEHOLDER_PROXY)
        cfg.append(build_groups(group, custom_groups))
        cfg.append("rule-providers:")
        for name, url in providers:
            cfg.append("  %s:" % name)
            cfg.append("    type: http")
            cfg.append("    behavior: classical")
            cfg.append("    format: yaml")
            cfg.append("    url: %s" % url)
            cfg.append("    path: ./ruleset/%s.yaml" % name)
            cfg.append("    interval: 86400")
        cfg.append("")
        cfg.append("rules:")
        for r in rules_final:
            cfg.append("  - %s" % r)

        with open(os.path.join(vdir, "config.yaml"), "w", encoding="utf-8") as f:
            f.write("\n".join(cfg) + "\n")

        index["variants"].append({
            "id": vid,
            "name": cname,
            "category": cat,
            "description": desc,
            "recommended": rec,
            "upstream_file": fname,
            "stats": {"reject": len(ad), "direct": len(direct), "proxy": len(proxy),
                      "custom_groups": len(custom_groups), "total_rules": len(rules_final)},
            "config_url": "%s/%s/config.yaml" % (base, vid),
            "providers": {n: u for n, u in providers},
        })
        print("[%s] %-14s REJECT %-6d DIRECT %-5d PROXY %-5d 自定义组 %d -> %d 条规则"
              % ("★" if rec else " ", vid, len(ad), len(direct), len(proxy), len(custom_groups), len(rules_final)))

    # 共享广告规则
    if ad_block:
        entries = dedupe([rule_line(*e) for e in ad_block])
        write_set(os.path.join(args.out, "common", "adblock.yaml"), entries,
                  "广告拦截规则（%d 个变体共用）" % sum(1 for v in index["variants"] if v["stats"]["reject"]))
        index["common"]["adblock"] = {
            "path": "common/adblock.yaml",
            "entries": len(entries),
            "url": "%s/common/adblock.yaml" % base,
            "bytes": os.path.getsize(os.path.join(args.out, "common", "adblock.yaml")),
        }
        print("[common] adblock %d 条（%d 个变体共用这一份）"
              % (len(entries), sum(1 for v in index["variants"] if v["stats"]["reject"])))

    # 根目录再放一份默认配置（白名单+去广告），只想接一个地址的人直接用
    default_cfg = os.path.join(args.out, "whitelist-ad", "config.yaml")
    if os.path.exists(default_cfg):
        shutil.copyfile(default_cfg, os.path.join(args.out, "config.yaml"))
        print("[+] 根目录 config.yaml = whitelist-ad（默认推荐）")

    index["upstream_build_time"] = upstream_time
    index["generated_at"] = __import__("datetime").datetime.now(
        __import__("datetime").timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    with open(os.path.join(args.out, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=2)
    print("[+] index.json 已生成（%d 个变体）" % len(index["variants"]))


if __name__ == "__main__":
    main()
