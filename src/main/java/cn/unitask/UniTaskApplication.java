package cn.unitask;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class UniTaskApplication extends SpringBootServletInitializer {
    public static void main(String[] args) {
        var context = SpringApplication.run(UniTaskApplication.class, args);
        if ("none".equals(context.getEnvironment().getProperty("spring.main.web-application-type"))) context.close();
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(UniTaskApplication.class);
    }

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }
}
