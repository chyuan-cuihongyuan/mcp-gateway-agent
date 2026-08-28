package cn.chyuan.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP 网关应用入口（纯 MCP 协议代理 + 治理平面，工单 0022：agent 宿主已删除）
 *
 * @author chyuan
 *         2026/1/20 08:23
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class);
    }

}
