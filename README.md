# MCP Gateway Agent

## 项目概述

MCP Gateway Agent 是整个 Agent 体系的**协议网关与智能体调度中心**，基于 Google ADK（Agent Development Kit）0.5.0、Spring AI 1.1.0-M3 与 LangChain4j 1.4.0 构建。它向上对外提供 MCP（Model Context Protocol）SSE 接口与 Agent 对话接口，向下通过可配置的 HTTP 协议映射将 MCP 工具调用转换为对真实业务系统（如 `agent-add-oil`）的 HTTP 调用，并支持从 OpenAPI 规范一键导入协议配置。系统采用 DDD 架构，具备运营配置管理后台与全链路可观测性上报能力。

### 核心特性

- **MCP 协议网关**：实现 MCP SSE 会话管理（`/api-gateway/{gatewayId}/mcp/sse`），支持 `initialize` / `tools/list` / `tools/call` 等 JSON-RPC 方法
- **多智能体装配**：基于 YAML 配置装配智能体（`deepseek-agent`、`zhipu-agent`、`gateway-business-agent`、`parallel_research_app` 等），支持 ReAct 与多智能体协作
- **协议映射引擎**：HTTP 协议配置 + 字段映射（parentPath/fieldName → mcpPath/mcpType），将 MCP 工具入参转换为 HTTP 请求
- **OpenAPI 导入**：从 OpenAPI JSON 解析端点并一键生成网关协议配置
- **运营后台**：网关/工具/协议/认证的增删改查与分页，网关测试调用
- **安全与限流**：网关 apiKey 鉴权、限流、过期时间管理
- **可观测性**：调用过程通过 `ObservabilityHelper` 上报到 `agent-rag-observability-server`，TraceContext 跨服务传递 traceId

## 使用功能

### 1. MCP 网关接口（McpGatewayController — `/api-gateway`）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api-gateway/{gatewayId}/mcp/sse?api_key=` | 建立 MCP SSE 连接（建立会话） |
| POST | `/api-gateway/{gatewayId}/mcp/sse?sessionId=&api_key=` | 处理 MCP JSON-RPC 消息 |

### 2. Agent 服务接口（AgentServiceController — `/api/v1`）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/query_ai_agent_config_list` | 查询智能体配置列表 |
| POST | `/api/v1/create_session` | 创建会话 |
| POST | `/api/v1/chat` | 同步对话 |
| POST | `/api/v1/chat_stream` | 流式对话（SSE） |

### 3. 运营管理接口（AdminController — `/admin`）

| 分组 | 接口 |
|------|------|
| 网关配置 | `save_gateway_config`、`query_gateway_config_list`、`query_gateway_config_page` |
| 网关工具 | `save_gateway_tool_config`、`query_gateway_tool_list[_by_gateway_id]`、`query_gateway_tool_page`、`delete_gateway_tool_config` |
| 网关协议 | `save_gateway_protocol`、`import_gateway_protocol`、`analysis_protocol`、`query_gateway_protocol_list[_by_gateway_id]`、`query_gateway_protocol_page`、`delete_gateway_protocol` |
| 网关认证 | `save_gateway_auth`、`query_gateway_auth_list[_by_gateway_id]`、`query_gateway_auth_page`、`delete_gateway_auth` |
| 测试 | `test_call_gateway`（API Key 脱敏日志） |

### 4. 智能体配置

位于 `mcp-gateway-agent-app/src/main/resources/agent/*.yml`：

| 配置 | 说明 |
|------|------|
| `zhipu-agent.yml` | 智谱 GLM 智能体 |
| `deepseek-agent.yml` | DeepSeek 智能体 |
| `gateway-business-agent.yml` | 网关业务智能体（调用 MCP 工具） |
| `parallel_research_app.yml` | 并行研究多智能体 |
| `only-one-agent.yml` / `demo.yml` / `test-agent.yml` | 单智能体与测试用例 |

## 设计思路

### DDD 架构设计

```
mcp-gateway-agent/
├── mcp-gateway-agent-api/              # API 层：服务接口、DTO、Response
├── mcp-gateway-agent-types/            # 类型层：常量、枚举、异常
├── mcp-gateway-agent-case/            # 用例层：admin/mcp 用例接口
├── mcp-gateway-agent-domain/          # 领域层：核心业务（agent/session/gateway/protocol/auth）
├── mcp-gateway-agent-infrastructure/ # 基础设施层：DAO、Redis、GenericHttpGateway、可观测性
├── mcp-gateway-agent-trigger/         # 触发器层：HTTP Controller、异常处理
└── mcp-gateway-agent-app/             # 应用层：启动配置、智能体装配、YAML 配置
```

### MCP 工具调用链路

```
MCP Client
  │  SSE 连接 → /api-gateway/{gatewayId}/mcp/sse
  ▼
McpSessionService.createMcpSession()      # 建立会话
  │
MCP Client 发送 JSON-RPC 消息
  │  POST /api-gateway/{gatewayId}/mcp/sse?sessionId=
  ▼
McpMessageService.handleMessage()
  │  解析 method（tools/call）
  ▼
GenericHttpGateway                        # 按 protocolId 找到 HTTP 协议配置
  │  字段映射：MCP 入参 → HTTP 请求（headers/url/method/body）
  ▼
业务系统（agent-add-oil 等）
  │
  ▼
ObservabilityHelper.reportToolCall()      # 上报 traceId + toolName + 耗时 + 状态
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
| Spring Boot | 3.4.3 | 应用框架 |
| Java | 17 | 编程语言 |
| Google ADK | 0.5.0 | Agent Development Kit |
| Spring AI | 1.1.0-M3 | AI 集成框架 |
| LangChain4j | 1.4.0 | LLM 应用框架 |
| spring-ai-agent-utils | 0.4.2 | Agent 工具 |
| xfg-wrench-design-framework | 3.0.0 | 设计模式脚手架 |

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
# 查询智能体列表
curl http://localhost:8777/api/v1/query_ai_agent_config_list

# 建立 MCP SSE 连接
curl "http://localhost:8777/api-gateway/gateway_001/mcp/sse?api_key=<your-api-key>"

# 创建会话
curl -X POST http://localhost:8777/api/v1/create_session \
     -H "Content-Type: application/json" \
     -d '{"agentId":"<agentId>","userId":"<userId>"}'
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
│       ├── gateway/             # GenericHttpGateway 协议执行
│       ├── redis/
│       └── utils/                # ObservabilityHelper、TraceContext
├── mcp-gateway-agent-trigger/
│   └── src/main/java/cn/chyuan/ai/trigger/http/
│       ├── McpGatewayController.java    # MCP SSE 网关
│       ├── AgentServiceController.java  # Agent 对话
│       ├── AdminController.java         # 运营管理
│       └── GlobalExceptionHandler.java
├── mcp-gateway-agent-app/
│   └── src/main/resources/
│       ├── agent/*.yml           # 智能体装配配置
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
