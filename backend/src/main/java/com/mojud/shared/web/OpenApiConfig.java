package com.mojud.shared.web;

import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.csrf.CsrfToken;
import tools.jackson.databind.JsonNode;

/** The API description the frontend generates its typed client from. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    static {
        // Free-form JSON (audit details, notification payloads) is an object, not JsonNode's getters.
        SpringDocUtils.getConfig().replaceWithSchema(JsonNode.class, new ObjectSchema().additionalProperties(true));
        // Supplied by Spring Security to GET /auth/csrf, not sent by the client.
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(CsrfToken.class);
    }

    @Bean
    OpenAPI mojudOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Mojud API")
                .version("v1")
                .description("Inventory, orders and billing for Bangladesh online sellers. "
                        + "Errors are RFC 9457 problem responses with a machine-readable `code`."));
    }

    /**
     * Replaces springdoc's default model resolver (same class, so springdoc swaps it in) to use
     * {@link ApiTypeNames}. First in order, so springdoc's own converters still run before it.
     */
    /** Sign-out is Spring Security's filter (SecurityConfig), not a controller, so springdoc cannot see it. */
    @Bean
    OpenApiCustomizer logoutEndpoint() {
        return openApi -> openApi.path("/api/v1/auth/logout", new PathItem().post(new Operation()
                .operationId("logOut")
                .addTagsItem("auth-controller")
                .responses(new ApiResponses().addApiResponse("204", new ApiResponse().description("No Content")))));
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    ModelResolver apiModelResolver() {
        return new ModelResolver(Json31.mapper(), ApiTypeNames.INSTANCE).openapi31(true);
    }

    @Bean
    ResponseFields responseFields() {
        return new ResponseFields(ApiTypeNames.INSTANCE);
    }
}
