package com.icbc.nbfh.service;

import com.icbc.nbfh.client.PlatformClient;
import com.icbc.nbfh.collector.RedisInfoCollector;
import com.icbc.nbfh.collector.SentinelInfoCollector;
import com.icbc.nbfh.collector.TopologyCollector;
import com.icbc.nbfh.store.LocalMetricStore;
import com.icbc.nbfh.command.FixedScriptExecutor;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.controller.AgentTaskController;
import com.icbc.nbfh.service.impl.AgentTaskServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 验证Service接口的实现唯一性以及Controller、任务服务的依赖注入。
 */
class ServiceWiringTest {

    /**
     * 扫描真实实现类，检查每个接口有且仅有一个Spring实现。
     */
    @Test
    void shouldWireExactlyOneImplementationForEachService() {

        new ApplicationContextRunner()
                .withUserConfiguration(ServiceTestConfiguration.class)
                .run(context -> {
                    assertTrue(context.isRunning());
                    Class<?>[] interfaces = {
                            AgentTaskService.class,
                            TaskResultReportService.class,
                            PrecheckHostService.class,
                            CollectService.class,
                            HeartbeatService.class,
                            RegisterService.class,
                            StartRedisService.class,
                            StopRedisService.class,
                            StartSentinelService.class,
                            StopSentinelService.class,
                            RestoreConfigService.class,
                            TopologyVerifyService.class,
                            DeployConfigService.class,
                            ConfigFileService.class,
                            DeployVersionService.class,
                            SwitchVersionService.class,
                            VersionPackageService.class
                    };
                    for (Class<?> serviceInterface : interfaces) {
                        assertEquals(1, context.getBeansOfType(serviceInterface).size(),
                                serviceInterface.getSimpleName());
                        Class<?> implementation = context.getBean(serviceInterface).getClass();
                        assertEquals(serviceInterface.getSimpleName() + "Impl",
                                implementation.getSimpleName());
                        assertNotNull(implementation.getAnnotation(Service.class));
                    }
                    assertNotNull(context.getBean(AgentTaskController.class));
                    assertNotNull(context.getBean(AgentTaskService.class));
                });
    }

    /**
     * 任务分发使用Service接口，避免直接依赖具体实现类。
     */
    @Test
    void shouldDependOnServiceInterfacesForTaskDispatch() {

        for (Field field : AgentTaskServiceImpl.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            // 只检查 service 包下的业务接口；impl 包中的辅助组件不是服务接口
            if ("com.icbc.nbfh.service".equals(type.getPackage().getName())) {
                assertTrue(type.isInterface(), field.getName());
            }
        }
    }

    /**
     * 仅加载业务服务和Controller，外部通信及脚本执行使用mock。
     */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = AgentTaskServiceImpl.class)
    @Import(AgentTaskController.class)
    static class ServiceTestConfiguration {

        /** 提供本机配置，不触发完整应用启动。 */
        @Bean
        AgentProperties agentProperties() {
            return new AgentProperties();
        }

        /** 阻止测试执行真实脚本。 */
        @Bean
        FixedScriptExecutor fixedScriptExecutor() {
            return mock(FixedScriptExecutor.class);
        }

        /** 阻止测试访问平台。 */
        @Bean
        PlatformClient platformClient() {
            return mock(PlatformClient.class);
        }

        /** 阻止测试连接Redis。 */
        @Bean
        RedisInfoCollector redisInfoCollector() {
            return mock(RedisInfoCollector.class);
        }

        /** 阻止测试连接Sentinel。 */
        @Bean
        SentinelInfoCollector sentinelInfoCollector() {
            return mock(SentinelInfoCollector.class);
        }

        /** 阻止测试读写本地采集缓存。 */
        @Bean
        LocalMetricStore localMetricStore() {
            return mock(LocalMetricStore.class);
        }

        /** 阻止测试采集真实Redis和Sentinel拓扑。 */
        @Bean
        TopologyCollector topologyCollector() {
            return mock(TopologyCollector.class);
        }
    }
}
