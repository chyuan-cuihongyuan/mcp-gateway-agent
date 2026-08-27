package cn.chyuan.ai.trigger.filter;

import cn.chyuan.ai.domain.governance.model.valobj.GovernancePrincipal;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 治理面请求级主体访问工具（工单 0017）
 *
 * <p>供控制器/服务在请求线程内取回统一认证过滤器写入的主体，
 * 避免控制器接口签名携带 servlet 类型。
 *
 * @author chyuan
 */
public final class GovernanceRequestContext {

    private GovernanceRequestContext() {
        // 工具类，禁止实例化
    }

    /** 当前请求的认证主体；无过滤器上下文（如单测直连）返回 null */
    public static GovernancePrincipal currentPrincipal() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return (GovernancePrincipal) servletAttributes.getRequest()
                    .getAttribute(GovernanceAuthFilter.PRINCIPAL_ATTR);
        }
        return null;
    }
}
