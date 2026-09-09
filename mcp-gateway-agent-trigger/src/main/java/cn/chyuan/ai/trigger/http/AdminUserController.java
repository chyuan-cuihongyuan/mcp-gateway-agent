package cn.chyuan.ai.trigger.http;

import cn.chyuan.ai.api.response.Response;
import cn.chyuan.ai.cases.admin.manage.AdminUserService;
import cn.chyuan.ai.domain.governance.adapter.repository.IAdminUserRepository;
import cn.chyuan.ai.trigger.filter.AdminJwtAuthFilter;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理员账户管理接口（工单 0110：/admin/v1/users，SUPER_ADMIN 专属——矩阵承载）
 *
 * @author chyuan
 */
@Slf4j
@RestController
@RequestMapping("/admin/v1/users")
public class AdminUserController {

    @Resource
    private AdminUserService adminUserService;

    @GetMapping
    public Response<List<IAdminUserRepository.AdminUserVO>> list() {
        return Response.success(adminUserService.list());
    }

    @PostMapping
    public Response<IAdminUserRepository.AdminUserVO> create(HttpServletRequest request,
            @RequestBody Map<String, String> body) {
        return Response.success(adminUserService.create(
                operator(request),
                body.get("username"), body.get("password"), body.get("role")));
    }

    @PutMapping("/{username}/role")
    public Response<Void> updateRole(HttpServletRequest request, @PathVariable String username,
            @RequestBody Map<String, String> body) {
        adminUserService.updateRole(operator(request), username, body.get("role"));
        return Response.success(null);
    }

    @PutMapping("/{username}/password")
    public Response<Void> resetPassword(HttpServletRequest request, @PathVariable String username,
            @RequestBody Map<String, String> body) {
        adminUserService.resetPassword(operator(request), username, body.get("password"));
        return Response.success(null);
    }

    @PutMapping("/{username}/status")
    public Response<Void> updateStatus(HttpServletRequest request, @PathVariable String username,
            @RequestBody Map<String, String> body) {
        adminUserService.updateStatus(operator(request), username, body.get("status"));
        return Response.success(null);
    }

    @DeleteMapping("/{username}")
    public Response<Void> delete(HttpServletRequest request, @PathVariable String username) {
        adminUserService.delete(operator(request), username);
        return Response.success(null);
    }

    private String operator(HttpServletRequest request) {
        Object role = request.getAttribute(AdminJwtAuthFilter.ADMIN_ROLE_ATTR);
        Object user = request.getAttribute("GOVERNANCE_ADMIN_USER");
        return user == null ? "admin:" + role : String.valueOf(user);
    }
}
