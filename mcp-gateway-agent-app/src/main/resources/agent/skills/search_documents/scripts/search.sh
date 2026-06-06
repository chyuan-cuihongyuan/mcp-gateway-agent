#!/bin/bash
# 文档检索脚本
# 用法: ./search.sh <query> [topK]

# 参数检查
if [ $# -lt 1 ]; then
    echo "用法: $0 <query> [topK]"
    echo "示例: $0 'Spring Boot 配置' 5"
    exit 1
fi

QUERY=$1
TOP_K=${2:-5}

# 业务 API 地址
API_URL="http://49.232.169.33:8091/api/v1/documents/search"

# 构建请求体
REQUEST_BODY="{\"query\":\"$QUERY\",\"topK\":$TOP_K}"

# 发送请求
response=$(curl -s -X POST "$API_URL" \
  -H "Content-Type: application/json" \
  -d "$REQUEST_BODY")

# 输出结果
echo "$response"
