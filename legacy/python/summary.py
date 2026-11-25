#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把构建结果渲染成 GitHub Actions 运行摘要（Markdown 表格）。"""
import json
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "index.json"
idx = json.load(open(path, encoding="utf-8"))

print("| 变体 | id | 分类 | REJECT | 直连 | 代理 | 自定义组 |")
print("|---|---|---|---|---|---|---|")
for v in idx["variants"]:
    s = v["stats"]
    star = " ★" if v.get("recommended") else ""
    print("| %s%s | `%s` | %s | %d | %d | %d | %d |"
          % (v["name"], star, v["id"], v["category"], s["reject"], s["direct"],
             s["proxy"], s["custom_groups"]))
print()
print("上游构建时间：%s ｜ 变体数：%d ｜ 去重前广告规则：%s 条"
      % (idx.get("upstream_build_time", "?"), len(idx["variants"]),
         idx.get("common", {}).get("adblock", {}).get("entries", "?")))
