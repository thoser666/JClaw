package biz.brumm.infrastructure.adapter.in.web;

import biz.brumm.config.AuthProperties;
import biz.brumm.domain.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuthInterceptorTest {

    private AuthService authService;
    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        AuthProperties properties = new AuthProperties(true, Set.of("/api/v1/gateway/status"));
        interceptor = new AuthInterceptor(authService, properties);
    }

    @Test
    void allowsRequestWhenAuthDisabled() {
        AuthProperties disabledProps = AuthProperties.disabled();
        AuthInterceptor disabledInterceptor = new AuthInterceptor(authService, disabledProps);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(disabledInterceptor.preHandle(request, response, null)).isTrue();
    }

    @Test
    void allowsPublicPaths() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/gateway/status");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, null)).isTrue();
    }

    @Test
    void allowsStaticResources() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/css/app.css");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, null)).isTrue();
    }

    @Test
    void rejectsRequestWithoutAuthorizationHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, null)).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsRequestWithInvalidToken() {
        when(authService.isValidToken("invalid")).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.addHeader("Authorization", "Bearer invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, null)).isFalse();
    }

    @Test
    void allowsRequestWithValidToken() {
        when(authService.isValidToken("valid-token")).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, null)).isTrue();
    }

    @Test
    void rejectsRequestWithWrongAuthScheme() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, null)).isFalse();
    }
}
