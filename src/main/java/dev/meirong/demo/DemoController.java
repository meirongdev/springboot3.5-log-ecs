package dev.meirong.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

/**
 * Demo controller with four endpoints:
 *   GET /api/hello
 *   GET /api/orders/{id}
 *   GET /api/error
 *   GET /api/async-demo
 *
 * Each handler demonstrates a different structured-logging pattern.
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    // ── /api/hello ────────────────────────────────────────────────────────────

    @GetMapping("/hello")
    public String hello() {
        // Simple INFO — message describes the action, no variables in message text
        log.atInfo()
                .setMessage("Hello endpoint called")
                .addKeyValue("endpoint", "/api/hello")
                .log();
        return "Hello from Spring Boot 3.5 + Java 25!";
    }

    // ── /api/orders/{id} ──────────────────────────────────────────────────────

    @GetMapping("/orders/{id}")
    public String getOrder(@PathVariable("id") String id) {
        long start = System.currentTimeMillis();

        // Simulate lightweight processing
        String status = id.hashCode() % 3 == 0 ? "SHIPPED" : "PROCESSING";

        log.atInfo()
                .setMessage("Order retrieved successfully")
                .addKeyValue("orderId", id)
                .addKeyValue("status", status)
                .addKeyValue("durationMs", System.currentTimeMillis() - start)
                .log();

        return "{\"orderId\":\"%s\",\"status\":\"%s\"}".formatted(id, status);
    }

    // ── /api/error ────────────────────────────────────────────────────────────

    @GetMapping("/error")
    public String triggerError() {
        try {
            // Intentional failure to demonstrate ERROR log with exception
            throw new IllegalStateException("Simulated downstream failure");
        } catch (IllegalStateException e) {
            // Correct pattern: pass exception object — Logback formats the stack trace
            log.atError()
                    .setMessage("Failed to process request")
                    .addKeyValue("errorCode", "DOWNSTREAM_FAILURE")
                    .setCause(e)
                    .log();
            return "{\"error\":\"downstream failure\"}";
        }
    }

    // ── /api/async-demo ───────────────────────────────────────────────────────

    /**
     * Demonstrates MDC propagation to a child virtual thread (manual copy pattern).
     */
    @GetMapping("/async-demo")
    public String asyncDemo() throws InterruptedException {
        org.slf4j.MDC.put("demoKey", "parent-value");

        java.util.Map<String, String> mdcCopy = org.slf4j.MDC.getCopyOfContextMap();

        Thread childThread = Thread.ofVirtual().start(() -> {
            if (mdcCopy != null) org.slf4j.MDC.setContextMap(mdcCopy);
            try {
                log.atInfo()
                        .setMessage("Async task running on virtual thread")
                        .addKeyValue("threadName", Thread.currentThread().getName())
                        .log();
            } finally {
                org.slf4j.MDC.clear();
            }
        });

        childThread.join();
        org.slf4j.MDC.remove("demoKey");
        return "async demo complete";
    }
}
