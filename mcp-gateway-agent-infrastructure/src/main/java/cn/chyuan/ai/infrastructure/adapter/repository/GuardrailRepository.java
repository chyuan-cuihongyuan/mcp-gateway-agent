package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.governance.adapter.repository.IGuardrailRepository;
import cn.chyuan.ai.domain.governance.model.valobj.GuardrailVO;
import cn.chyuan.ai.infrastructure.dao.IGuardrailDao;
import cn.chyuan.ai.infrastructure.dao.po.McpGuardrailPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 治理护栏仓储实现（工单 0091）
 *
 * @author chyuan
 */
@Repository
public class GuardrailRepository implements IGuardrailRepository {

    @Resource
    private IGuardrailDao guardrailDao;

    @Override
    public Long insert(GuardrailVO vo) {
        McpGuardrailPO po = toPo(vo);
        guardrailDao.insert(po);
        return po.getId();
    }

    @Override
    public boolean update(GuardrailVO vo) {
        return guardrailDao.update(toPo(vo)) > 0;
    }

    @Override
    public boolean deleteById(Long id) {
        return guardrailDao.deleteById(id) > 0;
    }

    @Override
    public GuardrailVO findById(Long id) {
        return toVo(guardrailDao.queryById(id));
    }

    @Override
    public GuardrailVO findByName(String name) {
        return toVo(guardrailDao.queryByName(name));
    }

    @Override
    public List<GuardrailVO> findAll() {
        return guardrailDao.queryAll().stream().map(this::toVo).toList();
    }

    private McpGuardrailPO toPo(GuardrailVO vo) {
        return McpGuardrailPO.builder()
                .id(vo.getId())
                .name(vo.getName())
                .type(vo.getType())
                .mode(vo.getMode())
                .config(vo.getConfig())
                .trafficMask(vo.getTrafficMask())
                .priority(vo.getPriority())
                .enabled(vo.getEnabled())
                .build();
    }

    private GuardrailVO toVo(McpGuardrailPO po) {
        if (po == null) {
            return null;
        }
        return GuardrailVO.builder()
                .id(po.getId())
                .name(po.getName())
                .type(po.getType())
                .mode(po.getMode())
                .config(po.getConfig())
                .trafficMask(po.getTrafficMask())
                .priority(po.getPriority())
                .enabled(po.getEnabled())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }
}
