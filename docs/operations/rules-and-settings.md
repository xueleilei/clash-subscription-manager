# 规则与基础设置

规则文本导入先预览，任何错误都会使整批不可应用，原规则保持不变。普通规则追加在现有 MATCH 前；导入 MATCH 必须显式选择 `REPLACE`，最终只能保留一个末尾 MATCH。接口接受当前页面的未保存文档和读取时的草稿版本，预览不写数据库；应用结果仍走版本化保存。

可视化目标和顶层 RULE-SET 使用内部 ID。代理组和规则集改名不改变引用。节点重名导致的歧义必须由用户选择，不能任意选中一个同名节点。规则文本中的原表达式、参数和原文保留在草稿中。

支持 DOMAIN、DOMAIN-SUFFIX、DOMAIN-KEYWORD、IP-CIDR/IP-CIDR6、PROCESS-NAME、GEOSITE、GEOIP、MATCH、RULE-SET，以及内核支持的端口、网络、进程路径、正则和 AND/OR/NOT 表达式。词法识别括号和引号；实际发布仍须通过固定 Mihomo 内核。普通匹配值内含逗号不能按 CSV 语义导出；正则中的逗号按内核原表达式保留。

尚不能安全改写的引用会阻止发布并定位到字段：逻辑表达式内部的 RULE-SET/SUB-RULE、基础 YAML 的 sub-rules、DNS 文本中的命名代理或规则集、TUN 的规则集地址引用、规则集下载参数中的命名代理。可先保存为待修正草稿。下载代理当前允许稳定的 DIRECT / REJECT，header 和非负 size-limit 可配置。

远程规则集由客户端下载，管理器不在发布时请求远端内容。支持 HTTP、domain/ipcidr/classical 行为和 yaml/text 格式；mrs 仅用于 domain/ipcidr。interval 为正整数秒。缓存 path 由管理器生成，不接受用户文件路径。

基础 YAML 可配置 DNS、端口、嗅探、IPv6、TUN 等固定内核识别的设置；顶层未知字段会报错。`proxies`、`proxy-groups`、`rules`、`rule-providers`、`proxy-providers` 由管理器维护，不能通过基础 YAML 覆盖。原文可以保存不完整内容，只有结构、引用和实际内核校验均通过才能发布。YAML 输出保留字段语义，不保留注释、缩进和锚点形式。

语法依据：[Mihomo 1.19.33 规则分隔](https://github.com/MetaCubeX/mihomo/blob/v1.19.33/rules/common/base.go)、[规则类型](https://github.com/MetaCubeX/mihomo/blob/v1.19.33/rules/parser.go)、[配置字段](https://github.com/MetaCubeX/mihomo/blob/v1.19.33/config/config.go)、[官方规则集文档](https://wiki.metacubex.one/en/config/rule-providers/)。
