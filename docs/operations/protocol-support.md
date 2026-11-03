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

地区仅按节点名称中的国家/地区文字、缩写或旗帜作提示；识别不到显示“未知”，不进行 IP 定位。
内核检查只证明固定内核接受配置，不能证明节点可连接。

字段参考：[固定版本 outbound](https://github.com/MetaCubeX/mihomo/tree/v1.19.33/adapter/outbound)。
真实协议夹具位于 `tests/fixtures/subscriptions/clash.yaml`，在 `MihomoValidatorIT` 中通过实际内核检查。
