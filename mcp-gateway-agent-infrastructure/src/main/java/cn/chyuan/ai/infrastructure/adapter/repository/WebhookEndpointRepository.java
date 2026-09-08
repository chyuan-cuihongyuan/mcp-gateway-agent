package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.governance.adapter.repository.IWebhookEndpointRepository;
import cn.chyuan.ai.domain.governance.model.valobj.WebhookEndpointVO;
import cn.chyuan.ai.infrastructure.dao.IWebhookEndpointDao;
import cn.chyuan.ai.infrastructure.dao.po.McpWebhookEndpointPO;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

/**
 * 治理告警 webhook 端点仓储实现（工单 0051）
 *
 * @author chyuan
 */
@Repository
public class WebhookEndpointRepository implements IWebhookEndpointRepository {

    @Resource
    private IWebhookEndpointDao dao;

    @Override
    public Long insert(WebhookEndpointVO endpoint) {
        McpWebhookEndpointPO po = toPo(endpoint);
        dao.insert(po);
        return po.getId();
    }

    @Override
    public boolean update(WebhookEndpointVO endpoint) {
        return dao.update(toPo(endpoint)) > 0;
    }

    @Override
    public boolean deleteById(Long id) {
        return dao.deleteById(id) > 0;
    }

    @Override
    public WebhookEndpointVO findById(Long id) {
        return toVo(dao.queryById(id));
    }

    @Override
    public List<WebhookEndpointVO> findAll() {
        return dao.queryAll().stream().map(this::toVo).toList();
    }

    @Override
    public List<WebhookEndpointVO> findEnabled() {
        List<McpWebhookEndpointPO> list = dao.queryEnabled();
        return list == null ? Collections.emptyList() : list.stream().map(this::toVo).toList();
    }

    private McpWebhookEndpointPO toPo(WebhookEndpointVO vo) {
        return McpWebhookEndpointPO.builder()
                .id(vo.getId())
                .name(vo.getName())
                .url(vo.getUrl())
                .events(vo.getEvents() == null || vo.getEvents().isEmpty() ? null : JSON.toJSONString(vo.getEvents()))
                .secret(vo.getSecret())
                .enabled(vo.getEnabled())
                .build();
    }

    private WebhookEndpointVO toVo(McpWebhookEndpointPO po) {
        if (po == null) {
            return null;
        }
        List<String> events = StringUtils.isBlank(po.getEvents())
                ? Collections.emptyList()
                : JSON.parseArray(po.getEvents(), String.class);
        return WebhookEndpointVO.builder()
                .id(po.getId())
                .name(po.getName())
                .url(po.getUrl())
                .events(events)
                .secret(po.getSecret())
                .enabled(po.getEnabled())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }
}
