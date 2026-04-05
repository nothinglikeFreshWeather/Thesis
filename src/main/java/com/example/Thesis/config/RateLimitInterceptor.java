package com.example.Thesis.config;

import com.example.Thesis.service.RateLimitingService;
import io.micrometer.core.instrument.Counter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitingService rateLimitingService;
    private final Counter rateLimitExceededCounter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        
        // Sadece /api ile başlayan endpoint'leri limitleyebiliriz, actuator vs hariç.
        if (request.getRequestURI().startsWith("/api")) {
            if (!rateLimitingService.tryConsume()) {
                // Rate limit aşıldı!
                rateLimitExceededCounter.increment();
                log.warn("Rate limit exceeded for path {}. Returning 429 Too Many Requests.", request.getRequestURI());
                
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json");
                response.getWriter().write("""
                        {
                            "error": "Too Many Requests",
                            "message": "Sistem anlik istel limitini astiniz. Lutfen biraz bekleyip tekrar deneyin."
                        }
                        """);
                
                // false dönerse Spring MVC "Controller sınıfına gitme, işlemi kes" der.
                return false;
            }
        }

        // Limit aşılmadıysa, devam et (Controller'a ilerle)
        return true;
    }
}
