package cn.chyuan.ai.infrastructure.adapter.repository;

import cn.chyuan.ai.domain.governance.adapter.repository.ICelTemplateRepository;
import cn.chyuan.ai.domain.governance.model.valobj.CelRuleTemplateVO;
import cn.chyuan.ai.infrastructure.dao.ICelRuleTemplateDao;
import cn.chyuan.ai.infrastructure.dao.po.McpCelRuleTemplatePO;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * CEL 规则模板仓储实现（工单 0057）
 *
 * @author chyuan
 */
@Repository
public class CelTemplateRepository implements ICelTemplateRepository {

    @Resource
    private ICelRuleTemplateDao dao;

    @Override
    public Long insert(CelRuleTemplateVO template) {
        McpCelRuleTemplatePO po = toPo(template);
        try {
            dao.insert(po);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("模板编码已存在: " + template.getCode(), e);
        }
        return po.getId();
    }

    @Override
    public boolean update(CelRuleTemplateVO template) {
        return dao.update(toPo(template)) > 0;
    }

    @Override
    public boolean deleteById(Long id) {
        return dao.deleteById(id) > 0;
    }

    @Override
    public CelRuleTemplateVO findById(Long id) {
        return toVo(dao.queryById(id));
    }

    @Override
    public CelRuleTemplateVO findByCode(String code) {
        return toVo(dao.queryByCode(code));
    }

    @Override
    public List<CelRuleTemplateVO> findAll() {
        return dao.queryAll().stream().map(this::toVo).toList();
    }

    @Override
    public boolean seedBuiltinIfAbsent(CelRuleTemplateVO template) {
        return dao.insertIgnore(toPo(template)) > 0;
    }

    private McpCelRuleTemplatePO toPo(CelRuleTemplateVO vo) {
        return McpCelRuleTemplatePO.builder()
                .id(vo.getId())
                .code(vo.getCode())
                .name(vo.getName())
                .expression(vo.getExpression())
                .variablesDesc(vo.getVariablesDesc())
                .builtin(vo.getBuiltin())
                .build();
    }

    private CelRuleTemplateVO toVo(McpCelRuleTemplatePO po) {
        if (po == null) {
            return null;
        }
        return CelRuleTemplateVO.builder()
                .id(po.getId())
                .code(po.getCode())
                .name(po.getName())
                .expression(po.getExpression())
                .variablesDesc(po.getVariablesDesc())
                .builtin(po.getBuiltin())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }
}
