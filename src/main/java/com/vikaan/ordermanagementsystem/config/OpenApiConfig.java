package com.vikaan.ordermanagementsystem.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI orderManagementOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ecommerce Order Processing API")
                        .version("v1")
                        .description("""
                                Create, retrieve, list and cancel customer orders.

                                **Order lifecycle.** A new order is always `PENDING`. A background \
                                job promotes every `PENDING` order to `PROCESSING` once a minute. \
                                `SHIPPED` and `DELIVERED` exist in the model but have no endpoint \
                                in v1, because the brief automates only one transition and allows \
                                only cancellation.

                                **Cancellation.** Allowed only while an order is still `PENDING`. \
                                Any other status returns `409 Conflict` naming the status the \
                                order is actually in. The rule is enforced by a status-conditioned \
                                SQL update, so the promotion job and a concurrent cancel cannot \
                                overwrite each other.

                                **Money and time.** Totals are computed server-side from quantity \
                                and unit price; the request body has no `totalAmount` or `status` \
                                field to set. Timestamps are UTC instants at microsecond \
                                precision.

                                Data lives in an in-memory H2 database, so restarting the \
                                application clears every order. Design decisions and trade-offs \
                                are recorded in `docs/DESIGN.md`.""")
                        .license(new License().name("Take-home assignment")));
    }
}
