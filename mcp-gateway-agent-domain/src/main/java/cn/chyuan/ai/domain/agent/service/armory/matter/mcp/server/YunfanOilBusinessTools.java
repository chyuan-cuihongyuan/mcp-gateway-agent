package cn.chyuan.ai.domain.agent.service.armory.matter.mcp.server;

import cn.chyuan.ai.domain.session.adapter.port.ISessionPort;
import cn.chyuan.ai.domain.session.adapter.repository.ISessionRepository;
import cn.chyuan.ai.domain.session.model.valobj.gateway.McpToolProtocolConfigVO;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 云帆加油业务工具。
 * <p>
 * 供网关业务智能体直接调用，内部复用 MCP 网关已登记的 HTTP 协议配置。
 */
@Slf4j
@Service
public class YunfanOilBusinessTools {

    private static final String GATEWAY_ID = "gateway_business";

    @Resource
    private ISessionRepository repository;

    @Resource
    private ISessionPort sessionPort;

    @Tool(name = "agent_order_query", description = "根据订单ID查询订单详细信息，包括油站信息、金额、支付方式、订单状态、退款申请状态和开票方。")
    public String queryOrder(@ToolParam(description = "订单ID，例如 OD012026052515030031863") String orderId) {
        return callTool("agent_order_query", params("orderId", orderId));
    }

    @Tool(name = "agent_order_status", description = "根据订单ID查询订单简要状态，包括订单状态、退款申请状态、开票方、是否可退款和是否可开票。")
    public String queryOrderStatus(@ToolParam(description = "订单ID，例如 OD012026052515030031863") String orderId) {
        return callTool("agent_order_status", params("orderId", orderId));
    }

    @Tool(name = "agent_order_refund_apply", description = "为已支付订单提交退款申请，需要订单ID和退款原因。")
    public String applyRefund(
            @ToolParam(description = "订单ID") String orderId,
            @ToolParam(description = "退款原因") String reason) {
        return callTool("agent_order_refund_apply", params("orderId", orderId, "reason", reason));
    }

    @Tool(name = "agent_order_refund_execute", description = "对已审核通过的退款申请执行退款操作，需要订单ID和审核确认标识。")
    public String executeRefund(
            @ToolParam(description = "订单ID") String orderId,
            @ToolParam(description = "是否审核通过") Boolean approved) {
        return callTool("agent_order_refund_execute", params("orderId", orderId, "approved", approved));
    }

    @Tool(name = "agent_invoice_query", description = "查询订单的CP开票信息。")
    public String queryInvoice(@ToolParam(description = "订单ID") String orderId) {
        return callTool("agent_invoice_query", params("orderId", orderId));
    }

    @Tool(name = "agent_invoice_info_query", description = "查询订单的高德发票信息。")
    public String queryInvoiceInfo(@ToolParam(description = "订单ID") String orderId) {
        return callTool("agent_invoice_info_query", params("orderId", orderId));
    }

    @Tool(name = "agent_invoice_submit", description = "提交订单开票信息。type 为 orderInvoice 或 gaodeInvoice，invoiceInfo 为开票信息对象。")
    public String submitInvoice(
            @ToolParam(description = "订单ID") String orderId,
            @ToolParam(description = "开票信息类型，orderInvoice 或 gaodeInvoice") String type,
            @ToolParam(description = "开票信息对象") Map<String, Object> invoiceInfo) {
        return callTool("agent_invoice_submit", params("orderId", orderId, "type", type, "invoiceInfo", invoiceInfo));
    }

    private String callTool(String toolName, Map<String, Object> params) {
        try {
            McpToolProtocolConfigVO protocolConfig = repository.queryMcpGatewayProtocolConfig(GATEWAY_ID, toolName);
            if (protocolConfig == null || protocolConfig.getHttpConfig() == null) {
                return errorResult("工具未配置: " + toolName);
            }
            Object result = sessionPort.toolCall(protocolConfig.getHttpConfig(), params);
            return String.valueOf(result);
        } catch (Exception e) {
            log.error("云帆业务工具调用失败: toolName={}, params={}", toolName, params, e);
            return errorResult("业务工具调用失败: " + e.getMessage());
        }
    }

    private Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                params.put(String.valueOf(keyValues[i]), value);
            }
        }
        return params;
    }

    private String errorResult(String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", true);
        error.put("message", message);
        return JSON.toJSONString(error);
    }
}
