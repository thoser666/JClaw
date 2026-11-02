package biz.brumm.infrastructure.adapter.in.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import jakarta.servlet.http.HttpServletResponse;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiVersionInterceptorTest {

    @ApiVersion("v1")
    @ApiDeprecation(since = "v1", sunset = "2027-01-01", replacement = "/api/v2/foo")
    static class DeprecatedController {
        public void handle() {
        }
    }

    @Test
    void deprecatedEndpointGetsHeaders() throws Exception {
        ApiVersionInterceptor interceptor = new ApiVersionInterceptor();
        HttpServletResponse response = mock(HttpServletResponse.class);
        Method m = DeprecatedController.class.getMethod("handle");
        HandlerMethod hm = new HandlerMethod(new DeprecatedController(), m);

        interceptor.postHandle(null, response, hm, null);

        org.mockito.Mockito.verify(response).setHeader(ApiVersionInterceptor.VERSION_HEADER, "v1");
        org.mockito.Mockito.verify(response).setHeader(ApiVersionInterceptor.DEPRECATION_HEADER, "true");
        org.mockito.Mockito.verify(response).setHeader(ApiVersionInterceptor.SUNSET_HEADER, "2027-01-01");
        org.mockito.Mockito.verify(response).setHeader(ApiVersionInterceptor.LINK_HEADER, "</api/v2/foo>; rel=\"successor-version\"");
    }

    @Test
    void nonDeprecatedEndpointGetsVersionOnly() throws Exception {
        ApiVersionInterceptor interceptor = new ApiVersionInterceptor();
        HttpServletResponse response = mock(HttpServletResponse.class);

        interceptor.postHandle(null, response, "not-a-handler", null);

        org.mockito.Mockito.verify(response).setHeader(ApiVersionInterceptor.VERSION_HEADER, "v1");
    }
}