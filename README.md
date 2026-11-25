# Clash-ADBlock-Rules

把 [Johnshall / Moshel 的 Shadowrocket 规则集](https://github.com/Johnshall/Shadowrocket-ADBlock-Rules-Forever)
**全部 14 个变体**每天自动转换成 Clash（mihomo 内核）可用的规则，发布在本仓库的 `dist` 分支。
你按自己的场景挑一个变体用就行。

上游每天 UTC 23:00 左右重新构建，本仓库 UTC 23:30 跟进。

转换工具已迁移到 Java（Spring Boot 4.2 / JDK 21），产物仍是标准 Clash YAML，客户端无感知。

## 订阅地址（完整）

默认订阅（推荐，白名单 + 去广告）：

```
https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/config.yaml
```

默认地址等同于 `whitelist-ad` 变体。机器可读的完整清单：

```
https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/index.json
```

**jsDelivr（国内直连更稳，完整地址示例）：**

```
https://cdn.jsdelivr.net/gh/LanConnor/Clash-ADBlock-Rules@dist/config.yaml
```

规则：把 raw 前缀 `https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist`
整体换成 `https://cdn.jsdelivr.net/gh/LanConnor/Clash-ADBlock-Rules@dist`，后面的路径不变。
分支地址最长 12 小时缓存，规则最多滞后半天。

**GitHub Pages（可选）**：Settings → Pages → Source 选 `dist` 分支根目录后，
用 `https://lanconnor.github.io/Clash-ADBlock-Rules/config.yaml`。

## 变体一览（完整地址）

★ = 推荐默认。每份 `config.yaml` 都是完整的 Clash 配置骨架，规则集通过远程 `rule-providers` 引用，
配一次以后自动更新。**注意：配置里只有一个占位节点，不能直接当带节点的订阅用**，
节点接入方式见下方「客户端怎么用」。

| 变体 | 分类 | 说明 | 完整订阅地址 |
|---|---|---|---|
| 白名单 + 去广告 ★ | 白名单 | top500 里可直连的走直连，其余境外站点走代理，同时拦截广告。日常最通用的选择。 | `https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/whitelist-ad/config.yaml` |
| 白名单 | 白名单 | 同上，但不含广告拦截规则。 | `https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/whitelist/config.yaml` |
| 黑名单 + 去广告 | 黑名单 | 只代理 GFWList 命中的站点，其余（含国内）全部直连，同时拦截广告。 | `https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/banlist-ad/config.yaml` |
| 黑名单 | 黑名单 | 同上，但不含广告拦截规则。 | `https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/banlist/config.yaml` |
| 国内外划分 + 去广告 | 国内外划分 | 按 GeoIP 划内外：境外一律代理，国内直连，同时拦截广告。 | `https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/cnip-ad/config.yaml` |
| 国内外划分 | 国内外划分 | 同上，但不含广告拦截规则。 | `https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/cnip/config.yaml` |
| 回国 + 去广告 | 回国 | 反过来的逻辑：国内站点走代理、境外直连。给海外用户访问国内服务用。 | `https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/backcn-ad/config.yaml` |
| 回国 | 回国 | 同上，但不含广告拦截规则。 | `https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/backcn/config.yaml` |
| 全局直连 + 去广告 | 特殊规则 | 所有流量直连，只借这份配置的广告拦截能力。 | `https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/direct-ad/config.yaml` |
| 全局代理 + 去广告 | 特殊规则 | 所有流量走代理，只有广告域名被拦。 | `https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/proxy-ad/config.yaml` |
| 仅去广告 | 特殊规则 | 只拦广告，其余全部直连。上游没有兜底规则，这里补了 MATCH,DIRECT。 | `https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/ad-only/config.yaml` |
| 综合版 | 特殊规则 | 上游另一套合并规则，黑白名单混合，行为与白名单版接近。 | `https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/adb/config.yaml` |
| 懒人配置 | 懒人 | 39 条粗粒度规则，不依赖任何外部规则集，体积最小。 | `https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/lazy/config.yaml` |
| 懒人配置（含策略组） | 懒人 | 39 条规则 + AI、流媒体、游戏平台、微软谷歌苹果等 17 个独立分流组。 | `https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist/lazy-group/config.yaml` |

## 产物结构

```
dist/
├── config.yaml            默认变体（= whitelist-ad）
├── index.json             变体清单：名称、分类、统计、每个变体的 provider 地址
├── meta.json              构建信息 + content_hash（工作流靠它判断要不要推送）
├── common/adblock.yaml    广告拦截规则（7 个单段广告变体共用，约 2.0 MB）
├── <变体id>/
│   ├── config.yaml        该变体的完整配置
│   ├── direct.yaml        第 1 段直连规则
│   ├── proxy.yaml         第 1 段代理规则
│   ├── direct-2.yaml      第 2 段直连规则（上游 direct / proxy 交错时会分段）
│   └── ...
├── adb/
│   ├── adblock.yaml       综合版的第 1 段广告规则
│   └── adblock-2.yaml     综合版的第 2 段广告规则（被代理规则隔开）
└── rulesets/              需要本地化的第三方清单（如 iab0x00 的 AI 列表）
```

几个数字：广告规则去重后 52886 条、14 个变体、全部产物约 6.2 MB。
8 个含广告变体的广告集合完全一致；其中 7 个单段变体共用 `common/adblock.yaml`，
`adb` 的广告块被代理规则隔成两段，单独成 provider（内容集合相同，只是顺序不同）。

## 客户端怎么用

### 方案 A（推荐）：只订阅规则，节点用你自己的

在现有 Clash 配置里追加 [config/rule-provider-snippet.yaml](config/rule-provider-snippet.yaml) 的内容——
几个 `rule-providers` + 十几条 `rules`，改一次，以后规则每天自动更新，节点完全不用动。
片段之间是有顺序的（`direct` → `proxy` → `direct-2`），**不要自行合并或按策略重排**。

### 方案 B：整份配置当订阅

用 [config/proxy-provider-example.yaml](config/proxy-provider-example.yaml) 的思路：
节点通过 `proxy-providers` 拉你的机场 Clash 订阅，规则指向本仓库。
客户端只加一个地址，节点和规则都自动更新。前提是机场提供 Clash 格式订阅；
Shadowrocket 的 `ss://` base64 订阅不是 Clash 格式，拉不了。

## 部署状态

- `main` = 代码与工作流，`dist` = 构建产物（永远只有 1 个提交）。
- GitHub Actions 每天 UTC 23:30 自动跑，也可在 Actions 页面手动 Run workflow。
- 工作流自带体检（`java -jar target/clash-rules.jar validate build confs`）：规则类型、provider 声明、
  策略引用、MATCH 兜底、provider 缓存路径唯一性，并逐变体校验「首条匹配」语义与上游一致；
  任何一项不对就直接失败，不会把坏配置推到线上。

## 国内网络访问

`raw.githubusercontent.com` 在国内经常被墙，两个替代：

- **jsDelivr**：把 `raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist`
  换成 `cdn.jsdelivr.net/gh/LanConnor/Clash-ADBlock-Rules@dist`。
  完整示例：`https://cdn.jsdelivr.net/gh/LanConnor/Clash-ADBlock-Rules@dist/whitelist-ad/config.yaml`。
- **GitHub Pages**：Settings → Pages → Source 选 `dist` 分支根目录，
  之后用 `https://lanconnor.github.io/Clash-ADBlock-Rules/whitelist-ad/config.yaml`。

## 设计说明

- **`dist` 分支永远只有一个提交**。每次重建孤儿分支再 force-push，仓库体积不随天数增长，
  raw 地址保持稳定；内容没变化时直接跳过推送（`index.json` 里不写时间戳，
  构建时间只记在 `meta.json`，因此内容不变时 `content_hash` 不变）。
- **规则顺序严格保留**：转换时按上游原始顺序，把连续的同策略规则切成片段，每个片段一个
  provider，`RULE-SET` 按原顺序排列。不能按 REJECT / DIRECT / PROXY 分桶后重排：
  上游白名单文件的 direct 与 proxy 是交错的（`direct 101 条 → proxy 389 条 → direct 280 条`），
  重排会让 `adobe.com`、`amazon.com`、`bing.com`、`disney.com`、`imdb.com`、`mozilla.org`
  等 12 个域名从「代理」变成「直连」。
- **provider 缓存路径带变体 id**（`./ruleset/<变体id>-<provider名>.yaml`），
  切换变体时不会复用同名旧缓存。
- **上游数据冲突**：同一域名可能同时被标成代理和直连（如 `amazon.com`、`bing.com`），
  分段后 Clash 的首条匹配结果与 Shadowrocket 完全一致。
- **上游 `sr_ad_only.conf` 没有兜底规则**，转换时补了 `MATCH,DIRECT`，否则未匹配流量行为不确定。
- **第三方规则集**：blackmatrix7 的清单自动换成同仓库的 Clash 版本远程引用（不落库、不再分发）；
  iab0x00 的 AI 列表没有 Clash 版，构建时抓下来本地化（80 条，包含 `DOMAIN-WILDCARD`）。
- **已知差异**：所有 `IP-CIDR` 统一补 `no-resolve`（内网直连规则需要；代价是广告 IP 规则
  只对直连 IP 生效，不参与域名解析后的匹配）；`[General]` 的 `ipv6` 开关会带到生成配置；
  源文件的 `[Host]`、`skip-proxy`、`[URL Rewrite]` 和 `[MITM]` 在 Clash 里没有直接对应，
  不做转换（`[URL Rewrite]` 只是 google.cn 的 302，`[MITM]` 是配套项，不影响去广告）。

## 维护

```bash
mkdir -p confs
for f in sr_top500_whitelist_ad.conf sr_top500_whitelist.conf sr_top500_banlist_ad.conf \
         sr_top500_banlist.conf sr_cnip_ad.conf sr_cnip.conf sr_backcn_ad.conf sr_backcn.conf \
         sr_direct_banad.conf sr_proxy_banad.conf sr_ad_only.conf sr_adb.conf lazy.conf lazy_group.conf; do
  curl -fsSL "https://johnshall.github.io/Shadowrocket-ADBlock-Rules-Forever/$f" -o "confs/$f"
done
mvn -q -B -DskipTests package
java -jar target/clash-rules.jar build --conf-dir confs --out build \
  --remote-base https://raw.githubusercontent.com/LanConnor/Clash-ADBlock-Rules/dist
java -jar target/clash-rules.jar meta build
java -jar target/clash-rules.jar validate build confs
```

工程使用 Spring Boot 4.2 + JDK 21，入口是 `target/clash-rules.jar`，子命令：
`build`（批量转换）、`sr2clash`（单文件转换）、`meta`（生成指纹）、
`validate`（体检 + 顺序等价校验）、`summary`（运行摘要）。
原 Python 脚本归档在 `legacy/python/`，仅作对照参考，CI 不再使用。

> 环境要求：JDK 21、Maven 3.9+。迁移后的 Java 产物与之前的 Python 版本逐字节一致。

> GitHub 对公开仓库的定时工作流有限制：连续 60 天没有任何提交会被自动停用。
> 本仓库每天都有 bot 提交，不会踩到。
