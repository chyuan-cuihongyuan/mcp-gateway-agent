package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.dto.*;
import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.service.IChatService;
import cn.chyuan.ai.types.enums.ResponseCode;
import cn.chyuan.ai.types.exception.AppException;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Agent 服务控制器单元测试（MCP 网关项目）
 * <p>
 * 测试场景：
 * 1. 查询智能体配置列表（成功）
 * 2. 查询智能体配置列表（异常）
 * 3. 创建会话（成功）
 * 4. 创建会话（异常）
 * 5. 对话（成功）
 * 6. 对话（sessionId 为空时自动创建）
 * 7. 对话（异常）
 * 8. 流式对话（返回 ResponseBodyEmitter）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Agent 服务控制器测试（MCP 网关）")
public class AgentServiceControllerTest {

    @Mock
    private IChatService chatService;

    @InjectMocks
    private AgentServiceController controller;

    @Test
    @DisplayName("查询智能体配置列表 — 成功返回配置列表")
    public void testQueryAgentConfigList_Success() {
        // 准备
        AiAgentConfigTableVO.Agent agent = new AiAgentConfigTableVO.Agent();
        agent.setAgentId("200001");
        agent.setAgentName("RAG 智能问答");
        agent.setAgentDesc("基于知识库的智能问答");
        when(chatService.queryAiAgentConfigList()).thenReturn(List.of(agent));

        // 执行
        Response<List<AiAgentConfigResponseDTO>> result = controller.queryAiAgentConfigList();

        // 验证
        assertEquals(ResponseCode.SUCCESS.getCode(), result.getCode(), "响应码应为 0000");
        assertNotNull(result.getData(), "数据不应为 null");
        assertEquals(1, result.getData().size(), "应返回 1 个智能体");
        assertEquals("200001", result.getData().get(0).getAgentId(), "智能体 ID 应正确");
        assertEquals("RAG 智能问答", result.getData().get(0).getAgentName(), "智能体名称应正确");
    }

    @Test
    @DisplayName("查询智能体配置列表 — 业务异常返回错误码")
    public void testQueryAgentConfigList_AppException() {
        // 准备
        when(chatService.queryAiAgentConfigList())
                .thenThrow(new AppException(ResponseCode.UN_ERROR.getCode(), ResponseCode.UN_ERROR.getInfo()));

        // 执行
        Response<List<AiAgentConfigResponseDTO>> result = controller.queryAiAgentConfigList();

        // 验证
        assertEquals(ResponseCode.UN_ERROR.getCode(), result.getCode(), "响应码应为 0001");
        assertNull(result.getData(), "异常时数据应为 null");
    }

    @Test
    @DisplayName("查询智能体配置列表 — 系统异常返回错误码")
    public void testQueryAgentConfigList_SystemException() {
        // 准备
        when(chatService.queryAiAgentConfigList()).thenThrow(new RuntimeException("系统错误"));

        // 执行
        Response<List<AiAgentConfigResponseDTO>> result = controller.queryAiAgentConfigList();

        // 验证
        assertEquals(ResponseCode.UN_ERROR.getCode(), result.getCode(), "响应码应为 0001");
    }

    @Test
    @DisplayName("创建会话 — 成功返回 sessionId")
    public void testCreateSession_Success() {
        // 准备
        CreateSessionRequestDTO requestDTO = new CreateSessionRequestDTO();
        requestDTO.setAgentId("200001");
        requestDTO.setUserId("user-001");
        when(chatService.createSession("200001", "user-001")).thenReturn("session-abc-123");

        // 执行
        Response<CreateSessionResponseDTO> result = controller.createSession(requestDTO);

        // 验证
        assertEquals(ResponseCode.SUCCESS.getCode(), result.getCode(), "响应码应为 0000");
        assertNotNull(result.getData(), "数据不应为 null");
        assertEquals("session-abc-123", result.getData().getSessionId(), "sessionId 应正确");
        verify(chatService).createSession("200001", "user-001");
    }

    @Test
    @DisplayName("创建会话 — 异常返回错误码")
    public void testCreateSession_Exception() {
        // 准备
        CreateSessionRequestDTO requestDTO = new CreateSessionRequestDTO();
        requestDTO.setAgentId("200001");
        requestDTO.setUserId("user-001");
        when(chatService.createSession(anyString(), anyString()))
                .thenThrow(new RuntimeException("会话创建失败"));

        // 执行
        Response<CreateSessionResponseDTO> result = controller.createSession(requestDTO);

        // 验证
        assertEquals(ResponseCode.UN_ERROR.getCode(), result.getCode(), "响应码应为 0001");
    }

    @Test
    @DisplayName("对话 — 成功返回对话内容")
    public void testChat_Success() {
        // 准备
        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setAgentId("200001");
        requestDTO.setUserId("user-001");
        requestDTO.setSessionId("session-123");
        requestDTO.setMessage("你好");
        when(chatService.handleMessage("200001", "user-001", "session-123", "你好"))
                .thenReturn(List.of("你好，我是 AI 助手"));

        // 执行
        Response<ChatResponseDTO> result = controller.chat(requestDTO);

        // 验证
        assertEquals(ResponseCode.SUCCESS.getCode(), result.getCode(), "响应码应为 0000");
        assertNotNull(result.getData(), "数据不应为 null");
        assertTrue(result.getData().getContent().contains("AI 助手"), "内容应包含对话结果");
    }

    @Test
    @DisplayName("对话 — sessionId 为空时自动创建会话")
    public void testChat_AutoCreateSession() {
        // 准备
        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setAgentId("200001");
        requestDTO.setUserId("user-001");
        requestDTO.setSessionId(null);
        requestDTO.setMessage("你好");
        when(chatService.createSession("200001", "user-001")).thenReturn("auto-session-123");
        when(chatService.handleMessage("200001", "user-001", "auto-session-123", "你好"))
                .thenReturn(List.of("回复"));

        // 执行
        Response<ChatResponseDTO> result = controller.chat(requestDTO);

        // 验证
        assertEquals(ResponseCode.SUCCESS.getCode(), result.getCode(), "响应码应为 0000");
        verify(chatService).createSession("200001", "user-001");
    }

    @Test
    @DisplayName("对话 — 异常返回错误码且不崩溃")
    public void testChat_Exception() {
        // 准备
        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setAgentId("200001");
        requestDTO.setUserId("user-001");
        requestDTO.setSessionId("session-123");
        requestDTO.setMessage("你好");
        when(chatService.handleMessage(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("对话服务不可用"));

        // 执行
        Response<ChatResponseDTO> result = controller.chat(requestDTO);

        // 验证
        assertEquals(ResponseCode.UN_ERROR.getCode(), result.getCode(), "响应码应为 0001");
    }

    @Test
    @DisplayName("流式对话 — 返回 ResponseBodyEmitter")
    public void testChatStream_ReturnsEmitter() {
        // 准备
        ChatRequestDTO requestDTO = new ChatRequestDTO();
        requestDTO.setAgentId("200001");
        requestDTO.setUserId("user-001");
        requestDTO.setSessionId("session-123");
        requestDTO.setMessage("你好");

        // 创建模拟的 Flowable
        Flowable<Event> mockFlowable = Flowable.empty();
        when(chatService.handleMessageStream(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockFlowable);

        // 执行
        ResponseBodyEmitter emitter = controller.chatStream(requestDTO);

        // 验证
        assertNotNull(emitter, "应返回非空的 ResponseBodyEmitter");
    }

    @Test
    @DisplayName("GET 创建会话 — 正确转发到 POST 方法")
    public void testCreateSession_GetMethod() {
        // 准备
        when(chatService.createSession("200001", "user-001")).thenReturn("session-get-123");

        // 执行
        Response<CreateSessionResponseDTO> result = controller.createSession("200001", "user-001");

        // 验证
        assertEquals(ResponseCode.SUCCESS.getCode(), result.getCode(), "响应码应为 0000");
        assertEquals("session-get-123", result.getData().getSessionId(), "sessionId 应正确");
    }
}
