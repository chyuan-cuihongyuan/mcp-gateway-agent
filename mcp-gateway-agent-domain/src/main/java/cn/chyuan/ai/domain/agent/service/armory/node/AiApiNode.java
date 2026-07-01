package cn.chyuan.ai.domain.agent.service.armory.node;

import cn.chyuan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.chyuan.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AiApiNode extends AbstractArmorySupport {

    @Resource
    private ChatModelNode chatModelNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AiApiNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.AiApi aiApiConfig = aiAgentConfigTableVO.getModule().getAiApi();

        // 部分 OpenAI 兼容服务商会用 application/octet-stream 返回 JSON，默认 Jackson 转换器
        // 仅支持 application/json，补充支持 application/octet-stream 以避免反序列化失败。
        RestClient.Builder restClientBuilder = RestClient.builder()
                .messageConverters(this::augmentJacksonConverterForOctetStream);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(aiApiConfig.getBaseUrl())
                .apiKey(aiApiConfig.getApiKey())
                .completionsPath(StringUtils.isNotBlank(aiApiConfig.getCompletionsPath()) ? aiApiConfig.getCompletionsPath() : "v1/chat/completions")
                .embeddingsPath(StringUtils.isNotBlank(aiApiConfig.getEmbeddingsPath()) ? aiApiConfig.getEmbeddingsPath() : "v1/embeddings")
                .restClientBuilder(restClientBuilder)
                .build();

        dynamicContext.setOpenAiApi(openAiApi);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return chatModelNode;
    }

    /**
     * 将 RestClient 默认的 Jackson 转换器替换为同时支持 application/octet-stream 的副本，
     * 以兼容用 octet-stream 返回 JSON 的 OpenAI 兼容服务商。复用原转换器的 ObjectMapper，
     * 避免影响其它请求的 JSON 解析行为，且不修改共享实例。
     */
    private void augmentJacksonConverterForOctetStream(List<HttpMessageConverter<?>> converters) {
        for (int i = 0; i < converters.size(); i++) {
            HttpMessageConverter<?> converter = converters.get(i);
            if (converter instanceof MappingJackson2HttpMessageConverter defaultJackson) {
                MappingJackson2HttpMessageConverter octetStreamAware =
                        new MappingJackson2HttpMessageConverter(defaultJackson.getObjectMapper());
                List<MediaType> mediaTypes = new ArrayList<>(defaultJackson.getSupportedMediaTypes());
                if (!mediaTypes.contains(MediaType.APPLICATION_OCTET_STREAM)) {
                    mediaTypes.add(MediaType.APPLICATION_OCTET_STREAM);
                }
                octetStreamAware.setSupportedMediaTypes(mediaTypes);
                converters.set(i, octetStreamAware);
                return;
            }
        }
    }

}
