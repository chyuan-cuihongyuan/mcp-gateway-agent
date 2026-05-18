package cn.chyuan.ai.domain.protocol.service.analysis.strategy;

import cn.chyuan.ai.domain.protocol.model.valobj.http.HTTPProtocolVO;
import com.alibaba.fastjson.JSONObject;

import java.util.List;

/**
 * 协议解析策略接口
 *
 * @author chyuan
 */
public interface IProtocolAnalysisStrategy {

    void doAnalysis(JSONObject operation, JSONObject definitions, List<HTTPProtocolVO.ProtocolMapping> mappings);

}
