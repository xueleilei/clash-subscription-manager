# 节点地区配置

编辑服务器上的 [config/node-regions.yaml](../../config/node-regions.yaml)。这是一份完整的地区表：手动节点、统一节点池和方案工作台都从后端读取相同的分组、显示名称与代码。工作台只列出候选节点中实际存在的地区。无需修改或重新编译前后端代码。

在合适分组的 `regions:` 下添加一项即可。例如添加喀麦隆：

```yaml
      - code: "CM"
        name: 喀麦隆
        aliases: [喀麥隆, Cameroon, 雅温得, Yaoundé]
```

- `code` 是唯一的两位大写字母；加引号可以避免 `"NO"` 等代码被 YAML 当作布尔值。显示名称和代码会自动用于识别，旗帜由代码生成。
- `aliases` 可省略，可填写繁体字、英文名称或常见城市名。别名按普通文字匹配，不按正则表达式解释；不同地区不能共用同一名称或别名。
- `strictCode: true` 适合代码也是英文常用词的地区，例如印度的 `IN`。启用后，小写简称仅在整个节点名就是简称或后面带编号时识别；英文句子里的 `in` 不会被当作印度。
- 文件中的顺序就是下拉框的分组和地区顺序。识别优先使用节点名中的旗帜，其次是名称或别名，最后是独立代码；没有线索时标记为「未知地区」。不查询 IP 或外部定位服务。

**Docker Compose：** 默认将宿主机文件 `./config/node-regions.yaml` 只读挂载到 app 容器。也可以在 `.env` 设置 `NODE_REGIONS_FILE=/你的目录/node-regions.yaml` 指定自己的完整文件。修改后用原部署的项目名和 Compose 文件重建 **app 容器**，无需重新构建镜像，也不重启 MySQL：

```sh
# 使用已有 MySQL
docker compose -f compose.yaml up -d --no-deps --force-recreate app

# 使用配套 MySQL，执行这一条即可
docker compose -f compose.yaml -f compose.mysql.yaml up -d --no-deps --force-recreate app
```

**直接运行 JAR：** 显式设置的 `NODE_REGIONS_FILE` 优先；未设置时先读取工作目录中的 `config/node-regions.yaml`，不存在则使用 JAR 内的默认表。改好后重启 Java 应用。

应用启动时先校验整份地区表，再分批重算已有有效节点的地区提示。刷新管理页面即可筛选，无需重新导入手动节点、刷新上游或发布方案。输入文件缺失（已显式指定）、格式错误、重复代码或别名冲突时启动会报错；修正文件后再启动。请将自定义 YAML 与部署配置一起备份。
