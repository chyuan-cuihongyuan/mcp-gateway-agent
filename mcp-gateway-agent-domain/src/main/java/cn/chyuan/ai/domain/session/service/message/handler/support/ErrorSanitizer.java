package cn.chyuan.ai.domain.session.service.message.handler.support;

import cn.chyuan.ai.types.exception.AppException;
import org.springframework.stereotype.Component;

/**
 * MCP 错误响应脱敏 — 分级来源信任（工单 0408/0409，SELFLOOP3 loop-305）
 * <p>
 * AppException 携带业务层可控文案（info 字段），直接透出；info 为空时兜底。
 * 其他 Throwable 的消息可能含内部地址/类名/SQL 片段，一律返回通用文案，
 * 细节仅保留在服务端日志与审计码中。
 */
@Component
public class ErrorSanitizer {

    private static final String FALLBACK_BUSINESS = "请求处理失败";
    private static final String GENERIC_UNEXPECTED = "工具调用失败，请稍后重试";

    /** 客户端可见的错误消息 — AppException 用 info（兜底），未知异常用通用文案 */
    public String clientMessage(Throwable e) {
        if (e instanceof AppException appException) {
            String info = appException.getInfo();
            return (info == null || info.isBlank()) ? FALLBACK_BUSINESS : info;
        }
        return GENERIC_UNEXPECTED;
    }
}
