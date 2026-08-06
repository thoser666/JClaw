package biz.brumm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JClawApplication {

    public static void main(String[] args) {
        SpringApplication.run(JClawApplication.class, args);
    }
}
