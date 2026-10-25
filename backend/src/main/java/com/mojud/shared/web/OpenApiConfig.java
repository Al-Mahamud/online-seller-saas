package com.mojud.shared.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The API description the frontend generates its typed client from. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    OpenAPI mojudOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Mojud API")
                .version("v1")
                .description("Inventory, orders and billing for Bangladesh online sellers. "
                        + "Errors are RFC 9457 problem responses with a machine-readable `code`."));
    }
}
