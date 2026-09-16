# MySQL 脚本（Flyway 兼容编号基线，SELFLOOP5 loop-518 / 工单 0734/0735）

> 四脚本此前 untracked（版本控制盲区），本次入库并按 Flyway `V{n}__{desc}.sql`
> 命名定序。**未引入 Flyway 库**——编号即人工执行序，未来引入迁移工具时可直接复用。

## 执行序（新环境按号递增执行）

| 序 | 脚本 | 用途 |
|----|------|------|
| V1 | V1__init_gateway_business.sql | mcp 网关 ↔ 业务系统对接初始化（244 行） |
| V2 | V2__init_gateway_yunfanoil_tools.sql | 云帆油站退款/开票工具注册（195 行） |
| V3 | V3__fix_protocol_mapping_flat.sql | mcp_protocol_mapping 去嵌套扁平化修复（19 行） |
| V4 | V4__fix_mcp_gateway_auth.sql | SSE 连接鉴权配置修复（67 行） |

## 判据（承接 loop-517 E10 调研）

- 新增 schema 变更脚本一律 `V{n}__{desc}.sql` 递增编号（禁 fix_/init_ 混放命名）；
  一个脚本一个逻辑变更（原子纪律）。
- 已执行过的脚本永不修改（历史不可变）；修正走新编号。
