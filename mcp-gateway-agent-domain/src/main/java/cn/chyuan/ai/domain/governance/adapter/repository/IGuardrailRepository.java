package cn.chyuan.ai.domain.governance.adapter.repository;

import cn.chyuan.ai.domain.governance.model.valobj.GuardrailVO;

import java.util.List;

/**
 * 治理护栏仓储端口（工单 0091）
 *
 * @author chyuan
 */
public interface IGuardrailRepository {

    Long insert(GuardrailVO vo);

    boolean update(GuardrailVO vo);

    boolean deleteById(Long id);

    GuardrailVO findById(Long id);

    GuardrailVO findByName(String name);

    /** 全部护栏（执行链按 priority 排序消费；含停用——服务侧过滤） */
    List<GuardrailVO> findAll();
}
