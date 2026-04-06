package dev.meirong.demo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Injects requestId into MDC for every request.
 * Honours an upstream X-Request-ID header when present.
 * traceId/spanId are auto-injected by Micrometer Tracing.
 */
@Component
public class LoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        // Honour upstream X-Request-ID header if present (e.g. from API gateway)
        String requestId = req.getHeader("X-Request-ID");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put("requestId", requestId);
        res.setHeader("X-Request-ID", requestId);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.clear(); // prevent memory leak on thread reuse
        }
    }
}
