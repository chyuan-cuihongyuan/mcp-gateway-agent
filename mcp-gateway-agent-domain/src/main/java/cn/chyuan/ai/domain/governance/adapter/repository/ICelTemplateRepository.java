package cn.chyuan.ai.domain.governance.adapter.repository;

import cn.chyuan.ai.domain.governance.model.valobj.CelRuleTemplateVO;

import java.util.List;

/**
 * CEL 规则模板仓储端口（工单 0057）
 *
 * @author chyuan
 */
public interface ICelTemplateRepository {

    Long insert(CelRuleTemplateVO template);

    boolean update(CelRuleTemplateVO template);

    boolean deleteById(Long id);

    CelRuleTemplateVO findById(Long id);

    CelRuleTemplateVO findByCode(String code);

    List<CelRuleTemplateVO> findAll();

    /** 内置模板幂等种子（code 已存在则跳过并返回 false） */
    boolean seedBuiltinIfAbsent(CelRuleTemplateVO template);
}
