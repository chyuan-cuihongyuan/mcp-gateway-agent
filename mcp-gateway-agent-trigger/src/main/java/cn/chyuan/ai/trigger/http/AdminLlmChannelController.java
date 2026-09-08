package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.IAdminLlmChannelService;
import cn.chyuan.ai.api.dto.LlmChannelRequestDTO;
import cn.chyuan.ai.api.dto.LlmChannelResponseDTO;
import cn.chyuan.ai.api.response.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * admin LLM 渠道控制台接口（工单 0063：/admin/v1/llm-channels）
 *
 * @author chyuan
 */
@Slf4j
@RestController
@RequestMapping("/admin/v1/llm-channels")
public class AdminLlmChannelController {

    @Resource
    private IAdminLlmChannelService adminLlmChannelService;

    @PostMapping
    public Response<LlmChannelResponseDTO> createChannel(@RequestBody LlmChannelRequestDTO requestDTO) {
        return Response.success(adminLlmChannelService.createChannel(requestDTO));
    }

    @GetMapping
    public Response<List<LlmChannelResponseDTO>> listChannels() {
        return Response.success(adminLlmChannelService.listChannels());
    }

    @GetMapping("/{id}")
    public Response<LlmChannelResponseDTO> getChannel(@PathVariable Long id) {
        return Response.success(adminLlmChannelService.getChannel(id));
    }

    @PutMapping("/{id}")
    public Response<LlmChannelResponseDTO> updateChannel(@PathVariable Long id,
            @RequestBody LlmChannelRequestDTO requestDTO) {
        return Response.success(adminLlmChannelService.updateChannel(id, requestDTO));
    }

    @DeleteMapping("/{id}")
    public Response<Void> deleteChannel(@PathVariable Long id) {
        adminLlmChannelService.deleteChannel(id);
        return Response.success(null);
    }

    /** 连通性测试（base_url/models GET），返回 {"elapsedMs":N} */
    @PostMapping("/{id}/test")
    public Response<Map<String, Object>> testChannel(@PathVariable Long id) {
        long elapsed = adminLlmChannelService.testChannel(id);
        return Response.success(Map.of("elapsedMs", elapsed));
    }
}
