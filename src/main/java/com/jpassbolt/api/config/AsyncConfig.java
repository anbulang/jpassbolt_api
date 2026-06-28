package com.jpassbolt.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Enables {@code @Async} and provides a dedicated executor for outbound email.
 *
 * <p>The event-driven notification redactors (package
 * {@code service.email.redactor}, added in the next phase) run on
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} and are annotated
 * {@code @Async("mailExecutor")} so SMTP latency never blocks the request thread
 * that committed the business transaction. Delivery is a best-effort side effect:
 * by the time a redactor runs, the HTTP response has already been produced.</p>
 *
 * <p>The bean is explicitly named {@code mailExecutor} and redactors always
 * qualify {@code @Async} with that name, so it never competes with Spring Boot's
 * auto-configured {@code applicationTaskExecutor} (the default executor used by
 * any unqualified {@code @Async}).</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("mailExecutor")
    public Executor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("mail-");
        // Drain in-flight notifications on shutdown rather than dropping them.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
