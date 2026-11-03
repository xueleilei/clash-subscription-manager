# 配置校验

内核固定为 Mihomo 1.19.33。首次开发运行 `./tools/mihomo/fetch.sh`；Linux
断网检查另需 `./tools/mihomo/fetch.sh linux-arm64`（x86 机器选 `linux-amd64`）。
压缩包摘要来自官方发行元数据，下载后核验；规则数据库使用仓库中的固定快照。
每次更新内核或数据必须同时更新锁文件并运行集成测试。

运行 `./scripts/mvn.sh -Dit.test=MihomoValidatorIT verify`，需要 Docker。
测试包括实际 macOS/Linux 内核，以及只读、无网络、无 capabilities 的 Linux 容器。
不会启动代理端口或 TUN。最低已测试内核就是 1.19.33，不承诺旧内核的兼容性。

管理器先做结构、字段和引用检查，再制作独立校验副本，通过参数数组执行
`mihomo -t -d <临时目录> -f -`，配置从 stdin 输入。并发上限 2，执行上限 10 秒，
输出上限 64 KiB。子进程环境重新建立，不继承 CLASH_*、代理、密钥或应用数据库环境。
配置原文不写入临时文件，退出后清理临时目录；内核原始输出不进入日志或接口。

| 输入 | 校验副本处理 | 可验证的范围 |
| --- | --- | --- |
| proxies、proxy-groups、rules、DNS、端口、嗅探 | 在字段检查后保留 | 参数及引用能否被固定内核接受 |
| HTTP rule-providers | 先检查地址、类型、behavior、format、interval、header、目标；再替换为相同行为的 inline 测试数据 | 声明和规则集引用；不验证远端内容、认证、可达性 |
| GEOIP、GEOSITE、IP-ASN | 临时目录中的固定 GeoIP/GeoSite/MMDB 数据 | 当前固定数据集中的类别及规则语法 |
| geox-url、geo-auto-update、geodata-mode | 先检查原类型及 URL；关闭更新、使用固定 dat 数据 | 不验证客户端指定的远端数据库；副本差异作为 warning 返回 |
| 文件/证书/脚本/UI、listeners、proxy-providers、sub-rules、未知协议 | 返回阻断诊断 | 不读取宿主文件，不递归展开提供者 |

这些限制针对服务端验证边界；远程规则集最终仍由客户端加载。内核检查成功不代表
节点能连接、测速成功，或规则集服务在线。发布产物保留用户原始设置，绝不保存替换副本。
导入中无法检查的字段保留在草稿并阻止发布，用户可修改后重试。

实现依据：[固定版本入口](https://github.com/MetaCubeX/mihomo/blob/v1.19.33/main.go)、
[配置解析](https://github.com/MetaCubeX/mihomo/blob/v1.19.33/config/config.go)、
[规则提供者解析](https://github.com/MetaCubeX/mihomo/blob/v1.19.33/rules/provider/parse.go)。
这些路径决定了 stdin 绕过默认配置初始化、test 模式不启动服务、inline 提供者不创建 HTTP 载体。
