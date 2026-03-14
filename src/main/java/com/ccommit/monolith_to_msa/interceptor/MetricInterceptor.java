package com.ccommit.monolith_to_msa.interceptor;

import com.ccommit.monolith_to_msa.service.metrics.MetricCollectorService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 메트릭 수집 인터셉터
 * - 모든 HTTP 요청의 응답 시간 및 성공/실패 여부 기록
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricInterceptor implements HandlerInterceptor {
    
    private final MetricCollectorService metricCollectorService;
    private static final ThreadLocal<Long> startTime = new ThreadLocal<>();
    
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        startTime.set(System.currentTimeMillis());
        return true;
    }
    
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        try {
            Long start = startTime.get();
            if (start != null) {
                long responseTime = System.currentTimeMillis() - start;
                String endpoint = request.getRequestURI();
                
                // API 엔드포인트만 수집
                if (endpoint.startsWith("/api/")) {
                    boolean success = response.getStatus() >= 200 && response.getStatus() < 400;
                    metricCollectorService.recordRequest(endpoint, responseTime, success);
                }
            }
        } catch (Exception e) {
            log.error("메트릭 수집 중 오류 발생", e);
        } finally {
            startTime.remove();
        }
    }
}
