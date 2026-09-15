package cn.chyuan.ai.domain.streamkernel.adapter.port;

/**
 * 工具参数 Schema 校验端口（工单 0374 AT4）：对接七期 AP2 校验内核的抽象，
 * domain 不直接依赖 toolchain 包。
 */
public interface ISchemaValidatorPort {

    /**
     * 校验工具参数 JSON。
     *
     * @param toolName    工具名
     * @param argumentsJson 参数 JSON 文本
     * @return 校验结果（valid=是否通过，error=错误路径/说明）
     */
    Result validate(String toolName, String argumentsJson);

    /** 校验结果 */
    record Result(boolean valid, String error) {
    }
}
