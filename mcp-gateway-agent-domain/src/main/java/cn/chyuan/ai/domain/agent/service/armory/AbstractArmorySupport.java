package cn.chyuan.ai.domain.agent.service.armory;

import cn.chyuan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.chyuan.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.chyuan.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.SequentialAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;

import jakarta.annotation.Resource;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public abstract class AbstractArmorySupport extends AbstractMultiThreadStrategyRouter<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> {

    protected final Logger log = LoggerFactory.getLogger(AbstractArmorySupport.class);

    @Resource
    protected ApplicationContext applicationContext;

    @Override
    protected void multiThread(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }

    /**
     * 通用的Bean注册方法
     *
     * @param beanName  Bean名称
     * @param beanClass Bean类型
     * @param <T>       Bean类型
     */
    protected synchronized <T> void registerBean(String beanName, Class<T> beanClass, T beanInstance) {
        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();

        // 注册Bean
        BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder.genericBeanDefinition(beanClass, () -> beanInstance);
        BeanDefinition beanDefinition = beanDefinitionBuilder.getRawBeanDefinition();
        beanDefinition.setScope(BeanDefinition.SCOPE_SINGLETON);

        // 如果Bean已存在，先移�?
        if (beanFactory.containsBeanDefinition(beanName)) {
            beanFactory.removeBeanDefinition(beanName);
        }

        // 注册新的Bean
        beanFactory.registerBeanDefinition(beanName, beanDefinition);

        log.info("成功注册Bean: {}", beanName);
    }

    protected <T> T getBean(String beanName) {
        return (T) applicationContext.getBean(beanName);
    }

    /**
     * 通用降级装配：当高级工作流（Replan/Reflexion）配置不完整或子 agent 缺失时，
     * 降级为 SequentialAgent 顺序执行，保证功能可用性。
     *
     * <p>供 Replan/Reflexion 等高级工作流节点在子 agent 配置不完整时复用，避免重复实现降级逻辑（DRY）。
     *
     * @param requestParameter     装配请求
     * @param currentAgentWorkflow 当前工作流配置
     * @param dynamicContext       装配上下文
     * @return 路由下一节点结果
     */
    protected AiAgentRegisterVO buildFallbackSequential(ArmoryCommandEntity requestParameter,
                                                       AiAgentConfigTableVO.Module.AgentWorkflow currentAgentWorkflow,
                                                       DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        java.util.List<BaseAgent> subs = dynamicContext.queryAgentList(currentAgentWorkflow.getSubAgents());
        SequentialAgent fallback = SequentialAgent.builder()
                .name(currentAgentWorkflow.getName())
                .description(currentAgentWorkflow.getDescription())
                .subAgents(subs)
                .build();
        dynamicContext.getAgentGroup().put(currentAgentWorkflow.getName(), fallback);
        log.warn("工作流[{}]降级为顺序执行: type={}, subAgents={}",
                currentAgentWorkflow.getName(), currentAgentWorkflow.getType(), currentAgentWorkflow.getSubAgents());
        return router(requestParameter, dynamicContext);
    }

}
