#!/bin/bash
# 查询智能体列表脚本
# 用法: ./query.sh

# 业务 API 地址
API_URL="http://127.0.0.1:8091/api/v1/query_ai_agent_config_list"

# 发送请求
response=$(curl -s -X GET "$API_URL" \
  -H "Content-Type: application/json")

# 输出结果
echo "$response"
