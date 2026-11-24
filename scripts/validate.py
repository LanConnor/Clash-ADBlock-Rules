#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对构建产物做结构和语法体检，有问题就以非零退出码失败。

检查项：
  1. 每个 YAML 能解析
  2. 规则类型都在 Clash 支持的范围内
  3. rule-provider 的 payload 不带策略名，IP-CIDR 参数顺序正确
  4. 配置里引用的 provider 都已声明、策略名都存在于 proxy-groups
  5. 每份配置都有 MATCH 兜底
"""
import glob
import os
import sys

import yaml

ALLOWED = {"DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX", "GEOIP",
           "IP-CIDR", "IP-CIDR6", "SRC-IP-CIDR", "DST-PORT", "SRC-PORT",
           "PROCESS-NAME", "RULE-SET", "MATCH", "GEOSITE"}


def main():
    build = sys.argv[1] if len(sys.argv) > 1 else "build"
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

    print("配置 %d 份 / 规则集 %d 份（%d 条）/ 错误 %d 个"
          % (n_cfg, n_payload, n_entries, len(errs)))
    for e in errs[:20]:
        print("  ✗", e)
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
