#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_variants.py —— 把上游 14 个变体一次性转成 Clash 规则包，供客户端自由挑选

产物布局（--out 目录下）：
    common/adblock.yaml        所有「去广告」变体共用的广告规则（各变体集合一致时只存一份）
    <变体id>/<片段名>.yaml      按上游原始顺序切出的连续同策略片段（direct / direct-2 / proxy ...）
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

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sr2clash import build_dns_block, fetch, parse_conf, render_payload  # noqa: E402

# 片段内规则数不超过该值时直接内联到 rules，避免生成过多小文件
INLINE_CHUNK_LIMIT = 60

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
     "39 条规则 + AI、流媒体、游戏平台、微软谷歌苹果等 17 个独立分流组。", False),
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
    if rtype in ("IP-CIDR", "IP-CIDR6"):
        t = "IP-CIDR6" if (rtype == "IP-CIDR6" or ":" in value) else "IP-CIDR"
        tail = ",".join(extra) if extra else "no-resolve"
        if policy:
            # Clash 要求策略在参数之前：IP-CIDR,网段,策略,no-resolve
            return "%s,%s,%s,%s" % (t, value, policy, tail)
        return "%s,%s,%s" % (t, value, tail)
    if rtype in ("DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX",
                 "DOMAIN-WILDCARD", "PROCESS-NAME", "GEOIP"):
        head = "%s,%s" % (rtype, value)
        return head + ("," + policy if policy else "")
    return None


def plan_rules(rules, default_group):
    """按上游原始顺序规划输出。

    连续且策略相同的域名/IP 规则合并成一个 chunk（可落成 rule-provider）；
    GEOIP / RULE-SET / FINAL 以及自定义策略规则保持内联位置。
    这样 Clash 的首条匹配结果才与 Shadowrocket 完全一致，不能先按策略分桶再拼接。
    """
    plan, dropped, customs = [], {}, set()
    for rtype, value, pol_raw, extra in rules:
        pol = canon_policy(pol_raw, default_group)
        if pol not in ("REJECT", "DIRECT", default_group):
            customs.add(pol)
        if rtype == "FINAL":
            plan.append({"kind": "match", "policy": pol})
        elif rtype == "RULE-SET":
            plan.append({"kind": "ruleset", "url": value, "policy": pol})
        elif rtype == "GEOIP":
            plan.append({"kind": "inline", "text": rule_line(rtype, value, extra, pol)})
        elif render_payload(rtype, value, extra) is not None \
                and pol in ("REJECT", "DIRECT", default_group):
            if plan and plan[-1]["kind"] == "chunk" and plan[-1]["policy"] == pol:
                plan[-1]["entries"].append((rtype, value, extra))
            else:
                plan.append({"kind": "chunk", "policy": pol, "entries": [(rtype, value, extra)]})
        else:
            text = rule_line(rtype, value, extra, pol)
            if text:
                plan.append({"kind": "inline", "text": text})
            else:
                dropped[rtype] = dropped.get(rtype, 0) + 1
    return plan, dropped, customs


def dedupe(entries):
    seen, uniq = set(), []
    for e in entries:                 # 去重但保持原顺序
        if e and e not in seen:
            seen.add(e)
            uniq.append(e)
    return uniq


def chunk_payload(entries):
    return dedupe([render_payload(*e) for e in entries])


def chunk_slug(policy):
    if policy == "REJECT":
        return "adblock"
    if policy == "DIRECT":
        return "direct"
    return "proxy"


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
    set_registry = {}          # provider 内容哈希 -> 已写出的 URL，多个变体共用同一份
    fetched_rulesets = set()   # 本次构建已本地化过的规则集文件名
    index = {"variants": [], "common": {}}
    upstream_time = ""
    ad_reference = None        # 首个含广告变体的 REJECT 集合（排序后），用于复用 common/adblock.yaml
    ad_block_entries = None    # 首个含广告变体的广告 payload（保序），用于写 common 文件
    common_ad_users = 0        # 实际引用 common/adblock.yaml 的变体数

    for vid, fname, cname, cat, desc, rec in VARIANTS:
        if wanted and vid not in wanted:
            continue
        path = os.path.join(args.conf_dir, fname)
        if not os.path.exists(path):
            print("[!] 跳过 %s：缺少 %s" % (vid, fname))
            continue
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            text = f.read()
        rules, general, btime, _ignored = parse_conf(text)
        if btime and not upstream_time:
            upstream_time = btime
        if not rules:
            print("[!] 跳过 %s：没解析到规则" % vid)
            continue
        ipv6 = str(general.get("ipv6", "false")).strip().lower() == "true"

        plan, dropped, customs = plan_rules(rules, group)
        for rtype, count in sorted(dropped.items()):
            print("[!] %s：跳过 %d 条不支持的规则类型 %s" % (vid, count, rtype))

        # 统计（与旧版口径保持一致：DIRECT 不含 IP 规则；GEOIP / RULE-SET / FINAL 不计入三类）
        reject = direct = proxy = 0
        for item in plan:
            if item["kind"] != "chunk":
                continue
            if item["policy"] == "REJECT":
                reject += len(item["entries"])
            elif item["policy"] == "DIRECT":
                direct += sum(1 for e in item["entries"] if not e[0].upper().startswith("IP-CIDR"))
            else:
                proxy += len(item["entries"])

        ad_entries_this = dedupe(
            render_payload(*e)
            for item in plan if item["kind"] == "chunk" and item["policy"] == "REJECT"
            for e in item["entries"])
        if ad_entries_this:
            if ad_reference is None:
                ad_reference = sorted(ad_entries_this)
                ad_block_entries = ad_entries_this
            elif sorted(ad_entries_this) != ad_reference:
                print("[i] %s 的广告集合与首个变体不同，将使用该变体自己的 provider" % vid)

        vdir = os.path.join(args.out, vid)
        os.makedirs(vdir, exist_ok=True)
        providers, rules_out = [], []
        used_names, url_names = set(), {}

        def uniq(name):
            n, i = name, 2
            while n in used_names:
                n = "%s-%d" % (name, i)
                i += 1
            used_names.add(n)
            return n

        def register_provider(name, url):
            if url in url_names:
                return url_names[url]
            name = uniq(name)
            providers.append((name, url))
            url_names[url] = name
            return name

        def add_chunk(policy, payload, title):
            key = hashlib.sha256("\n".join(payload).encode()).hexdigest()
            if key in set_registry:                 # 内容一致，复用别的变体已写出的文件
                return register_provider(chunk_slug(policy), set_registry[key])
            name = uniq(chunk_slug(policy))
            fn = name + ".yaml"
            write_set(os.path.join(vdir, fn), payload, title)
            url = "%s/%s/%s" % (base, vid, fn)
            set_registry[key] = url
            providers.append((name, url))
            url_names[url] = name
            return name

        def use_common_ad():
            url = "%s/common/adblock.yaml" % base
            if url in url_names:
                return url_names[url]
            name = uniq("sr-adblock")
            providers.append((name, url))
            url_names[url] = name
            return name

        for item in plan:
            if item["kind"] == "chunk":
                policy = item["policy"]
                payload = chunk_payload(item["entries"])
                if policy == "REJECT" and ad_reference is not None \
                        and sorted(payload) == ad_reference:
                    name = use_common_ad()
                    rules_out.append("RULE-SET,%s,REJECT" % name)
                elif len(item["entries"]) <= INLINE_CHUNK_LIMIT:
                    for e in item["entries"]:
                        line = rule_line(*e, policy)
                        if line:
                            rules_out.append(line)
                else:
                    name = add_chunk(policy, payload, "%s %s 规则" % (cname, chunk_slug(policy)))
                    rules_out.append("RULE-SET,%s,%s" % (name, policy))
            elif item["kind"] == "match":
                rules_out.append("MATCH,%s" % item["policy"])
            elif item["kind"] == "ruleset":
                url = item["url"]
                curl = clash_ruleset_url(url)
                slug = ruleset_slug(url, len(providers))
                if curl:
                    name = register_provider("rs-" + slug, curl)
                    rules_out.append("RULE-SET,%s,%s" % (name, item["policy"]))
                    continue
                # 非 mihomo 格式的清单，构建时抓下来本地化
                local_dir = os.path.join(args.out, "rulesets")
                os.makedirs(local_dir, exist_ok=True)
                fn = "rs-%s.yaml" % slug
                fp = os.path.join(local_dir, fn)
                if fn not in fetched_rulesets:
                    try:
                        body = fetch(url)
                        items, ldropped = [], {}
                        for line in body.splitlines():
                            s = line.strip()
                            if not s or s.startswith("#"):
                                continue
                            parts = [x.strip() for x in s.split(",")]
                            if len(parts) < 2:
                                continue
                            extra = [x for x in parts[2:] if x in ("no-resolve", "src")]
                            ln = render_payload(parts[0].upper(), parts[1], extra)
                            if ln:
                                items.append(ln)
                            else:
                                t = parts[0].upper()
                                ldropped[t] = ldropped.get(t, 0) + 1
                        write_set(fp, items, "%s（本地化）" % slug)
                        fetched_rulesets.add(fn)
                        print("    · 本地化规则集 %s <- %s（%d 条）" % (fn, url, len(items)))
                        for t, n in sorted(ldropped.items()):
                            print("    ! 跳过 %d 条不支持的规则类型: %s" % (n, t))
                    except Exception as e:          # noqa: BLE001
                        print("    ! 抓取失败 %s: %s" % (url, e))
                        continue
                name = register_provider("rs-" + slug, "%s/rulesets/%s" % (base, fn))
                rules_out.append("RULE-SET,%s,%s" % (name, item["policy"]))
            else:
                rules_out.append(item["text"])

        if not any(r.startswith("MATCH,") for r in rules_out):
            rules_out.append("MATCH,DIRECT")        # 上游 ad_only 没有兜底规则

        common_url = "%s/common/adblock.yaml" % base
        if any(url == common_url for _name, url in providers):
            common_ad_users += 1

        custom_groups = sorted(customs)
        other = sum(1 for r in rules_out if not r.startswith("RULE-SET,"))
        cfg = []
        cfg.append("# ============================================================")
        cfg.append("# %s（变体 id: %s）" % (cname, vid))
        cfg.append("# %s" % desc)
        cfg.append("# 上游: %s   构建时间: %s" % (fname, upstream_time or "unknown"))
        cfg.append("# 规则: REJECT %d / DIRECT %d / 代理 %d / 其他 %d"
                   % (reject, direct, proxy, other))
        cfg.append("# ============================================================")
        cfg.append("")
        cfg.append("mixed-port: 7890")
        cfg.append("allow-lan: false")
        cfg.append("mode: rule")
        cfg.append("log-level: info")
        cfg.append("ipv6: %s" % ("true" if ipv6 else "false"))
        cfg.append("tcp-concurrent: true")
        cfg.append("unified-delay: true")
        cfg.append("external-controller: 127.0.0.1:9090")
        cfg.append("")
        cfg.append(build_dns_block(ipv6))
        cfg.append(PLACEHOLDER_PROXY)
        cfg.append(build_groups(group, custom_groups))
        cfg.append("rule-providers:")
        for name, url in providers:
            cfg.append("  %s:" % name)
            cfg.append("    type: http")
            cfg.append("    behavior: classical")
            cfg.append("    format: yaml")
            cfg.append("    url: %s" % url)
            # provider 缓存路径带上变体 id，避免切换变体时复用同名旧缓存
            cfg.append("    path: ./ruleset/%s-%s.yaml" % (vid, name))
            cfg.append("    interval: 86400")
        cfg.append("")
        cfg.append("rules:")
        for r in rules_out:
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
            "stats": {"reject": reject, "direct": direct, "proxy": proxy,
                      "custom_groups": len(custom_groups), "total_rules": len(rules_out)},
            "config_url": "%s/%s/config.yaml" % (base, vid),
            "providers": {n: u for n, u in providers},
        })
        print("[%s] %-14s REJECT %-6d DIRECT %-5d PROXY %-5d 自定义组 %d -> %d 条规则"
              % ("★" if rec else " ", vid, reject, direct, proxy, len(custom_groups), len(rules_out)))

    # 共享广告规则
    if ad_block_entries:
        write_set(os.path.join(args.out, "common", "adblock.yaml"), ad_block_entries,
                  "广告拦截规则（各含广告变体共用）")
        index["common"]["adblock"] = {
            "path": "common/adblock.yaml",
            "entries": len(dedupe(ad_block_entries)),
            "url": "%s/common/adblock.yaml" % base,
            "bytes": os.path.getsize(os.path.join(args.out, "common", "adblock.yaml")),
        }
        print("[common] adblock %d 条（%d 个变体共用这一份）"
              % (len(dedupe(ad_block_entries)), common_ad_users))

    # 根目录再放一份默认配置（白名单+去广告），只想接一个地址的人直接用
    default_cfg = os.path.join(args.out, "whitelist-ad", "config.yaml")
    if os.path.exists(default_cfg):
        shutil.copyfile(default_cfg, os.path.join(args.out, "config.yaml"))
        print("[+] 根目录 config.yaml = whitelist-ad（默认推荐）")

    index["upstream_build_time"] = upstream_time
    with open(os.path.join(args.out, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=2)
    print("[+] index.json 已生成（%d 个变体）" % len(index["variants"]))


if __name__ == "__main__":
    main()
