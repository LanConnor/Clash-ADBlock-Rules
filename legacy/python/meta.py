#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 dist/meta.json：构建时间、文件清单、内容指纹。

content_hash 是对所有产物内容算的合并指纹，工作流拿它判断
「这次构建和线上那份有没有区别」，没变化就不推送。
"""
import datetime
import hashlib
import json
import os
import sys


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: meta.py <build_dir> [src_dir]")
    build = sys.argv[1]
    src_dir = sys.argv[2] if len(sys.argv) > 2 else ""

    files = {}
    combined = hashlib.sha256()
    for root, _dirs, names in os.walk(build):
        for n in sorted(names):
            if n == "meta.json":
                continue
            fp = os.path.join(root, n)
            rel = os.path.relpath(fp, build).replace(os.sep, "/")
            with open(fp, "rb") as f:
                h = hashlib.sha256(f.read()).hexdigest()
            files[rel] = h
            combined.update(rel.encode())
            combined.update(h.encode())

    upstream_time, total_rules = "", 0
    idx_path = os.path.join(build, "index.json")
    if os.path.exists(idx_path):
        with open(idx_path, "r", encoding="utf-8") as f:
            idx = json.load(f)
        upstream_time = idx.get("upstream_build_time", "")
        total_rules = idx.get("common", {}).get("adblock", {}).get("entries", 0)

    meta = {
        "generated_at": datetime.datetime.now(datetime.timezone.utc)
        .replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "upstream_build_time": upstream_time,
        "content_hash": combined.hexdigest(),
        "file_count": len(files),
        "total_bytes": sum(os.path.getsize(os.path.join(build, p)) for p in files),
        "adblock_entries": total_rules,
        "files": files,
    }
    with open(os.path.join(build, "meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    print("content_hash = %s" % meta["content_hash"])
    print("files = %d, bytes = %.1f MB" % (meta["file_count"], meta["total_bytes"] / 1048576))


if __name__ == "__main__":
    main()
