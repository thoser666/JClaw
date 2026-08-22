package biz.brumm.config;

import biz.brumm.domain.service.AuthService;
import biz.brumm.infrastructure.adapter.in.web.AuthInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
@ConditionalOnProperty(prefix = "jclaw.auth", name = "enabled", havingValue = "true")
public class AuthConfiguration implements WebMvcConfigurer {

    private final AuthService authService;
    private final AuthProperties properties;

    public AuthConfiguration(AuthService authService, AuthProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(authService, properties))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/gateway/status", "/api/v1/auth/**");
    }
}
