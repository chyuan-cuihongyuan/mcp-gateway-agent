package cn.chyuan.ai.config;

import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

/**
 * MyBatis 双方言支持（三期 PG 双轨，工单 0122）。
 * 按数据源产品名识别 databaseId：PostgreSQL→postgresql、MySQL→mysql。
 * mapper 中以 databaseId 属性写方言分叉语句；无 databaseId 的语句为默认回退，
 * 同 id 存在匹配 databaseId 的语句时无 databaseId 版本被淘汰（MyBatis 官方语义）。
 */
@Configuration
public class MyBatisDialectConfig {

    @Bean
    public DatabaseIdProvider databaseIdProvider() {
        VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
        Properties properties = new Properties();
        properties.setProperty("PostgreSQL", "postgresql");
        properties.setProperty("MySQL", "mysql");
        provider.setProperties(properties);
        return provider;
    }
}
