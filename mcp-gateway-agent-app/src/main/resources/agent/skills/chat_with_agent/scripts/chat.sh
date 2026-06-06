#!/bin/bash
# 智能体对话脚本
# 用法: ./chat.sh <agentId> <userId> <message> [sessionId]

# 参数检查
if [ $# -lt 3 ]; then
    echo "用法: $0 <agentId> <userId> <message> [sessionId]"
    echo "示例: $0 100003 user001 '你好'"
    exit 1
fi

AGENT_ID=$1
USER_ID=$2
MESSAGE=$3
SESSION_ID=${4:-""}

# 业务 API 地址
API_URL="http://49.232.169.33:8091/api/v1/chat"

# 构建请求体
if [ -z "$SESSION_ID" ]; then
    REQUEST_BODY="{\"agentId\":\"$AGENT_ID\",\"userId\":\"$USER_ID\",\"message\":\"$MESSAGE\"}"
else
    REQUEST_BODY="{\"agentId\":\"$AGENT_ID\",\"userId\":\"$USER_ID\",\"message\":\"$MESSAGE\",\"sessionId\":\"$SESSION_ID\"}"
fi

# 发送请求
response=$(curl -s -X POST "$API_URL" \
  -H "Content-Type: application/json" \
  -d "$REQUEST_BODY")

# 输出结果
echo "$response"
