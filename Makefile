# AUTOLOOP al-17 / 工单 1017：子仓任务入口（模式承主仓 D05）
SHELL := /bin/bash
.DEFAULT_GOAL := help
.PHONY: help
help: ## 列出全部目标
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'
.PHONY: test
test: ## domain+infrastructure 模块测试（同主仓口径）
	mvn -B -ntp -pl mcp-gateway-agent-domain,mcp-gateway-agent-infrastructure test
.PHONY: audit-permissions
audit-permissions: ## workflow 最小权限审计（AUTOLOOP al-49）
	python3 tools/audit_workflow_permissions.py

.PHONY: changelog
changelog: ## 重新生成 CHANGELOG.md
	python3 scripts/gen_changelog.py
.PHONY: clean
clean: ## 清理构建产物
	mvn -q clean
