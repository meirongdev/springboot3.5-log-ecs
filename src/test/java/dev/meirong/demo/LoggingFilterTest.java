package dev.meirong.demo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingFilterTest {

    private final LoggingFilter filter = new LoggingFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesRequestIdWhenAbsent() throws Exception {
        var req = new MockHttpServletRequest();
        var res = new MockHttpServletResponse();
        filter.doFilterInternal(req, res, new MockFilterChain());

        assertThat(res.getHeader("X-Request-ID")).isNotBlank();
    }

    @Test
    void propagatesUpstreamRequestId() throws Exception {
        var req = new MockHttpServletRequest();
        req.addHeader("X-Request-ID", "upstream-123");
        var res = new MockHttpServletResponse();
        filter.doFilterInternal(req, res, new MockFilterChain());

        assertThat(res.getHeader("X-Request-ID")).isEqualTo("upstream-123");
    }

    @Test
    void mdcClearedAfterRequest() throws Exception {
        var req = new MockHttpServletRequest();
        var res = new MockHttpServletResponse();
        filter.doFilterInternal(req, res, new MockFilterChain());

        // MDC must be empty after filter completes (prevent virtual-thread leak)
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void mdcContainsRequestIdDuringChain() throws Exception {
        var req = new MockHttpServletRequest();
        req.addHeader("X-Request-ID", "trace-abc");
        var res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, (rq, rs) -> {
            // Inside the chain, MDC must carry the requestId
            assertThat(MDC.get("requestId")).isEqualTo("trace-abc");
        });
    }
}
