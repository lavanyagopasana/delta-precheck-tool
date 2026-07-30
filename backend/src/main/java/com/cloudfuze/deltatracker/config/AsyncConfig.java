package com.cloudfuze.deltatracker.config;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

// Enables @Async so slow side effects (currently: SMTP notification emails) run on a background
// thread pool instead of the request thread. Before this, submit()/approve() blocked for the full
// SMTP round-trip to Office 365 -- several seconds -- while also holding the DB transaction open.
// The pool is bounded (queue + caller-runs fallback) so a burst of emails can never spawn unbounded
// threads; if the queue fills, the submitting request runs the task itself rather than dropping it.
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    public static final String EMAIL_EXECUTOR = "emailExecutor";

    @Bean(name = EMAIL_EXECUTOR)
    public Executor emailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("email-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        // Let the app shut down cleanly without cutting off an in-flight send mid-connection.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return emailExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler();
    }
}
