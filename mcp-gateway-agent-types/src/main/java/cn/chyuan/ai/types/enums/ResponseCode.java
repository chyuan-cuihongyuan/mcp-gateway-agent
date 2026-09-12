package cn.chyuan.ai.types.enums;

public enum ResponseCode {

    SUCCESS("0000", "成功"),
    UN_ERROR("0001", "未知失败"),
    ILLEGAL_PARAMETER("0002", "非法参数"),
    METHOD_NOT_FOUND("0003", "未找到方法"),
    ENUM_NOT_FOUND("0004", "未找到枚举"),
    DB_UPDATE_FAIL("0005", "数据库更新失败"),

    AUTH_ERROR_EXPIRE_TIME("1001", "网关服务认证过期"),
    AUTH_ERROR_RATE_LIMIT("1002", "网关请求速率限制"),

    E0001("E0001", "智能体ID不存在"),
    E0002("E0002", "智能体MCP配置不在可加载范围"),
    RESPONSE_ERROR("0006", "响应错误"),
    // SELFLOOP2 loop-219：HTTP 语义错误码（405/415 精确映射，审计路线 1）
    METHOD_NOT_SUPPORTED("0007", "HTTP 方法不支持"),
    MEDIA_TYPE_NOT_SUPPORTED("0008", "媒体类型不支持"),

    ;

    private final String code;
    private final String info;

    ResponseCode(String code, String info) {
        this.code = code;
        this.info = info;
    }

    public String getCode() {
        return code;
    }

    public String getInfo() {
        return info;
    }

}
