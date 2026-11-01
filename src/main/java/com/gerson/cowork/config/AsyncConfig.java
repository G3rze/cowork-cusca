package com.gerson.cowork.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

	/**
	 * Notifications are I/O bound, so each one gets its own virtual thread. The concurrency limit keeps a burst
	 * of events from creating unbounded work (callers beyond it block briefly instead of piling up).
	 */
	@Bean(name = "notificationExecutor")
	Executor notificationExecutor() {
		SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("notification-");
		executor.setVirtualThreads(true);
		executor.setConcurrencyLimit(200);
		return executor;
	}
}
