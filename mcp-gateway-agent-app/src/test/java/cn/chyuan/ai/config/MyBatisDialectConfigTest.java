package cn.chyuan.ai.config;

import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 双方言 databaseId 识别测试（工单 0122：PostgreSQL/MySQL 产品名映射，未知产品回退 null）。 */
class MyBatisDialectConfigTest {

    @Test
    void shouldMapProductNamesToDatabaseIds() throws SQLException {
        DatabaseIdProvider provider = new MyBatisDialectConfig().databaseIdProvider();
        assertEquals("postgresql", provider.getDatabaseId(dataSource("PostgreSQL")));
        assertEquals("mysql", provider.getDatabaseId(dataSource("MySQL")));
    }

    @Test
    void shouldReturnNullForUnknownProduct() throws SQLException {
        DatabaseIdProvider provider = new MyBatisDialectConfig().databaseIdProvider();
        assertNull(provider.getDatabaseId(dataSource("Oracle")));
    }

    /** VendorDatabaseIdProvider 经 DataSource→Connection→MetaData 取产品名，按该链路 mock。 */
    private DataSource dataSource(String productName) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn(productName);
        return dataSource;
    }
}
