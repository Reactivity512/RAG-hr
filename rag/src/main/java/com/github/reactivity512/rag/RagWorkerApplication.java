package com.github.reactivity512.rag;

import java.util.concurrent.CountDownLatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Точка входа worker-сервиса: consume задач из Redis Streams,
 * извлечение текста → разбор → эмбеддинг → upsert в Qdrant.
 *
 * <p>Web-сервер не поднимается: это фоновый процесс.
 *
 * <p>Важно: {@code SpringApplication.run} для не-web приложения возвращается
 * сразу после старта контекста, а все рабочие потоки — демоны. Поэтому главный
 * поток удерживается на {@link CountDownLatch}, иначе JVM завершилась бы сразу
 * после запуска. Освобождение защёлки происходит в shutdown hook.
 *
 * <p>Запуск: {@code java -jar rag-hr.jar --spring.profiles.active=worker}
 */
@SpringBootApplication
public class RagWorkerApplication {

    public static void main(String[] args) throws InterruptedException {
        SpringApplication application = new SpringApplication(RagWorkerApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        ConfigurableApplicationContext context = application.run(args);

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (Boolean.getBoolean("rag.worker.dump-on-shutdown")) {
                dumpThreads();
            }
            context.close();
            shutdownLatch.countDown();
        }, "worker-shutdown"));

        // Держим процесс живым до сигнала остановки
        shutdownLatch.await();
    }

    /** Диагностика: кто держит потоки в момент завершения процесса. */
    private static void dumpThreads() {
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            System.err.println("THREAD " + thread.getName() + " daemon=" + thread.isDaemon()
                    + " state=" + thread.getState());
            for (StackTraceElement element : stack) {
                System.err.println("    at " + element);
            }
        });
    }
}
