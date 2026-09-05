package com.library.lms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Library Management System backend.
 *
 * <p>{@code @SpringBootApplication} is a shortcut for three annotations:</p>
 * <ul>
 *   <li><b>@Configuration</b> - this class can define Spring beans.</li>
 *   <li><b>@EnableAutoConfiguration</b> - Spring Boot inspects the classpath and
 *       configures things automatically (it sees spring-boot-starter-web, so it
 *       starts an embedded Tomcat on port 8080; it sees Spring Data JPA and the
 *       MySQL driver, so it builds a DataSource and Hibernate session factory).</li>
 *   <li><b>@ComponentScan</b> - it scans this package, {@code com.library.lms},
 *       and every sub-package for @RestController, @Service, @Repository and
 *       @Component classes and registers them. This is exactly why the class
 *       must sit in the ROOT package: everything we add later is found
 *       automatically.</li>
 * </ul>
 */
@SpringBootApplication
public class LibraryManagementSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(LibraryManagementSystemApplication.class, args);
    }
}
