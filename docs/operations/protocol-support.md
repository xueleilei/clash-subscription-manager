# 输入协议矩阵

以 Mihomo 1.19.33 的字段模式为依据。每份订阅最多 10 MiB、20000 个节点。
UTF-8、YAML 键、节点名称及全部连接字段均严格检查；一个节点失败即拒绝整个结果。
诊断包含节点索引/字段路径，绝不回显密码、公钥值、订阅 URL 或原始错误上下文。

## Clash / Mihomo YAML

来源订阅只提取 `proxies`；其他完整配置内容由“完整配置导入”处理。`proxies: []`
是有效空结果，需要用户决定保留或清空；空响应、无 proxies、provider-only 都不是有效空结果。
不展开 proxy-providers。名字按精确 Unicode 字符串处理，保留尾随空格，不折叠大小写或组合字符。

| 类型 | 本版范围 | 明确拒绝 |
| --- | --- | --- |
| ss | 常见 AEAD/传统 cipher、2022 密钥长度检查；obfs、v2ray-plugin；UDP/UOT | 未知 cipher/plugin 或 plugin 参数 |
| ssr | 支持矩阵中的传统 cipher；origin/auth_sha1_v4/auth_aes128/auth_chain；plain/http/tls ticket 混淆 | 未知协议及混淆方式 |
| vmess | UUID、alterId、auto/none/zero/AES/ChaCha；TCP/WS/HTTP/H2/gRPC；TLS/Reality | MKCP、Mekya、TLSMirror 等未实现扩展 |
| vless | UUID、none encryption、Vision；TCP/WS/HTTP/H2/gRPC；TLS/Reality | XHTTP、非 none encryption、未知 flow |
| trojan | 密码、TCP/WS/gRPC、TLS/Reality、已定义 ss-opts | 未定义的传输/连接扩展 |
| hysteria2 | 密码、salamander、端口跳跃、SNI/ALPN、带宽和已定义 QUIC 参数 | Realm、客户端证书、未知混淆 |
| tuic | v4 token 或 v5 UUID/password；SNI/ALPN、拥塞控制、UDP 参数 | 混用两套认证；未知参数 |
| http / socks5 | YAML 节点，认证及 TLS，HTTP headers | 本地证书文件或未知字段 |

通用字段支持 server、port、interface-name、routing-mark、ip-version、dialer-proxy、TFO/MPTCP。
HTTP/WS headers 必须符合对应字符串/字符串列表模式。安全边界暂不接受客户端证书、私钥文件、
ECH、自定义脚本或未列明扩展；失败时保留原订阅数据，用户可查看字段诊断后调整。
完整 JSON 连接对象按字段保存，输出阶段只重写节点名及结构化引用。

手动节点可以指定地区。自动识别使用节点名称中的国家或地区文字、缩写和旗帜，识别不到时显示“未知”。地区选项与别名由服务器维护。参见[节点地区配置](node-regions.md)。
内核检查只证明固定内核接受配置，不能证明节点可连接。

字段参考：[固定版本 outbound](https://github.com/MetaCubeX/mihomo/tree/v1.19.33/adapter/outbound)。
真实协议夹具位于 `tests/fixtures/subscriptions/clash.yaml`，在 `MihomoValidatorIT` 中通过实际内核检查。

### 上游生成器兼容

`tests/fixtures/subscriptions/mihomo-compatibility.yaml` 覆盖 ECH、smux、Hysteria2 数字参数和常见转换器默认值；解析后保留这些字段，并使用固定 Mihomo 内核校验输出。

- VMess、VLESS、Trojan、Hysteria2 和 TUIC 支持 `ech-opts` 的 `enable`、`config`、`query-server-name`，遵循 [Mihomo 1.19.33 ECH 参数](https://github.com/MetaCubeX/mihomo/blob/v1.19.33/adapter/outbound/ech.go)。
- 节点支持 `smux` 的开关、协议、连接数、流数、padding、statistic、only-tcp 及 `brutal-opts`；嵌套字段仍按 [固定版本结构](https://github.com/MetaCubeX/mihomo/blob/v1.19.33/adapter/outbound/singmux.go) 校验，未知字段不会被忽略。
- Hysteria2 的 `up`、`down`、`hop-interval` 接受文本或非负整数，数字带宽按 Mbps、数字跳跃间隔按秒解释；对应 [固定版本选项](https://github.com/MetaCubeX/mihomo/blob/v1.19.33/adapter/outbound/hysteria2.go)。
- VLESS 兼容生成器附带的 `alterId: 0` 和 `cipher: auto`；TUIC 兼容冗余的 `udp: true`。这些默认值原样保留，其他值会报错，避免把不生效的参数误认为已配置成功。

任意节点或参数校验失败时，整个候选订阅仍不提交，旧节点和代理组关系保持不变。

## URI 列表与 Base64

自动识别直接 YAML、逐行节点链接，或一层 Base64 包装的 YAML/链接列表。
支持标准与 URL-safe 字母表及省略 padding；拒绝损坏编码和任意多层包装。
协议自身规定的 VMess/SSR/SS 内部 Base64 字段正常解码。无名链接使用协议、规范化主机与端口命名，
不把凭据放入名字；多个链接得到同名时要求用户修改来源名称，不自动加随机后缀。

| scheme | 支持的分享形式与字段 | 拒绝的变体 |
| --- | --- | --- |
| ss | SIP002 百分号编码认证或 Base64URL userinfo；旧式整体 Base64；plugin=obfs-local/simple-obfs/v2ray-plugin | SS 2022 的 Base64 认证；未知插件参数 |
| ssr | Base64URL 主体与密码；remarks、group、obfsparam、protoparam，允许空的可选字段 | 未知连接参数；group 只作为来源元数据，不自动建代理组 |
| vmess | v2rayN v2 JSON；UUID authority URI；auto/none/zero/AES/ChaCha；TCP/WS/HTTP/H2/gRPC | 其他 JSON 字段、非 v2 版本、未知传输 |
| vless | UUID authority；encryption=none；security=none/tls/reality；sni、fp、pbk、sid、flow、type、host、path、serviceName、alpn | XHTTP/KCP、Reality spiderX、其他 encryption、无 TLS 的 Vision |
| trojan | 密码 authority；TLS、TCP/WS/gRPC；sni/peer、alpn、allowInsecure/insecure | security=none；未知扩展 |
| hysteria2 / hy2 | auth authority，缺省端口 443，端口范围；sni、insecure、salamander、obfs-password、pinSHA256、alpn | Realm、ECH、未被固定内核模式覆盖的新混淆 |
| tuic | v4 token 或 v5 UUID:password；sni、alpn、congestion_control、udp_relay_mode、allow_insecure/allowInsecure、disable_sni、reduce_rtt | 未列明的参数；两套认证混用 |

VLESS/VMess 的 gRPC 仅支持 gun 模式；WS 支持 ed/eh 早期数据选项。重复 query key、冲突别名、
在其他传输中无效的连接参数都报错。百分号解码使用严格 UTF-8，`+` 按 URI 字面字符保留。
任意一行失败只返回定位诊断，不提供可提交的部分节点列表；已有数据与分组不受影响。
全部 7 类 URI 的人工夹具在 `tests/fixtures/subscriptions/uris.txt`，转换后一起通过真实 Mihomo 检查。

原始参考：[Shadowsocks SIP002](https://shadowsocks.org/doc/sip002.html)、
[SSR 分享格式](https://github.com/shadowsocksrr/shadowsocks-rss/wiki/SSR-QRcode-scheme)、
[v2rayN VMess JSON](https://github.com/2dust/v2rayN/wiki/Description-of-VMess-share-link)、
[VLESS/VMess URI 提案](https://github.com/XTLS/Xray-core/discussions/716)、
[Hysteria 2 URI](https://v2.hysteria.network/docs/developers/URI-Scheme/)。
Trojan 与 TUIC 的分享参数按上表支持的通用客户端形式映射，协议字段依据
[Trojan 配置](https://trojan-gfw.github.io/trojan/config)、
[TUIC 协议](https://github.com/tuic-protocol/tuic/blob/master/SPEC.md)与固定 Mihomo 模式；不声称存在统一 TUIC URI 标准。
## Hysteria2 带宽边界

`up` / `down` 按固定内核的 [StringToBps](https://github.com/MetaCubeX/mihomo/blob/v1.19.33/common/utils/mbps.go) 接受整数（默认 Mbps）或整数加 `bps`、`Bps` 及大写 K/M/G/T 前缀。空字符串和零沿用内核默认行为；非法文本和数值溢出由管理器拒绝，避免被内核默认为零。
