# MCP Gateway Agent

## 项目概述

MCP Gateway Agent 是整个 Agent 体系的**纯 MCP 协议代理 + 治理平面**，基于 Spring Boot 4.1.1 + Spring AI 2.0.1 + MCP Java SDK 2.0 构建。它向上对外提供 MCP（Model Context Protocol）**Streamable HTTP** 接口，向下通过可配置的 HTTP 协议映射将 MCP 工具调用转换为对真实业务系统（如 `agent-add-oil`）的 HTTP 调用，并支持从 OpenAPI 规范一键导入协议配置。系统采用 DDD 架构，具备运营配置管理后台、虚拟密钥/CEL 工具治理/配额限流的治理面与全链路可观测性上报能力。agent 对话宿主已删除（工单 0022，归档说明见 [../docs/03-mcp-gateway-agent/13-agent宿主删除与归档.md](../docs/03-mcp-gateway-agent/13-agent宿主删除与归档.md)），智能体能力由 aggregation-support-agent 承担。

### 核心特性

- **MCP 协议网关**：官方 Streamable HTTP 单端点（`/api-gateway/{gatewayId}/mcp`，POST 消息 / GET 监听流 / DELETE 会话终止），支持 `initialize` / `tools/list` / `tools/call` 等 JSON-RPC 方法；旧 SSE 端点已下线（[迁移指南](../docs/03-mcp-gateway-agent/10-SSE下线与StreamableHTTP迁移.md)）
- **外部 MCP 挂接**：管理员经 `/admin/v1/external-attaches` 配置外部 MCP server（streamable HTTP / stdio），其工具以 `attachName_toolName` 前缀并入网关清单并可透传调用，连接状态可观测、上游工具漂移自动刷新（[工具联邦](../docs/03-mcp-gateway-agent/11-外部MCP挂接与工具联邦.md)）
- **协议映射引擎**：HTTP 协议配置 + 字段映射（parentPath/fieldName → mcpPath/mcpType），将 MCP 工具入参转换为 HTTP 请求
- **OpenAPI 导入**：从 OpenAPI JSON 解析端点并一键生成网关协议配置
- **治理面**：vk- 虚拟密钥统一认证（401/403）、CEL 工具可见性与调用拦截（tools/list 隐藏 + tools/call 结构化拒绝）、per-key RPM/日配额限流（429 + 剩余额度）
- **运营后台**：网关/工具/协议的增删改查与分页，治理对象（密钥/规则/审计）管理，JWT 角色保护
- **可观测性**：调用过程通过 `ObservabilityHelper` 上报到 `agent-rag-observability-server`，TraceContext 跨服务传递 traceId

## 使用功能

### 1. MCP 网关接口（Streamable HTTP — `/api-gateway/{gatewayId}/mcp`）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api-gateway/{gatewayId}/mcp` | JSON-RPC 消息（initialize 握手、tools/list、tools/call、ping、通知）；响应头返回 `Mcp-Session-Id` |
| GET | `/api-gateway/{gatewayId}/mcp` | 服务端推送监听流（携带 `Mcp-Session-Id` 请求头） |
| DELETE | `/api-gateway/{gatewayId}/mcp` | 终止会话 |

凭证：`Authorization: Bearer <JWT 或 vk- 密钥>`（或兼容 `?api_key=`）。旧 `/mcp/sse` 端点已移除（404）。

agent 对话接口（`/api/v1/*`）已随 agent 宿主删除整体下线（404），见 [归档说明](../docs/03-mcp-gateway-agent/13-agent宿主删除与归档.md)。

### 2. 运营管理接口（AdminController — `/admin`）

| 分组 | 接口 |
|------|------|
| 网关配置 | `save_gateway_config`、`query_gateway_config_list`、`query_gateway_config_page` |
| 网关工具 | `save_gateway_tool_config`、`query_gateway_tool_list[_by_gateway_id]`、`query_gateway_tool_page`、`delete_gateway_tool_config` |
| 网关协议 | `save_gateway_protocol`、`import_gateway_protocol`、`analysis_protocol`、`query_gateway_protocol_list[_by_gateway_id]`、`query_gateway_protocol_page`、`delete_gateway_protocol` |
| 网关认证 | `save_gateway_auth`、`query_gateway_auth_list[_by_gateway_id]`、`query_gateway_auth_page`、`delete_gateway_auth` |
| 测试 | `test_call_gateway`（LLM 链路验证调用，API Key 脱敏日志） |
| 治理面 | 虚拟密钥/CEL 规则/审计（`/admin/v1/*`，0017/0018）；外部挂接（`/admin/v1/external-attaches`，0021） |

## 设计思路

### DDD 架构设计

```
mcp-gateway-agent/
├── mcp-gateway-agent-api/              # API 层：服务接口、DTO、Response
├── mcp-gateway-agent-types/            # 类型层：常量、枚举、异常
├── mcp-gateway-agent-case/            # 用例层：admin/mcp 用例接口
├── mcp-gateway-agent-domain/          # 领域层：核心业务（session/gateway/protocol/auth/governance/externalattach/llm）
├── mcp-gateway-agent-infrastructure/ # 基础设施层：DAO、Redis、GenericHttpGateway、可观测性
├── mcp-gateway-agent-trigger/         # 触发器层：HTTP Controller/Servlet、异常处理
└── mcp-gateway-agent-app/             # 应用层：启动配置
```

### MCP 工具调用链路

```
MCP Client（Streamable HTTP）
  │  POST initialize → /api-gateway/{gatewayId}/mcp（官方传输建会话，响应头 Mcp-Session-Id）
  ▼
GovernanceAuthFilter / QuotaEnforcementFilter   # 统一认证（401/403）+ per-key 配额（429）
  ▼
McpGatewayDelegateServlet                       # 委派路由：tools/list CEL 过滤自答、其余委派官方服务器
  ▼
GatewayMcpServerRegistry（per-gateway 官方 McpSyncServer）
  │  tools/call → McpToolInvocationService（CEL 拦截 -32006 + 必填校验）
  ▼
GenericHttpGateway                              # 按 protocolId 找到 HTTP 协议配置
  │  字段映射：MCP 入参 → HTTP 请求（headers/url/method/body）
  ▼
业务系统（agent-add-oil 等）
  │
  ▼
ObservabilityHelper.reportToolCall()            # 上报 traceId + toolName + 耗时 + 状态
```

### 协议字段映射

`HTTPProtocolVO.ProtocolMapping` 描述 MCP 参数到 HTTP 字段的映射：

- `parentPath` / `fieldName`：源参数位置
- `mcpPath` / `mcpType`：目标 MCP 字段与类型
- `mappingType`：映射方式
- `isRequired` / `sortOrder`：必填与排序

## 使用技术

### 核心框架

| 技术 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 4.1.1 | 应用框架（0016 升级） |
| Java | 21 | 编程语言 |
| Spring AI | 2.0.1 | LLM 集成（admin LLM 验证）+ MCP 客户端 |
| MCP Java SDK | 2.0.0 | Streamable HTTP 协议（服务器/客户端） |
| xfg-wrench-design-framework | 3.0.0（净化版） | 设计模式脚手架 |

### 数据存储与中间件

| 技术 | 说明 |
|------|------|
| MySQL 8.0.33 | 关系数据库（网关/工具/协议/认证配置） |
| Redis | 会话与缓存 |
| MyBatis 3.0.4 | ORM |
| Reactor Core 3.7.3 | 响应式（SSE） |
| Retrofit2 + OkHttp | HTTP 客户端 |
| FastJSON 2.0.52 | JSON 处理 |
| JJWT / java-jwt | 鉴权 |
| Guava | 工具库 |
| XStream / dom4j | XML 处理 |

### AI 模型

| 提供商 | 用途 |
|--------|------|
| 智谱 BigModel（GLM） | 对话模型 |
| DeepSeek | 对话模型 |
| 阿里云 DashScope（千问） | 对话模型 |

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.8+
- MySQL 8.x、Redis
- 可选：`agent-rag-observability-server`（用于链路上报）

### 启动步骤

1. **准备配置**

复制 `mcp-gateway-agent-app/src/main/resources/application.yml.example` 为 `application-dev.yml`，配置数据源、Redis、LLM API Key、可观测性地址。默认服务端口 `8777`（`cors.allowed-origins` 默认指向 `http://127.0.0.1:3000`）。

2. **构建**

```bash
mvn clean package -DskipTests
```

3. **启动**

```bash
java -Dspring.profiles.active=dev \
     -jar mcp-gateway-agent-app/target/mcp-gateway-agent-app.jar
```

### 验证服务

```bash
# initialize 握手（Streamable HTTP 单端点，响应头返回 Mcp-Session-Id）
curl -D - http://localhost:8777/api-gateway/gateway_001/mcp \
     -H "Authorization: Bearer <your-vk-key>" \
     -H "Content-Type: application/json" \
     -H "Accept: application/json, text/event-stream" \
     -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
```

### 运营后台

通过 `/admin/*` 接口配置网关、工具、协议映射与认证 Key，或通过 `import_gateway_protocol` 从 OpenAPI JSON 一键导入。

## 项目结构

```
mcp-gateway-agent/
├── mcp-gateway-agent-api/
├── mcp-gateway-agent-types/
├── mcp-gateway-agent-case/
├── mcp-gateway-agent-domain/
├── mcp-gateway-agent-infrastructure/
│   └── src/main/java/cn/chyuan/ai/infrastructure/
│       ├── dao/                 # MyBatis DAO
│       ├── externalattach/      # ExternalMcpAttachRegistry 外部 MCP 挂接客户端（0021）
│       ├── gateway/             # GenericHttpGateway 协议执行 + streamable 网关注册表
│       ├── redis/
│       └── utils/                # ObservabilityHelper、TraceContext
├── mcp-gateway-agent-trigger/
│   └── src/main/java/cn/chyuan/ai/trigger/
│       ├── http/McpGatewayDelegateServlet.java  # Streamable HTTP 委派路由（0020）
│       ├── http/AdminController.java            # 运营管理
│       ├── http/AdminGovernanceController.java  # 治理面（0017/0018）
│       ├── http/AdminExternalAttachController.java # 外部 MCP 挂接管理（0021）
│       └── filter/                              # 统一认证 / 配额 / admin JWT 过滤器
├── mcp-gateway-agent-app/
│   └── src/main/resources/
│       ├── mybatis/mapper/*.xml   # Mapper
│       ├── application.yml.example
│       └── logback-spring.xml
└── pom.xml
```

## 在 Agent 体系中的位置

```
用户 → aggregation-support-agent-web (前端, 3000)
     → aggregation-support-agent (RAG/AIOps 后端, 8091)
        → mcp-gateway-agent (MCP 网关, 8777)   ← 本项目
           → agent-add-oil (加油业务, 8877)
              → 油站渠道 / 支付通道

agent-rag-observability-server (8092) ← 接收本服务上报的 Trace
```

本服务是 RAG/AIOps 后端调用真实业务系统的「协议转换层」，把 MCP 工具调用翻译为 HTTP 请求，同时上报全链路 Trace。

## 许可证

Apache License, Version 2.0

## 联系方式

- 开发者：chyuan
- 邮箱：184172133@qq.com
