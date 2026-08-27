package cn.chyuan.ai.domain.agent.service.armory.node;

import cn.chyuan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.chyuan.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

@Slf4j
@Service
public class AiApiNode extends AbstractArmorySupport {

    /** 官方 openai-java SDK 固定在 baseUrl 后拼接的对话补全路径后缀 */
    private static final String CHAT_COMPLETIONS_SUFFIX = "chat/completions";

    @Resource
    private ChatModelNode chatModelNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AiApiNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.AiApi aiApiConfig = aiAgentConfigTableVO.getModule().getAiApi();

        OpenAIClient openAIClient = OpenAIOkHttpClient.builder()
                .baseUrl(resolveBaseUrl(aiApiConfig))
                .apiKey(aiApiConfig.getApiKey())
                .build();

        dynamicContext.setOpenAIClient(openAIClient);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return chatModelNode;
    }

    /**
     * Spring AI 2.0 起 openai 模块基于官方 openai-java SDK，客户端只接受 baseUrl，
     * 固定在其后拼接 /chat/completions。旧配置为 baseUrl + completionsPath 两段式，
     * 此处将两段折叠为等价的官方 SDK baseUrl（去掉末尾的 chat/completions 后缀），
     * 保持各 OpenAI 兼容服务商的实际请求地址不变。
     *
     * <p>旧实现中的 octet-stream RestClient 兼容 hack 随官方 SDK 自行解析响应而移除；
     * embeddingsPath 因网关仅构建对话模型不再消费。
     */
    private String resolveBaseUrl(AiAgentConfigTableVO.Module.AiApi aiApiConfig) {
        String baseUrl = StringUtils.removeEnd(StringUtils.trimToEmpty(aiApiConfig.getBaseUrl()), "/");
        String completionsPath = StringUtils.isNotBlank(aiApiConfig.getCompletionsPath())
                ? aiApiConfig.getCompletionsPath()
                : "v1/chat/completions";

        if (completionsPath.endsWith(CHAT_COMPLETIONS_SUFFIX)) {
            String prefix = StringUtils.removeEnd(
                    completionsPath.substring(0, completionsPath.length() - CHAT_COMPLETIONS_SUFFIX.length()), "/");
            return prefix.isEmpty() ? baseUrl : baseUrl + "/" + prefix;
        }

        log.warn("completions-path [{}] 不以 chat/completions 结尾，官方 openai-java SDK 不支持自定义补全路径，直接使用 base-url [{}]",
                completionsPath, baseUrl);
        return baseUrl;
    }

}
