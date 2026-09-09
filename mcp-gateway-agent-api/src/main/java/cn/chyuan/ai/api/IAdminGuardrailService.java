package cn.chyuan.ai.api;

import cn.chyuan.ai.api.dto.GuardrailDTO;

import java.util.List;

/**
 * 治理护栏管理端口（工单 0091）
 *
 * @author chyuan
 */
public interface IAdminGuardrailService {

    List<GuardrailDTO> listGuardrails();

    GuardrailDTO getGuardrail(Long id);

    GuardrailDTO createGuardrail(GuardrailDTO dto);

    GuardrailDTO updateGuardrail(Long id, GuardrailDTO dto);

    void deleteGuardrail(Long id);
}
