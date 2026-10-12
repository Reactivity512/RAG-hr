package com.github.reactivity512.rag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Удержание процесса воркера.
 *
 * <p>Воркер — не-web приложение: {@code SpringApplication.run} возвращается
 * сразу после старта контекста, а рабочие потоки (цикл очереди, Lettuce/Jedis)
 * демонические. Без не-демон потока JVM завершается сразу после запуска,
 * и задачи никогда не обрабатываются. Этот бин держит процесс живым до
 * остановки контейнера.
 *
 * <p>Активен только когда включён разбор очереди, то есть в профиле worker.
 */
@Configuration
@ConditionalOnProperty(name = "rag.queue.enabled", havingValue = "true")
public class WorkerKeepAliveConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkerKeepAliveConfig.class);

    @Bean
    public WorkerKeepAlive workerKeepAlive() {
        WorkerKeepAlive keepAlive = new WorkerKeepAlive();
        keepAlive.start();
        log.info("Поток удержания воркера запущен");
        return keepAlive;
    }

    /** Не-демон поток, который держит JVM живой. */
    public static class WorkerKeepAlive {

        private volatile boolean running = true;
        private Thread thread;

        void start() {
            thread = new Thread(() -> {
                while (running) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "worker-keep-alive");
            thread.setDaemon(false);
            thread.start();
        }

        /** Останавливает удержание (при закрытии контекста). */
        @jakarta.annotation.PreDestroy
        public void stop() {
            running = false;
            if (thread != null) {
                thread.interrupt();
            }
        }
    }
}
