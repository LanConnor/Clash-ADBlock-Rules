# Clash-ADBlock-Rules

把 [Johnshall / Moshel 的 Shadowrocket 规则集](https://github.com/Johnshall/Shadowrocket-ADBlock-Rules-Forever)
**全部 14 个变体**每天自动转换成 Clash（mihomo 内核）可用的规则，发布在本仓库的 `dist` 分支。
你按自己的场景挑一个变体用就行。

上游每天 UTC 23:00 左右重新构建，本仓库 UTC 23:30 跟进。

## 变体一览

| 变体 | 分类 | 说明 | 配置地址 |
|---|---|---|---|
| 白名单 + 去广告 ★ | 白名单 | top500 里可直连的走直连，其余境外站点走代理，同时拦截广告。日常最通用的选择。 | `whitelist-ad/config.yaml` |
| 白名单 | 白名单 | 同上，但不含广告拦截规则。 | `whitelist/config.yaml` |
| 黑名单 + 去广告 | 黑名单 | 只代理 GFWList 命中的站点，其余（含国内）全部直连，同时拦截广告。 | `banlist-ad/config.yaml` |
| 黑名单 | 黑名单 | 同上，但不含广告拦截规则。 | `banlist/config.yaml` |
| 国内外划分 + 去广告 | 国内外划分 | 按 GeoIP 划内外：境外一律代理，国内直连，同时拦截广告。 | `cnip-ad/config.yaml` |
| 国内外划分 | 国内外划分 | 同上，但不含广告拦截规则。 | `cnip/config.yaml` |
| 回国 + 去广告 | 回国 | 反过来的逻辑：国内站点走代理、境外直连。给海外用户访问国内服务用。 | `backcn-ad/config.yaml` |
| 回国 | 回国 | 同上，但不含广告拦截规则。 | `backcn/config.yaml` |
| 全局直连 + 去广告 | 特殊规则 | 所有流量直连，只借这份配置的广告拦截能力。 | `direct-ad/config.yaml` |
| 全局代理 + 去广告 | 特殊规则 | 所有流量走代理，只有广告域名被拦。 | `proxy-ad/config.yaml` |
| 仅去广告 | 特殊规则 | 只拦广告，其余全部直连。上游没有兜底规则，这里补了 MATCH,DIRECT。 | `ad-only/config.yaml` |
| 综合版 | 特殊规则 | 上游另一套合并规则，黑白名单混合，行为与白名单版接近。 | `adb/config.yaml` |
| 懒人配置 | 懒人 | 39 条粗粒度规则，不依赖任何外部规则集，体积最小。 | `lazy/config.yaml` |
| 懒人配置（含策略组） | 懒人 | 39 条规则 + AI、流媒体、游戏平台、微软谷歌苹果等 18 个独立分流组。 | `lazy-group/config.yaml` |

★ = 推荐默认。每份 `config.yaml` 都是完整的 Clash 配置骨架，规则集通过远程 `rule-providers` 引用，
配一次以后自动更新。

**地址前缀：**

```
https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/
```

默认变体地址是 `.../dist/config.yaml`（等同于 `.../dist/whitelist-ad/config.yaml`）。
机器可读的完整清单见 `.../dist/index.json`。

## 产物结构

```
dist/
├── config.yaml            默认变体（= whitelist-ad）
├── index.json             变体清单：名称、分类、统计、每个变体的 provider 地址
├── meta.json              构建信息 + content_hash（工作流靠它判断要不要推送）
├── common/adblock.yaml    广告拦截规则，8 个含广告的变体共用这一份（约 2.0 MB）
├── <变体id>/
│   ├── config.yaml        该变体的完整配置
│   ├── direct.yaml        直连集合（与其他变体内容相同时自动去重共用）
│   └── proxy.yaml         代理集合
└── rulesets/              需要本地化的第三方清单（如 iab0x00 的 AI 列表）
```

几个数字：广告规则去重后 52886 条、14 个变体、全部产物约 3.2 MB。
8 个含广告的变体共用同一份广告规则，没变成 8 × 2 MB。

## 客户端怎么用

### 方案 A（推荐）：只订阅规则，节点用你自己的

在现有 Clash 配置里追加 [config/rule-provider-snippet.yaml](config/rule-provider-snippet.yaml) 的内容——
几个 `rule-providers` + 十几条 `rules`，改一次，以后规则每天自动更新，节点完全不用动。

### 方案 B：整份配置当订阅

用 [config/proxy-provider-example.yaml](config/proxy-provider-example.yaml) 的思路：
节点通过 `proxy-providers` 拉你的机场 Clash 订阅，规则指向本仓库。
客户端只加一个地址，节点和规则都自动更新。

## 部署状态

- `main` = 代码与工作流，`dist` = 构建产物（永远只有 1 个提交）。
- GitHub Actions 每天 UTC 23:30 自动跑，也可在 Actions 页面手动 Run workflow。
- 工作流自带体检（`scripts/validate.py`）：规则类型、provider 声明、策略引用、MATCH 兜底，
  任何一项不对就直接失败，不会把坏配置推到线上。

## 国内网络访问

`raw.githubusercontent.com` 在国内经常被墙，两个替代：

- **jsDelivr**：把 `raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist`
  换成 `cdn.jsdelivr.net/gh/LanConnor/Clash-ADBlock-Rules@dist`。
  分支地址最长 12 小时缓存，规则最多滞后半天。
- **GitHub Pages**：Settings → Pages → Source 选 `dist` 分支根目录，
  之后用 `https://lanconnor.github.io/Clash-ADBlock-Rules/`。

## 设计说明

- **`dist` 分支永远只有一个提交**。每次重建孤儿分支再 force-push，仓库体积不随天数增长，
  raw 地址保持稳定；内容没变化时直接跳过推送。
- **规则顺序严格保留**：广告拦截 → 直连 → 代理 → GEOIP CN / 内网网段 → 兜底。
  Clash 首条匹配生效，顺序错了会整片误判。
- **上游数据冲突**：`amazon.com`、`bing.com`、`imdb.com` 等域名同时被标成代理和直连，
  转换时按「首次出现优先」裁决，与 Shadowrocket 行为一致。
- **上游 `sr_ad_only.conf` 没有兜底规则**，转换时补了 `MATCH,DIRECT`，否则未匹配流量行为不确定。
- **第三方规则集**：blackmatrix7 的清单自动换成同仓库的 Clash 版本远程引用（不落库、不再分发）；
  iab0x00 的 AI 列表没有 Clash 版，构建时抓下来本地化。
  源文件的 `[URL Rewrite]` 和 `[MITM]` 在 Clash 里没有对应能力，已丢弃。

## 维护

```bash
mkdir -p confs
for f in sr_top500_whitelist_ad.conf sr_top500_whitelist.conf; do
  curl -fsSL "https://johnshall.github.io/Shadowrocket-ADBlock-Rules-Forever/$f" -o "confs/$f"
done
python scripts/build_variants.py --conf-dir confs --out build \
  --remote-base https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist
python scripts/meta.py build
python scripts/validate.py build
```

`scripts/` 五个文件：`build_variants.py`（批量转换）、`sr2clash.py`（单文件转换，被前者复用）、
`meta.py`（生成指纹）、`validate.py`（体检）、`summary.py`（运行摘要）。

> GitHub 对公开仓库的定时工作流有限制：连续 60 天没有任何提交会被自动停用。
> 本仓库每天都有 bot 提交，不会踩到。
