package dev.meirong.demo;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Captures Logback output in-memory to assert ECS-relevant log fields
 * without spinning up the full Spring context.
 */
class DemoControllerLogTest {

    private ListAppender<ILoggingEvent> appender;
    private DemoController controller;

    @BeforeEach
    void setUp() {
        controller = new DemoController();

        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(DemoController.class)).addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(DemoController.class)).detachAppender(appender);
    }

    @Test
    void helloLogsInfoWithEndpointKeyValue() {
        controller.hello();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel().toString()).isEqualTo("INFO");
        assertThat(event.getMessage()).isEqualTo("Hello endpoint called");
        assertThat(event.getKeyValuePairs())
                .anyMatch(kv -> kv.key.equals("endpoint") && kv.value.equals("/api/hello"));
    }

    @Test
    void orderLogsOrderIdAndStatus() {
        controller.getOrder("42");

        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getMessage()).isEqualTo("Order retrieved successfully");
        assertThat(event.getKeyValuePairs())
                .anyMatch(kv -> kv.key.equals("orderId") && kv.value.equals("42"))
                .anyMatch(kv -> kv.key.equals("status"));
    }

    @Test
    void errorLogsAtErrorLevelWithCause() {
        controller.triggerError();

        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel().toString()).isEqualTo("ERROR");
        assertThat(event.getThrowableProxy()).isNotNull();
        assertThat(event.getThrowableProxy().getClassName())
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(event.getKeyValuePairs())
                .anyMatch(kv -> kv.key.equals("errorCode") && kv.value.equals("DOWNSTREAM_FAILURE"));
    }

    @Test
    void orderLogIncludesDurationMs() {
        controller.getOrder("77");

        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getKeyValuePairs())
                .anyMatch(kv -> kv.key.equals("durationMs"));
    }

    @Test
    void asyncDemoChildThreadLogsCorrectMessage() throws InterruptedException {
        controller.asyncDemo();

        assertThat(appender.list)
                .anyMatch(e -> "Async task running on virtual thread".equals(e.getMessage()));
    }

    @Test
    void orderStatusDeterministicForSameId() {
        // Same id must always yield same status (hash-based, not random)
        String r1 = controller.getOrder("99");
        String r2 = controller.getOrder("99");
        assertThat(r1).isEqualTo(r2);
    }
}
