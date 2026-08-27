package cn.chyuan.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 虚拟密钥↔网关授权 DAO（工单 0017）
 */
@Mapper
public interface IVirtualKeyGatewayDao {

    int insertIgnore(@Param("keyId") Long keyId, @Param("gatewayId") String gatewayId);

    int delete(@Param("keyId") Long keyId, @Param("gatewayId") String gatewayId);

    Integer countGrant(@Param("keyId") Long keyId, @Param("gatewayId") String gatewayId);

    java.util.List<String> queryGatewaysByKeyId(@Param("keyId") Long keyId);

    Long queryKeyIdByHash(@Param("apiKeyHash") String apiKeyHash);
}
