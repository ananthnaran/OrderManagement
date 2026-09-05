package com.vikaan.ordermanagementsystem;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The generated spec, asserted rather than eyeballed in the browser. springdoc builds it by
 * reflecting over controllers at runtime, so a rename or a generic it cannot introspect breaks the
 * documentation without breaking a single other test. These assertions fail instead.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocumentationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("the spec is served, and carries the configured metadata")
    void specIsServed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi", startsWith("3.1")))
                .andExpect(jsonPath("$.info.title").value("Ecommerce Order Processing API"))
                .andExpect(jsonPath("$.info.version").value("v1"))
                .andExpect(jsonPath("$.tags[0].name").value("Orders"));
    }

    @Test
    @DisplayName("all four operations are documented, and nothing else is")
    void everyOperationIsDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/orders'].post.summary").value("Create an order"))
                .andExpect(jsonPath("$.paths['/api/v1/orders'].get.summary")
                        .value("List orders, optionally filtered by status"))
                .andExpect(jsonPath("$.paths['/api/v1/orders/{orderId}'].get.summary")
                        .value("Retrieve an order by id"))
                .andExpect(jsonPath("$.paths['/api/v1/orders/{orderId}/cancel'].post.summary")
                        .value(containsString("only while it is PENDING")))
                // No PUT or PATCH on status: the brief allows exactly one client-driven transition.
                .andExpect(jsonPath("$.paths['/api/v1/orders/{orderId}'].put").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/orders/{orderId}'].patch").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/orders/{orderId}'].delete").doesNotExist());
    }

    @Test
    @DisplayName("failure responses are documented, not just the happy paths")
    void failuresAreDocumented() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/orders'].post.responses.201").exists())
                .andExpect(jsonPath("$.paths['/api/v1/orders'].post.responses.400").exists())
                .andExpect(jsonPath("$.paths['/api/v1/orders/{orderId}'].get.responses.404").exists())
                .andExpect(jsonPath("$.paths['/api/v1/orders'].get.responses.400").exists())
                // The conflict is the whole point of the cancel rule; an undocumented 409 would
                // leave a caller no way to know refusal is expected behaviour.
                .andExpect(jsonPath("$.paths['/api/v1/orders/{orderId}/cancel'].post.responses.409")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/v1/orders/{orderId}/cancel'].post.responses.404")
                        .exists())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/orders/{orderId}/cancel'].post.responses.409.content"
                                + "['application/json'].schema.$ref")
                        .value(containsString("ErrorResponse")));
    }

    @Test
    @DisplayName("request and response schemas are introspected, including the paged envelope")
    void schemasAreGenerated() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.CreateOrderRequest").exists())
                .andExpect(jsonPath("$.components.schemas.OrderItemRequest").exists())
                .andExpect(jsonPath("$.components.schemas.OrderResponse").exists())
                .andExpect(jsonPath("$.components.schemas.ErrorResponse").exists())
                // A generic springdoc cannot resolve silently degrades to a bare object, which is
                // useless to a client generator.
                .andExpect(jsonPath("$.components.schemas.PagedResponseOrderResponse.properties"
                        + ".content.items.$ref").value(containsString("OrderResponse")))
                .andExpect(jsonPath("$.components.schemas.CreateOrderRequest.required",
                        hasItem("customerId")))
                .andExpect(jsonPath("$.components.schemas.OrderItemRequest.properties.quantity.maximum")
                        .value(1000))
                .andExpect(jsonPath("$.components.schemas.OrderResponse.properties.status.enum",
                        hasItem("CANCELLED")));
    }

    @Test
    @DisplayName("the request schema exposes no server-owned field a client could try to set")
    void requestSchemaHidesServerOwnedFields() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.CreateOrderRequest.properties.status")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreateOrderRequest.properties.totalAmount")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreateOrderRequest.properties.id")
                        .doesNotExist())
                .andExpect(jsonPath("$.components.schemas.OrderItemRequest.properties.lineTotal")
                        .doesNotExist());
    }

    @Test
    @DisplayName("Swagger UI is reachable at the configured path")
    void swaggerUiIsReachable() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .as("swagger-ui.html should redirect to the bundled UI")
                        .isNotNull()
                        .contains("swagger-ui"));

        mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .as("the YAML rendering should be a spec, not an error page")
                        .contains("Ecommerce Order Processing API")
                        .doesNotContain("Whitelabel"));
    }

    @Test
    @DisplayName("the spec covers the API and nothing else")
    void specCoversOnlyTheApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths.length()").value(3))
                .andExpect(jsonPath("$.paths['/h2-console']").doesNotExist());
    }
}
