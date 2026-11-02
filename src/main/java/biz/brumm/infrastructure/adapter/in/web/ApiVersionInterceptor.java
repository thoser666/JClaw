package biz.brumm.infrastructure.adapter.in.web;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Setzt API-Versions- und Deprecation-Header (P4-16 Stable API).
 */
public class ApiVersionInterceptor implements HandlerInterceptor {

    public static final String VERSION_HEADER = "X-API-Version";
    public static final String DEPRECATION_HEADER = "Deprecation";
    public static final String SUNSET_HEADER = "Sunset";
    public static final String LINK_HEADER = "Link";

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                          org.springframework.web.servlet.ModelAndView modelAndView) {
        String version = "v1";
        ApiDeprecation deprecation = null;
        if (handler instanceof HandlerMethod hm) {
            ApiVersion v = hm.getMethod().getAnnotation(ApiVersion.class);
            if (v == null) v = hm.getBeanType().getAnnotation(ApiVersion.class);
            if (v != null) version = v.value();
            ApiDeprecation d = hm.getMethod().getAnnotation(ApiDeprecation.class);
            if (d == null) d = hm.getBeanType().getAnnotation(ApiDeprecation.class);
            deprecation = d;
        }
        response.setHeader(VERSION_HEADER, version);
        if (deprecation != null) {
            response.setHeader(DEPRECATION_HEADER, "true");
            if (!deprecation.sunset().isBlank()) {
                response.setHeader(SUNSET_HEADER, deprecation.sunset());
            }
            if (!deprecation.replacement().isBlank()) {
                response.setHeader(LINK_HEADER, "<" + deprecation.replacement() + ">; rel=\"successor-version\"");
            }
        }
    }
}