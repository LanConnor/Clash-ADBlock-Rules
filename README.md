# Clash-ADBlock-Rules

把 [Johnshall / Moshel 的 Shadowrocket 规则集](https://github.com/Johnshall/Shadowrocket-ADBlock-Rules-Forever)
（`sr_top500_whitelist_ad.conf`）每天自动转换成 Clash（mihomo 内核）可用的规则，
产物发布在本仓库的 `dist` 分支，可通过 raw URL 直接订阅。

上游每天 UTC 23:00 左右重新构建，本仓库 UTC 23:30 跟进。

## 产物

| 路径（`dist` 分支） | 内容 | 体积 |
|---|---|---|
| `providers/adblock.yaml` | 广告拦截域名 + IP，52886 条 | ~2.4 MB |
| `providers/direct.yaml` | 白名单直连，366 条 | ~16 KB |
| `providers/proxy.yaml` | 需要走代理的境外站点 + Telegram 网段，384 条 | ~20 KB |
| `config-online.yaml` | 完整配置骨架（远程引用上面三个 provider） | ~3 KB |
| `meta.json` | 上游构建时间、各文件条目数与 sha256 | <1 KB |

订阅地址：

```
https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/providers/adblock.yaml
https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/providers/direct.yaml
https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/providers/proxy.yaml
https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/config-online.yaml
https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/meta.json
```

## 部署（5 步）

1. 仓库已建好：<https://github.com/LanConnor/Clash-ADBlock-Rules>（公开）。
2. 代码已在 `main` 分支，`dist` 分支也已用本地构建结果初始化，raw 地址现在就能用。
3. **唯一还需要手动做的一步**：打开 **Settings → Actions → General → Workflow permissions**，
   选 **Read and write permissions**（工作流要 force-push `dist` 分支）。不做这步定时任务会推送失败。
4. 打开 **Actions → sync-rules → Run workflow** 验证一次，之后每天 UTC 23:30 自动跑。

## 客户端怎么用

### 方案 A（推荐）：只订阅规则，节点用你自己的

在你的 Clash 配置里追加 [config/rule-provider-snippet.yaml](config/rule-provider-snippet.yaml) 的内容——
3 个 `rule-providers` + 12 条 `rules`，改一次，以后规则每天自动更新，节点完全不用动。

### 方案 B（进阶）：整份配置当订阅

用 [config/proxy-provider-example.yaml](config/proxy-provider-example.yaml)，
节点通过 `proxy-providers` 拉你的机场 Clash 订阅，规则指向本仓库。
这样把整份 YAML 上传回仓库（或任何静态托管），客户端只加一个地址，节点和规则都自动更新。

> 前提是你的机场订阅是 Clash 格式。不是的话用方案 A。

## 国内网络访问

`raw.githubusercontent.com` 在国内经常被墙，两个替代：

- **jsDelivr**（把 `raw.githubusercontent.com/USER/REPO/dist` 换成 `cdn.jsdelivr.net/gh/USER/REPO@dist`）：

  ```
  https://cdn.jsdelivr.net/gh/LanConnor/Clash-ADBlock-Rules@dist/providers/adblock.yaml
  ```

  注意 jsDelivr 对分支地址有最长 12 小时缓存，所以规则最多滞后半天。

- **GitHub Pages**：Settings → Pages → Source 选 `dist` 分支根目录，
  之后可以用 `https://LanConnor.github.io/Clash-ADBlock-Rules/providers/adblock.yaml`。
  Pages 走的是 Fastly CDN，国内一般能连。

## 设计说明

- **`dist` 分支永远只有一个提交**。工作流每次都重建孤儿分支再 force-push，
  这样仓库体积不会每天 +1.5 MB 越堆越大，raw 地址也保持不变。
- **规则顺序被严格保留**：广告拦截 → 白名单直连 → 代理 → GEOIP CN 直连 → 兜底。
  Clash 是首条匹配生效，顺序错了会整片误判。
- **上游数据里 `amazon.com` / `bing.com` / `imdb.com` 等 12 个域名同时被标成代理和直连**，
  转换脚本按「首次出现优先」裁决，和 Shadowrocket 的行为保持一致。
- 源文件里的 `[URL Rewrite]`（google.cn 302 跳转）和 `[MITM]` 在 Clash 里没有对应能力，已丢弃。

## 维护

```bash
# 本地手动生成一份看看
curl -fsSL https://johnshall.github.io/Shadowrocket-ADBlock-Rules-Forever/sr_top500_whitelist_ad.conf -o src.conf
python scripts/sr2clash.py -i src.conf -o out --mode provider
python scripts/meta.py out src.conf
```

脚本改动推到 `main` 会自动触发一次同步。

> GitHub 对公开仓库的定时工作流有个限制：仓库连续 60 天没有任何提交会被自动停用。
> 本仓库每天都有 bot 提交，不会踩到；如果哪天想彻底停掉，去 Actions 页面 Disable 即可。
