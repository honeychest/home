package com.chs.springboot.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    void disabledSchedulingDoesNotRegisterScheduledProcessor() {
        contextRunner.withPropertyValues("spring.task.scheduling.enabled=false")
                .run(context -> assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class))
                        .isEmpty());
    }

    @Test
    void enabledSchedulingRegistersScheduledProcessor() {
        contextRunner.withPropertyValues("spring.task.scheduling.enabled=true")
                .run(context -> assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class))
                        .isNotEmpty());
    }
}
