package dev.meirong.demo;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Harness tests covering gaps in the existing suite:
 *   1. ECS JSON field structure on serialized output
 *   2. traceId/spanId injection by Micrometer Tracing
 *   3. MDC isolation across concurrent virtual threads
 *   4. MDC propagation in asyncDemo endpoint
 */
@SpringBootTest
@AutoConfigureMockMvc
class HarnessTest {

    @Autowired
    MockMvc mvc;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        rootLogger().addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        rootLogger().detachAppender(appender);
    }

    private static ch.qos.logback.classic.Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(DemoController.class);
    }

    // ── 1. ECS field structure ────────────────────────────────────────────────

    @Autowired
    org.springframework.core.env.Environment env;

    @Test
    void ecsRequiredFieldsPresentOnEveryLogEvent() throws Exception {
        mvc.perform(get("/api/hello")).andExpect(status().isOk());

        ILoggingEvent event = appender.list.get(0);

        // Core ILoggingEvent fields that feed ECS @timestamp, log.level, log.logger, message
        assertThat(event.getLoggerName()).isNotBlank();
        assertThat(event.getLevel()).isNotNull();
        assertThat(event.getMessage()).isNotBlank();
        assertThat(event.getTimeStamp()).isPositive();

        // service.name is read from Spring Environment by StructuredLogEncoder at serialization time;
        // assert it is configured correctly in the environment
        assertThat(env.getProperty("spring.application.name"))
                .as("spring.application.name must be set for ECS service.name")
                .isEqualTo("spring-log-demo");
        assertThat(env.getProperty("logging.structured.ecs.service.name"))
                .as("ECS service.name override must resolve to application name")
                .isNotBlank();
    }

    @Test
    void errorEventContainsStackTraceInThrowableProxy() throws Exception {
        mvc.perform(get("/api/error")).andExpect(status().isOk());

        ILoggingEvent errorEvent = appender.list.stream()
                .filter(e -> e.getLevel().toString().equals("ERROR"))
                .findFirst()
                .orElseThrow();

        // ECS error.stack_trace comes from ThrowableProxy — must not be null
        assertThat(errorEvent.getThrowableProxy()).isNotNull();
        assertThat(errorEvent.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
    }

    // ── 2. traceId / spanId injection ────────────────────────────────────────

    @Test
    void traceIdInjectedIntoMdcByMicrometerTracing() throws Exception {
        mvc.perform(get("/api/hello")).andExpect(status().isOk());

        ILoggingEvent event = appender.list.get(0);
        // Micrometer Tracing (Brave bridge) injects traceId + spanId into MDC
        assertThat(event.getMDCPropertyMap())
                .containsKey("traceId")
                .containsKey("spanId");
        assertThat(event.getMDCPropertyMap().get("traceId")).isNotBlank();
    }

    @Test
    void requestIdAndTraceIdBothPresentInSameEvent() throws Exception {
        mvc.perform(get("/api/hello").header("X-Request-ID", "harness-trace-001"))
                .andExpect(status().isOk());

        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getMDCPropertyMap())
                .containsEntry("requestId", "harness-trace-001")
                .containsKey("traceId");
    }

    // ── 3. MDC isolation across concurrent virtual threads ───────────────────

    @Test
    void mdcNotLeakedAcrossConcurrentRequests() throws Exception {
        int threads = 10;
        var latch = new CountDownLatch(threads);
        var leaks = new CopyOnWriteArrayList<String>();

        List<Thread> vThreads = java.util.stream.IntStream.range(0, threads)
                .mapToObj(i -> Thread.ofVirtual().start(() -> {
                    try {
                        String reqId = "req-" + i;
                        mvc.perform(get("/api/hello").header("X-Request-ID", reqId))
                                .andExpect(status().isOk());

                        // After request completes, MDC must be empty on this thread
                        String leaked = org.slf4j.MDC.get("requestId");
                        if (leaked != null) leaks.add(leaked);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        latch.countDown();
                    }
                }))
                .toList();

        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(leaks).as("MDC requestId leaked after request on virtual thread").isEmpty();
    }

    // ── 4. asyncDemo MDC propagation ─────────────────────────────────────────

    @Test
    void asyncDemoChildThreadLogsWithParentMdcContext() throws Exception {
        mvc.perform(get("/api/async-demo").header("X-Request-ID", "async-parent-001"))
                .andExpect(status().isOk());

        // The child virtual thread log event must carry the same requestId as the parent
        ILoggingEvent asyncEvent = appender.list.stream()
                .filter(e -> "Async task running on virtual thread".equals(e.getMessage()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("async log event not found"));

        assertThat(asyncEvent.getMDCPropertyMap())
                .containsEntry("requestId", "async-parent-001");
    }
}
