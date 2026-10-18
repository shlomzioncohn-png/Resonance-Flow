package com.rnd.inbox.shared.logging;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Records which HTTP request the current thread is serving - its id and the controller method
 * handling it - so every SQL log line written meanwhile can carry both.
 */
public class SqlLogRequestInterceptor implements HandlerInterceptor {

    public static final String REQUEST_ID_KEY = "request_id";
    public static final String OPERATION_KEY = "operation";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    // A caller-supplied id ends up in the log and in a response header, so only plain tokens pass.
    private static final Pattern ACCEPTABLE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern WORD_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String requestId = resolveRequestId(request);
        MDC.put(REQUEST_ID_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        if (handler instanceof HandlerMethod handlerMethod) {
            MDC.put(OPERATION_KEY, toUpperSnakeCase(handlerMethod.getMethod().getName()));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception ex) {
        // Threads are pooled: without this the next request on this thread inherits the values.
        MDC.remove(REQUEST_ID_KEY);
        MDC.remove(OPERATION_KEY);
    }

    private String resolveRequestId(HttpServletRequest request) {
        // Kept on the request so an error re-dispatch reports the same id as the original attempt.
        if (request.getAttribute(REQUEST_ID_KEY) instanceof String alreadyAssigned) {
            return alreadyAssigned;
        }
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        String requestId = supplied != null && ACCEPTABLE_REQUEST_ID.matcher(supplied).matches()
                ? supplied
                : "req-" + UUID.randomUUID().toString().substring(0, 8);
        request.setAttribute(REQUEST_ID_KEY, requestId);
        return requestId;
    }

    private static String toUpperSnakeCase(String camelCase) {
        return WORD_BOUNDARY.matcher(camelCase).replaceAll("$1_$2").toUpperCase(Locale.ROOT);
    }
}
