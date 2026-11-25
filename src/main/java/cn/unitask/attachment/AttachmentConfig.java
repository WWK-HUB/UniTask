package cn.unitask.attachment;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AttachmentConfig {
    @Bean
    ServletRegistrationBean<DownloadServlet> downloads(AttachmentService files) {
        var registration = new ServletRegistrationBean<>(new DownloadServlet(files),"/attachments/*");
        registration.setName("unitaskAttachmentDownload");
        registration.setLoadOnStartup(1);
        return registration;
    }
}
