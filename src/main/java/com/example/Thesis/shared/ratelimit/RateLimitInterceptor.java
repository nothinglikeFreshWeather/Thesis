package com.example.Thesis.shared.ratelimit;

import io.micrometer.core.instrument.Counter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Spring MVC interceptor that enforces the global rate limit on all {@code /api/**} requests.
 * Returns HTTP 429 when the token bucket is exhausted.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitingService rateLimitingService;
    private final Counter rateLimitExceededCounter;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        if (request.getRequestURI().startsWith("/api")) {
            if (!rateLimitingService.tryConsume()) {
                rateLimitExceededCounter.increment();
                log.warn("Rate limit exceeded: path={}", request.getRequestURI());

                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json");
                response.getWriter().write("""
                        {
                            "error": "Too Many Requests",
                            "message": "Rate limit exceeded. Please retry after a moment."
                        }
                        """);
                return false;
            }
        }
        return true;
    }
}
