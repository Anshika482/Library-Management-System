package com.library.lms.config;

import org.apache.catalina.core.StandardHost;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Installs {@link JsonErrorReportValve} in place of Tomcat's own error page.
 *
 * <p>Tomcat keeps its error report valve on the host and builds it from a class
 * name, so replacing it is a matter of naming a different class before the host
 * starts. The customizer below runs while the context is being prepared, which
 * is early enough: the host instantiates the valve when it starts, after this
 * has run.</p>
 *
 * <p>Naming the class is what makes this work at all. Adding a valve of our own
 * to the pipeline would not: valves run outermost first and report on the way
 * back out, so Tomcat's own valve - which sits further in, on the host - would
 * still be the one to claim the error and render the HTML.</p>
 */
@Configuration
public class ErrorHardeningConfig {

    /**
     * Points the Tomcat host at the JSON valve.
     *
     * <p>The type check is not defensive noise: {@code getParent()} is declared
     * as a {@code Container}, and only a {@code StandardHost} carries the error
     * report valve setting. If Tomcat is ever replaced by another container
     * this bean simply does nothing, rather than failing the startup.</p>
     *
     * @return a customizer that replaces the container's error page
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> containerErrorsAsJson() {
        return factory -> factory.addContextCustomizers(context -> {
            if (context.getParent() instanceof StandardHost host) {
                host.setErrorReportValveClass(JsonErrorReportValve.class.getName());
            }
        });
    }
}
