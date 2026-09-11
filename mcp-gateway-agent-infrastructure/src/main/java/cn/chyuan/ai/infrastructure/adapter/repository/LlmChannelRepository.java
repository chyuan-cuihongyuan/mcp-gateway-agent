package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.llmchannel.adapter.repository.ILlmChannelRepository;
import cn.chyuan.ai.domain.llmchannel.model.valobj.LlmChannelVO;
import cn.chyuan.ai.infrastructure.dao.ILlmChannelDao;
import cn.chyuan.ai.infrastructure.dao.po.McpLlmChannelPO;
import cn.chyuan.ai.infrastructure.utils.CredentialCipher;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

/**
 * LLM 渠道仓储实现（工单 0063；credential 写侧密文/读侧探测解密——0062 口径）
 *
 * @author chyuan
 */
@Repository
public class LlmChannelRepository implements ILlmChannelRepository {

    @Resource
    private ILlmChannelDao dao;

    @Resource
    private CredentialCipher credentialCipher;

    @Override
    public Long insert(LlmChannelVO channel) {
        McpLlmChannelPO po = toPo(channel);
        dao.insert(po);
        return po.getId();
    }

    @Override
    public boolean update(LlmChannelVO channel) {
        return dao.update(toPo(channel)) > 0;
    }

    @Override
    public boolean deleteById(Long id) {
        return dao.deleteById(id) > 0;
    }

    @Override
    public LlmChannelVO findById(Long id) {
        return toVo(dao.queryById(id));
    }

    @Override
    public LlmChannelVO findByName(String name) {
        return toVo(dao.queryByName(name));
    }

    @Override
    public List<LlmChannelVO> findAll() {
        return dao.queryAll().stream().map(this::toVo).toList();
    }

    @Override
    public List<LlmChannelVO> findEnabled() {
        List<McpLlmChannelPO> list = dao.queryEnabled();
        return list == null ? Collections.emptyList() : list.stream().map(this::toVo).toList();
    }

    @Override
    public void updateBalance(Long id, String balance, java.util.Date balanceTime) {
        dao.updateBalance(id, balance, balanceTime);
    }

    private McpLlmChannelPO toPo(LlmChannelVO vo) {
        return McpLlmChannelPO.builder()
                .id(vo.getId()).name(vo.getName()).baseUrl(vo.getBaseUrl())
                .credential(credentialCipher.encrypt(vo.getCredential()))
                .models(vo.getModels()).modelMapping(vo.getModelMapping())
                .weight(vo.getWeight() == null ? 1 : vo.getWeight())
                .priority(vo.getPriority() == null ? 0 : vo.getPriority())
                .channelGroup(vo.getChannelGroup())
                .status(vo.getStatus() == null ? 1 : vo.getStatus())
                .timeoutMs(vo.getTimeoutMs() == null ? 60_000 : vo.getTimeoutMs())
                .maxBodyBytes(vo.getMaxBodyBytes())
                .maxConcurrency(vo.getMaxConcurrency())
                .contextLimitTokens(vo.getContextLimitTokens())
                .numRetries(vo.getNumRetries())
                .retryBackoffMs(vo.getRetryBackoffMs())
                .retryOn(vo.getRetryOn())
                .fallbackChannelId(vo.getFallbackChannelId())
                .balanceProbeUrl(vo.getBalanceProbeUrl()).balanceJsonPath(vo.getBalanceJsonPath())
                .balance(vo.getBalance()).balanceTime(vo.getBalanceTime())
                .build();
    }

    private LlmChannelVO toVo(McpLlmChannelPO po) {
        if (po == null) {
            return null;
        }
        return LlmChannelVO.builder()
                .id(po.getId()).name(po.getName()).baseUrl(po.getBaseUrl())
                .credential(credentialCipher.decrypt(po.getCredential()))
                .models(po.getModels()).modelMapping(po.getModelMapping())
                .weight(po.getWeight()).priority(po.getPriority()).status(po.getStatus())
                .channelGroup(po.getChannelGroup())
                .timeoutMs(po.getTimeoutMs()).maxBodyBytes(po.getMaxBodyBytes())
                .maxConcurrency(po.getMaxConcurrency()).contextLimitTokens(po.getContextLimitTokens())
                .testTime(po.getTestTime())
                .numRetries(po.getNumRetries()).retryBackoffMs(po.getRetryBackoffMs()).retryOn(po.getRetryOn())
                .fallbackChannelId(po.getFallbackChannelId())
                .balanceProbeUrl(po.getBalanceProbeUrl()).balanceJsonPath(po.getBalanceJsonPath())
                .balance(po.getBalance()).balanceTime(po.getBalanceTime())
                .responseTimeMs(po.getResponseTimeMs())
                .createTime(po.getCreateTime()).updateTime(po.getUpdateTime())
                .build();
    }
}
