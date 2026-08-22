package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.config.AuthProperties;
import biz.brumm.domain.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Interceptor zur API-Token-Authentifizierung.
 * Prüft den Authorization-Header gegen gespeicherte API-Token.
 */
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    private final AuthProperties properties;

    public AuthInterceptor(AuthService authService, AuthProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.enabled()) {
            return true;
        }

        String path = request.getRequestURI();

        // Öffentliche Pfade überspringen
        if (isPublicPath(path)) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            sendUnauthorized(response, "Authorization-Header fehlt oder ungültig.");
            return false;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());
        if (!authService.isValidToken(token)) {
            sendUnauthorized(response, "Ungültiger API-Token.");
            return false;
        }

        return true;
    }

    private boolean isPublicPath(String path) {
        if (path.startsWith("/css/") || path.startsWith("/js/") || path.equals("/") || path.equals("/index.html")) {
            return true;
        }
        return properties.publicPaths().stream().anyMatch(path::startsWith);
    }

    private void sendUnauthorized(HttpServletResponse response, String message) {
        try {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\": \"" + message + "\"}");
        } catch (Exception e) {
            log.error("Fehler beim Senden der 401-Antwort: {}", e.getMessage());
        }
    }
}
