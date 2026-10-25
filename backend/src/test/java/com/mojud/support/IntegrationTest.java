package com.mojud.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The full application on a random port against the embedded PostgreSQL. Integration tests use
 * this one annotation so they share one cached Spring context and one database: tests create
 * their own businesses and never assume an empty table.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({EmbeddedPostgresConfig.class, DevSupportConfig.class})
public @interface IntegrationTest {
}
