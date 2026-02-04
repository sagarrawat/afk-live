package com.afklive.streamer.aspect;

import com.afklive.streamer.service.QuotaTrackingService;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class QuotaAspect {

    private final QuotaTrackingService quotaTrackingService;

    @Around("@annotation(com.afklive.streamer.aspect.YoutubeQuota)")
    public Object trackQuota(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        YoutubeQuota annotation = signature.getMethod().getAnnotation(YoutubeQuota.class);

        String apiName = annotation.apiName();
        int estimatedCost = annotation.cost();
        String username = "unknown";

        // Heuristic: Assume first argument is username
        Object[] args = joinPoint.getArgs();
        if (args.length > 0 && args[0] instanceof String) {
            username = (String) args[0];
        }

        try {
            Object result = joinPoint.proceed();
            quotaTrackingService.logApiCall(username, apiName, estimatedCost, "SUCCESS");
            return result;
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 304) {
                // Not Modified = 0 quota
                quotaTrackingService.logApiCall(username, apiName, 0, "304");
            } else {
                // Failure usually costs quota too (e.g. 1 unit)
                quotaTrackingService.logApiCall(username, apiName, Math.min(estimatedCost, 1), "FAILURE");
            }
            throw e;
        } catch (Exception e) {
            // Other exceptions might not be API calls, but safer to assume failure cost
             quotaTrackingService.logApiCall(username, apiName, Math.min(estimatedCost, 1), "ERROR");
            throw e;
        }
    }
}
