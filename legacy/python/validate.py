#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对构建产物做结构和语法体检，有问题就以非零退出码失败。

检查项：
  1. 每个 YAML 能解析
  2. 规则类型都在 Clash 支持的范围内
  3. rule-provider 的 payload 不带策略名，IP-CIDR 参数顺序正确
  4. 配置里引用的 provider 都已声明、策略名都存在于 proxy-groups
  5. 每份配置都有 MATCH 兜底
  6. 同一配置内 provider 的缓存 path 不重复
  7. 传入上游 conf 目录时，逐变体校验「首条匹配」结果与上游一致

用法：
    python validate.py build [confs]
"""
import glob
import os
import sys

import yaml

ALLOWED = {"DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX", "DOMAIN-WILDCARD",
           "GEOIP", "IP-CIDR", "IP-CIDR6", "SRC-IP-CIDR", "DST-PORT", "SRC-PORT",
           "PROCESS-NAME", "RULE-SET", "MATCH", "GEOSITE"}


def policy_class(raw):
    """把策略名归一到 REJECT / DIRECT / PROXY 三类，用于顺序等价校验。"""
    key = raw.strip().lower()
    if key in ("reject", "reject-drop", "reject-no-drop", "reject-img", "reject-tinygif"):
        return "REJECT"
    if key in ("direct", "directly"):
        return "DIRECT"
    return "PROXY"


def rule_key(rtype, value):
    """归一规则键：FINAL/MATCH 等价，IPv6 的 IP-CIDR 统一成 IP-CIDR6。"""
    rtype = rtype.upper()
    if rtype in ("FINAL", "MATCH"):
        return ("MATCH", "")
    if rtype.startswith("IP-CIDR"):
        rtype = "IP-CIDR6" if ":" in value else "IP-CIDR"
    return (rtype, value)


def first_policies_upstream(conf_path):
    """解析上游 .conf，按顺序返回 [(规则键, 策略类别)]；外部 RULE-SET 不展开。"""
    from sr2clash import parse_conf
    with open(conf_path, encoding="utf-8", errors="replace") as f:
        rules, _general, _btime, _ignored = parse_conf(f.read())
    seq = []
    for rtype, value, pol_raw, _extra in rules:
        if rtype == "RULE-SET":
            continue
        seq.append((rule_key(rtype, value), policy_class(pol_raw)))
    return seq


def local_provider_file(build, url):
    """把产物里的 provider URL 映射回 build 目录下的本地文件；外部 URL 返回 None。"""
    path = url.split("?")[0].rstrip("/")
    pieces = [p for p in path.split("/") if p]
    if len(pieces) < 2:
        return None
    cand = os.path.join(build, pieces[-2], pieces[-1])
    return cand if os.path.exists(cand) else None


def expand_config(config_path, build):
    """按 rules 顺序展开本地 rule-provider，返回 [(规则键, 策略类别)]。"""
    with open(config_path, encoding="utf-8") as f:
        d = yaml.safe_load(f) or {}
    payloads = {}
    for name, p in (d.get("rule-providers") or {}).items():
        local = local_provider_file(build, str((p or {}).get("url", "")))
        if not local:
            continue
        with open(local, encoding="utf-8") as f:
            pd = yaml.safe_load(f) or {}
        payloads[name] = pd.get("payload") or []
    seq = []
    for r in d.get("rules") or []:
        a = r.split(",")
        t = a[0].upper()
        if t == "RULE-SET":
            name, pol_raw = a[1], a[2]
            if name not in payloads:
                continue                       # 外部规则集不展开
            pol = policy_class(pol_raw)
            for item in payloads[name]:
                b = item.split(",")
                seq.append((rule_key(b[0], b[1]), pol))
        elif t == "MATCH":
            seq.append((("MATCH", ""), policy_class(a[1])))
        elif t.startswith("IP-CIDR"):
            seq.append((rule_key(t, a[1]), policy_class(a[2])))
        else:
            seq.append((rule_key(t, a[1]), policy_class(a[-1])))
    return seq


def first_map(seq):
    m = {}
    for key, pol in seq:
        if key not in m:
            m[key] = pol
    return m


def check_order(build, conf_dir, errs):
    """逐变体比较「每个规则键首次出现时的策略类别」，等价于首条匹配语义。"""
    from build_variants import VARIANTS
    checked = 0
    for vid, fname, _cname, _cat, _desc, _rec in VARIANTS:
        cfg = os.path.join(build, vid, "config.yaml")
        conf = os.path.join(conf_dir, fname)
        if not (os.path.exists(cfg) and os.path.exists(conf)):
            continue
        up = first_map(first_policies_upstream(conf))
        gen = first_map(expand_config(cfg, build))
        bad = [(key, up_pol, gen.get(key)) for key, up_pol in up.items()
               if gen.get(key) != up_pol]
        if bad:
            sample = "; ".join("%s,%s 上游=%s 生成=%s"
                               % (k[0], k[1], a, b if b else "缺失")
                               for k, a, b in bad[:8])
            errs.append("%s 首条匹配与上游不一致 %d 处，例如: %s" % (vid, len(bad), sample))
        checked += 1
    return checked


def main():
    build = sys.argv[1] if len(sys.argv) > 1 else "build"
    conf_dir = sys.argv[2] if len(sys.argv) > 2 else ""
    errs, n_cfg, n_payload, n_entries = [], 0, 0, 0

    for path in sorted(glob.glob(os.path.join(build, "**", "*.yaml"), recursive=True)):
        rel = os.path.relpath(path, build)
        try:
            with open(path, encoding="utf-8") as f:
                d = yaml.safe_load(f)
        except Exception as e:                      # noqa: BLE001
            errs.append("%s YAML 解析失败: %s" % (rel, e))
            continue
        if not isinstance(d, dict):
            errs.append("%s 顶层不是字典" % rel)
            continue

        if "payload" in d:
            n_payload += 1
            items = d.get("payload") or []
            n_entries += len(items)
            for e in items:
                a = e.split(",")
                if a[0] not in ALLOWED:
                    errs.append("%s 未知规则类型: %s" % (rel, e))
                    break
                if a[0].startswith("IP-CIDR"):
                    if len(a) > 3 or (len(a) == 3 and a[2] != "no-resolve"):
                        errs.append("%s IP 规则参数异常: %s" % (rel, e))
                        break
                elif len(a) > 2:
                    errs.append("%s payload 里不应带策略: %s" % (rel, e))
                    break
            continue

        n_cfg += 1
        groups = {g["name"] for g in (d.get("proxy-groups") or [])}
        providers = set(d.get("rule-providers") or {})
        rules = d.get("rules") or []
        if not rules:
            errs.append("%s 没有 rules" % rel)
            continue
        paths = {}
        for name, p in (d.get("rule-providers") or {}).items():
            cache_path = str((p or {}).get("path", ""))
            if cache_path in paths:
                errs.append("%s provider 缓存 path 重复: %s (%s / %s)"
                            % (rel, cache_path, paths[cache_path], name))
            paths[cache_path] = name
        for r in rules:
            a = r.split(",")
            if a[0] not in ALLOWED:
                errs.append("%s 未知规则类型: %s" % (rel, r))
                continue
            if a[0] == "RULE-SET":
                if a[1] not in providers:
                    errs.append("%s 引用了未声明的 provider: %s" % (rel, a[1]))
                pol = a[2]
            elif a[0] == "MATCH":
                pol = a[1]
            elif a[0].startswith("IP-CIDR"):
                pol = a[2] if len(a) > 2 else ""
                for p in a[3:]:
                    if p not in ("no-resolve", "src"):
                        errs.append("%s IP 规则参数异常: %s" % (rel, r))
            else:
                pol = a[-1]
            if pol not in groups and pol not in ("DIRECT", "REJECT"):
                errs.append("%s 策略不存在: %s -> %s" % (rel, pol, r))
        if not any(r.startswith("MATCH,") for r in rules):
            errs.append("%s 缺少 MATCH 兜底" % rel)

    checked = check_order(build, conf_dir, errs) if conf_dir else 0
    print("配置 %d 份 / 规则集 %d 份（%d 条）/ 顺序校验 %d 个变体 / 错误 %d 个"
          % (n_cfg, n_payload, n_entries, checked, len(errs)))
    for e in errs[:20]:
        print("  [x]", e)
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
