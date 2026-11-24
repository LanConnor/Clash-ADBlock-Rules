#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 dist/meta.json：记录上游构建时间、各 provider 的条目数与校验和。

客户端/脚本可以拿它判断规则有没有更新，不用每次都拉 1MB 的列表去比对。
"""
import datetime
import hashlib
import json
import os
import re
import sys


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: meta.py <build_dir> [src.conf]")
    build = sys.argv[1]
    src = sys.argv[2] if len(sys.argv) > 2 else ""

    upstream_time = ""
    if src and os.path.exists(src):
        with open(src, "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                m = re.search(r"build time\s*:\s*(.+)", line, re.I)
                if m:
                    upstream_time = m.group(1).strip()
                    break

    providers = {}
    for key in ("adblock", "direct", "proxy"):
        path = os.path.join(build, "providers", key + ".yaml")
        with open(path, "rb") as f:
            data = f.read()
        providers[key] = {
            "entries": data.count(b"\n  - "),
            "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
        }

    meta = {
        "generated_at": datetime.datetime.now(datetime.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z"),
        "upstream_build_time": upstream_time,
        "source": os.environ.get("SRC_URL", ""),
        "total_entries": sum(v["entries"] for v in providers.values()),
        "providers": providers,
    }

    out = os.path.join(build, "meta.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    print(json.dumps(meta, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
