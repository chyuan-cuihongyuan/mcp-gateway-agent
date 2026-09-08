package cn.chyuan.ai.infrastructure.externalattach;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 被动熔断失败三分类测试（工单 0059）
 */
@DisplayName("被动熔断失败分类测试")
public class CircuitFailureClassificationTest {

    @Test
    @DisplayName("分类 — 超时/连接/其余（上游错误）")
    public void testClassification() {
        assertEquals("fail_timeout", ExternalMcpAttachRegistry.classifyFailure(
                new RuntimeException("wrap", new HttpTimeoutException("timed out"))));
        assertEquals("fail_timeout", ExternalMcpAttachRegistry.classifyFailure(
                new TimeoutException("request timed out")));

        assertEquals("fail_connect", ExternalMcpAttachRegistry.classifyFailure(
                new RuntimeException("wrap", new ConnectException("Connection refused"))));
        assertEquals("fail_connect", ExternalMcpAttachRegistry.classifyFailure(
                new RuntimeException("connect failed")));

        assertEquals("fail_http", ExternalMcpAttachRegistry.classifyFailure(
                new RuntimeException("MCP error -32603: internal")));
        assertEquals("fail_http", ExternalMcpAttachRegistry.classifyFailure(null));
    }
}
