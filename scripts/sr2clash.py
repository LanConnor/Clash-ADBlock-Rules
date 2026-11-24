#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
sr2clash.py —— 把 Shadowrocket / Surge 风格的 .conf 分流规则转换成 Clash (mihomo) 可用的 YAML

用法:
    # 直接读在线规则（推荐，规则每天更新，脚本自动拉最新）
    python sr2clash.py -u https://johnshall.github.io/Shadowrocket-ADBlock-Rules-Forever/sr_top500_whitelist_ad.conf -o out

    # 读本地文件
    python sr2clash.py -i sr.conf -o out

    # 生成 rule-provider 版本（大量域名规则拆成独立文件，加载更快）
    python sr2clash.py -u <url> -o out --mode provider

    # 自定义代理组名 / 是否内联节点
    python sr2clash.py -i sr.conf -o out --group "🚀 节点选择"

产物:
    out/config.yaml          完整可用配置（规则内联，单文件，手机端最稳）
    out/rules-only.yaml      只有 rules: 列表，粘到自己已有的配置里用
    --mode provider 额外产出:
    out/providers/<策略>-<序号>.yaml   按上游原始顺序切分的连续同策略片段
    out/config-providers.yaml
"""

import argparse
import os
import re
import sys
import urllib.request

# ----------------------------------------------------------------------------
# 策略映射：Shadowrocket 的策略名 -> Clash 策略
# Clash 的策略只有三种：DIRECT / REJECT / 代理组名
# ----------------------------------------------------------------------------
REJECT_ALIASES = {"reject", "reject-drop", "reject-no-drop", "reject-img", "reject-tinygif"}
DIRECT_ALIASES = {"direct", "directly"}


def map_policy(raw, proxy_group):
    p = raw.strip()
    low = p.lower()
    if low in REJECT_ALIASES:
        return "REJECT"
    if low in DIRECT_ALIASES:
        return "DIRECT"
    # Proxy / PROXY / 任何自定义策略（如 "节点选择"）都归到同一个代理组
    return proxy_group


# ----------------------------------------------------------------------------
# 解析 .conf
# ----------------------------------------------------------------------------
def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8", "replace")


def parse_conf(text):
    """返回 (rules, general, build_time, ignored)。

    rules 为 [(类型, 值, 原始策略, 参数列表)]，严格保持上游顺序。
    策略固定取第 3 列，no-resolve 等参数放在后面，避免把参数误当策略。
    """
    rules, general, ignored = [], {}, []
    section = None
    build_time = ""
    for line in text.splitlines():
        s = line.strip().lstrip("\ufeff")
        if not build_time and s.lower().startswith("# build time"):
            build_time = s.split(":", 1)[-1].strip()
        if s.startswith("[") and s.endswith("]"):
            section = s[1:-1].strip().lower()
            continue
        if not s or s.startswith("#") or s.startswith(";"):
            continue
        if section == "general":
            if "=" in s:
                k, v = s.split("=", 1)
                general[k.strip().lower()] = v.strip()
            continue
        if section != "rule":
            continue
        # 去掉行尾注释（例如 "Proxy # Google AMP issue#237"），
        # 要求 # 前有空白，避免把 URL 里的 # 也当成注释
        s = re.sub(r"\s+#.*$", "", s).strip()
        if not s:
            continue
        parts = [x.strip() for x in s.split(",")]
        rtype = parts[0].upper()
        if len(parts) == 2 and rtype in ("FINAL", "MATCH"):
            rules.append(("FINAL", "", parts[1], []))
            continue
        if len(parts) < 3:
            ignored.append(s)
            continue
        rules.append((rtype, parts[1], parts[2], parts[3:]))
    return rules, general, build_time, ignored


# ----------------------------------------------------------------------------
# 规则转换
# ----------------------------------------------------------------------------
def to_clash_rule(rule, proxy_group):
    rtype, value, policy, extra = rule
    pol = map_policy(policy, proxy_group)

    if rtype in ("DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX",
                 "DOMAIN-WILDCARD", "PROCESS-NAME"):
        return "DOMAIN,%s,%s" % (value, pol) if rtype == "DOMAIN" else "%s,%s,%s" % (rtype, value, pol)

    if rtype in ("IP-CIDR", "IP-CIDR6"):
        # Clash 要求 IPv6 用 IP-CIDR6；同时补 no-resolve 避免域名走 IP 规则时白做 DNS
        t = "IP-CIDR6" if (rtype == "IP-CIDR6" or ":" in value) else "IP-CIDR"
        tail = ",".join(extra) if extra else "no-resolve"
        return "%s,%s,%s,%s" % (t, value, pol, tail)

    if rtype == "GEOIP":
        return "GEOIP,%s,%s" % (value, pol)

    if rtype == "FINAL":
        return "MATCH,%s" % pol

    if rtype == "RULE-SET":
        # Shadowrocket 用远程 .list，Clash 需要远程 rule-provider，这里转成 provider 引用
        return "RULE-SET,%s,%s" % (ruleset_name(value), pol)

    return None


def render_payload(rtype, value, extra):
    """渲染 rule-provider payload 里的一条规则（不带策略）。返回 None 表示类型不支持。"""
    if rtype in ("IP-CIDR", "IP-CIDR6"):
        t = "IP-CIDR6" if (rtype == "IP-CIDR6" or ":" in value) else "IP-CIDR"
        tail = ",".join(extra) if extra else "no-resolve"
        return "%s,%s,%s" % (t, value, tail)
    if rtype in ("DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX",
                 "DOMAIN-WILDCARD", "PROCESS-NAME", "GEOIP"):
        return "%s,%s" % (rtype, value)
    return None


def ruleset_name(url):
    """从规则集 URL 里取一个合法的 provider 名"""
    path = url.split("?")[0].rstrip("/")
    seg = path.split("/")[-1]
    seg = re.sub(r"\.(list|yaml|yml|txt|conf)$", "", seg, flags=re.I)
    # 形如 .../Shadowrocket/AppleNews/AppleNews.list -> AppleNews
    seg = re.sub(r"[^0-9A-Za-z_.-]", "_", seg) or "ruleset"
    return seg


def clash_url_for_ruleset(url):
    """blackmatrix7 的规则集有 Clash 版本，自动换路径"""
    m = re.match(r"^(https://raw\.githubusercontent\.com/blackmatrix7/ios_rule_script/[^/]+/rule/)Shadowrocket/([^/]+)/[^/]+$", url)
    if m:
        return "%sClash/%s/%s.yaml" % (m.group(1), m.group(2), m.group(2))
    return url


# ----------------------------------------------------------------------------
# YAML 输出（手工拼接，避免依赖 PyYAML；域名里不会出现需要转义的字符）
# ----------------------------------------------------------------------------
def build_rules(rules, proxy_group, dedupe=True):
    out, seen, providers = [], set(), {}
    stat = {"REJECT": 0, "DIRECT": 0, "PROXY": 0}
    dropped = {}
    for r in rules:
        line = to_clash_rule(r, proxy_group)
        if not line:
            dropped[r[0]] = dropped.get(r[0], 0) + 1
            continue
        if r[0] == "RULE-SET":
            url = r[1]
            providers[ruleset_name(url)] = clash_url_for_ruleset(url)
        if dedupe:
            if line in seen:
                continue
            seen.add(line)
        out.append(line)
        pol = map_policy(r[2], proxy_group)
        stat["REJECT" if pol == "REJECT" else ("DIRECT" if pol == "DIRECT" else "PROXY")] += 1
    return out, providers, stat, dropped


def dedupe_lines(lines):
    seen, out = set(), []
    for line in lines:
        if line and line not in seen:
            seen.add(line)
            out.append(line)
    return out


def chunk_slug(policy):
    if policy == "REJECT":
        return "adblock"
    if policy == "DIRECT":
        return "direct"
    return "proxy"


def plan_provider_chunks(rules, proxy_group):
    """按上游原始顺序规划输出。

    连续且策略相同的域名/IP 规则合并成一个 chunk（落成独立 provider）；
    GEOIP / RULE-SET / FINAL 以及自定义策略规则保持内联位置。
    不能先按策略分桶再拼接，否则 direct / proxy 交错的文件会改变首条匹配结果。
    """
    plan, dropped = [], {}
    for rule in rules:
        rtype, value, pol_raw, extra = rule
        pol = map_policy(pol_raw, proxy_group)
        if rtype == "FINAL":
            plan.append({"kind": "inline", "text": "MATCH,%s" % pol})
        elif rtype == "RULE-SET":
            plan.append({"kind": "ruleset", "url": value, "policy": pol})
        elif rtype != "GEOIP" and pol in ("REJECT", "DIRECT", proxy_group) \
                and render_payload(rtype, value, extra) is not None:
            if plan and plan[-1]["kind"] == "chunk" and plan[-1]["policy"] == pol:
                plan[-1]["entries"].append((rtype, value, extra))
            else:
                plan.append({"kind": "chunk", "policy": pol, "entries": [(rtype, value, extra)]})
        else:
            text = to_clash_rule(rule, proxy_group)
            if text:
                plan.append({"kind": "inline", "text": text})
            else:
                dropped[rtype] = dropped.get(rtype, 0) + 1
    return plan, dropped


DNS_BLOCK_TEMPLATE = """dns:
  enable: true
  ipv6: {ipv6}
  enhanced-mode: fake-ip
  fake-ip-range: 198.18.0.1/16
  fake-ip-filter:
    - '*.lan'
    - '*.local'
    - '+.msftconnecttest.com'
    - '+.msftncsi.com'
    - 'time.*.com'
    - 'ntp.*.com'
  default-nameserver:
    - 223.5.5.5
    - 119.29.29.29
  nameserver:
    - https://dns.alidns.com/dns-query
    - https://doh.pub/dns-query
  proxy-server-nameserver:
    - https://dns.alidns.com/dns-query
  fallback:
    - https://1.1.1.1/dns-query
    - https://dns.google/dns-query
  fallback-filter:
    geoip: true
    geoip-code: CN
"""


def build_dns_block(ipv6=False):
    """生成 DNS 段，ipv6 与上游 [General] 的 ipv6 开关保持一致。"""
    return DNS_BLOCK_TEMPLATE.format(ipv6="true" if ipv6 else "false")


DNS_BLOCK = build_dns_block(False)

PROXIES_BLOCK = """proxies:
  # ↓↓↓ 这里换成你自己的节点（也可以用订阅转换后只保留 rules 段） ↓↓↓
  - name: "占位节点"
    type: ss
    server: 1.2.3.4
    port: 8388
    cipher: aes-256-gcm
    password: "password"
    udp: true
"""


def groups_block(proxy_group, node_names):
    nodes = ", ".join('"%s"' % n for n in node_names)
    return """proxy-groups:
  - name: "%s"
    type: select
    proxies: ["⚡ 自动选择", %s, DIRECT]
  - name: "⚡ 自动选择"
    type: url-test
    url: http://www.gstatic.com/generate_204
    interval: 300
    tolerance: 50
    proxies: [%s]
""" % (proxy_group, nodes, nodes)


def header(src, build_time, stat, proxy_group):
    total = stat["REJECT"] + stat["DIRECT"] + stat["PROXY"]
    return (
        "# ============================================================\n"
        "# 由 sr2clash.py 从 Shadowrocket 规则生成\n"
        "# 源文件: %s\n"
        "# 规则构建时间: %s\n"
        "# 规则条数: %d  (REJECT=%d, DIRECT=%d, 代理=%d)\n"
        "# 代理组名: %s\n"
        "# ============================================================\n"
        % (src, build_time, total, stat["REJECT"], stat["DIRECT"], stat["PROXY"], proxy_group)
    )


def write_rules_list(fh, rules_lines):
    fh.write("rules:\n")
    for line in rules_lines:
        fh.write("  - %s\n" % line)


def main():
    ap = argparse.ArgumentParser(description="Shadowrocket .conf -> Clash YAML")
    ap.add_argument("-i", "--input", help="本地 .conf 文件")
    ap.add_argument("-u", "--url", help="在线 .conf 地址")
    ap.add_argument("-o", "--out", default="clash", help="输出目录")
    ap.add_argument("-g", "--group", default="🚀 代理", help="代理组名")
    ap.add_argument("--mode", choices=["inline", "provider"], default="inline")
    ap.add_argument("--no-dedupe", action="store_true", help="不去重")
    ap.add_argument("--remote-base", default="", metavar="URL",
                    help="provider 模式下用远程 URL 引用规则集，例如 "
                         "https://raw.githubusercontent.com/USER/REPO/dist")
    args = ap.parse_args()

    if args.url:
        print("[*] 下载规则: %s" % args.url)
        text = fetch(args.url)
        src = args.url
    elif args.input:
        with open(args.input, "r", encoding="utf-8-sig", errors="replace") as f:
            text = f.read()
        src = os.path.abspath(args.input)
    else:
        ap.error("必须指定 -i 或 -u")

    rules, general, build_time, _ignored = parse_conf(text)
    if not rules:
        sys.exit("[!] 没解析到任何规则，检查文件是否为 [Rule] 格式的 .conf")

    ipv6 = str(general.get("ipv6", "false")).strip().lower() == "true"

    rules_lines, providers, stat, dropped = build_rules(rules, args.group, dedupe=not args.no_dedupe)
    print("[*] 解析到 %d 条规则，去重后 %d 条 (REJECT=%d DIRECT=%d 代理=%d)"
          % (len(rules), len(rules_lines), stat["REJECT"], stat["DIRECT"], stat["PROXY"]))
    for rtype, count in sorted(dropped.items()):
        print("[!] 跳过 %d 条不支持的规则类型: %s" % (count, rtype))

    os.makedirs(args.out, exist_ok=True)
    hdr = header(src, build_time or "unknown", stat, args.group)

    if args.mode == "inline":
        path = os.path.join(args.out, "config.yaml")
        with open(path, "w", encoding="utf-8") as f:
            f.write(hdr)
            f.write("\nmixed-port: 7890\nallow-lan: false\nmode: rule\nlog-level: info\n")
            f.write("ipv6: %s\ntcp-concurrent: true\nunified-delay: true\n"
                    % ("true" if ipv6 else "false"))
            f.write("external-controller: 127.0.0.1:9090\n\n")
            f.write(build_dns_block(ipv6))
            f.write("\n" + PROXIES_BLOCK + "\n")
            f.write(groups_block(args.group, ["占位节点"]) + "\n")
            if providers:
                f.write("rule-providers:\n")
                for name, url in providers.items():
                    f.write("  %s:\n    type: http\n    behavior: classical\n    url: %s\n"
                            "    path: ./ruleset/%s.yaml\n    interval: 86400\n" % (name, url, name))
                f.write("\n")
            write_rules_list(f, rules_lines)
        print("[+] 已生成 %s (%.1f KB)" % (path, os.path.getsize(path) / 1024))

        path2 = os.path.join(args.out, "rules-only.yaml")
        with open(path2, "w", encoding="utf-8") as f:
            f.write(hdr)
            f.write("# 用法: 把下面整段 rules: 替换/追加到你现有的 Clash 配置末尾，\n")
            f.write("#       并保证配置里存在名为 \"%s\" 的 proxy-group。\n" % args.group)
            f.write("#       如果只是追加，记得保留原来的 MATCH 行在最后一条。\n\n")
            if providers:
                f.write("rule-providers:\n")
                for name, url in providers.items():
                    f.write("  %s:\n    type: http\n    behavior: classical\n    url: %s\n"
                            "    path: ./ruleset/%s.yaml\n    interval: 86400\n" % (name, url, name))
                f.write("\n")
            write_rules_list(f, rules_lines)
        print("[+] 已生成 %s (%.1f KB)" % (path2, os.path.getsize(path2) / 1024))

    else:
        # provider 模式：按上游原始顺序，把连续的同策略规则切成片段，逐段生成 provider。
        # 不能按策略分桶后重新拼接，否则 direct / proxy 交错的文件会改变首条匹配结果。
        pdir = os.path.join(args.out, "providers")
        os.makedirs(pdir, exist_ok=True)
        plan, dropped = plan_provider_chunks(rules, args.group)
        for rtype, count in sorted(dropped.items()):
            print("[!] 跳过 %d 条不支持的规则类型: %s" % (count, rtype))

        provider_defs = []       # [(name, url)]，url 为远程地址或本地产物文件名
        rules_out = []
        used_names = set()
        chunk_counts = {}

        def uniq(name):
            n, i = name, 2
            while n in used_names:
                n = "%s-%d" % (name, i)
                i += 1
            used_names.add(n)
            return n

        for item in plan:
            if item["kind"] == "chunk":
                slug = chunk_slug(item["policy"])
                chunk_counts[slug] = chunk_counts.get(slug, 0) + 1
                name = uniq("%s-%d" % (slug, chunk_counts[slug]))
                fn = name + ".yaml"
                payload = dedupe_lines([render_payload(*e) for e in item["entries"]])
                with open(os.path.join(pdir, fn), "w", encoding="utf-8") as f:
                    f.write("# %s rules, %d entries\npayload:\n" % (name, len(payload)))
                    for line in payload:
                        f.write("  - '%s'\n" % line)
                provider_defs.append((name, fn))
                rules_out.append("RULE-SET,%s,%s" % (name, item["policy"]))
                print("[+] 已生成 %s (%d 条)" % (os.path.join(pdir, fn), len(payload)))
            elif item["kind"] == "ruleset":
                name = uniq(ruleset_name(item["url"]))
                provider_defs.append((name, clash_url_for_ruleset(item["url"])))
                rules_out.append("RULE-SET,%s,%s" % (name, item["policy"]))
            else:
                rules_out.append(item["text"])

        path = os.path.join(args.out, "config-providers.yaml")
        with open(path, "w", encoding="utf-8") as f:
            f.write(hdr)
            f.write("\nmixed-port: 7890\nallow-lan: false\nmode: rule\nlog-level: info\n")
            f.write("ipv6: %s\ntcp-concurrent: true\nunified-delay: true\n"
                    % ("true" if ipv6 else "false"))
            f.write("external-controller: 127.0.0.1:9090\n\n")
            f.write(build_dns_block(ipv6) + "\n" + PROXIES_BLOCK + "\n")
            f.write(groups_block(args.group, ["占位节点"]) + "\n")
            f.write("rule-providers:\n")
            for name, target in provider_defs:
                if not target.startswith("http"):
                    if args.remote_base:
                        target = "%s/providers/%s" % (args.remote_base.rstrip("/"), target)
                    else:
                        f.write("  %s:\n    type: file\n    behavior: classical\n    format: yaml\n"
                                "    path: ./providers/%s\n" % (name, target))
                        continue
                f.write("  %s:\n    type: http\n    behavior: classical\n    format: yaml\n"
                        "    url: %s\n    path: ./ruleset/%s.yaml\n    interval: 86400\n"
                        % (name, target, name))
            f.write("\nrules:\n")
            for line in rules_out:
                f.write("  - %s\n" % line)
        print("[+] 已生成 %s (%.1f KB)" % (path, os.path.getsize(path) / 1024))


if __name__ == "__main__":
    main()
