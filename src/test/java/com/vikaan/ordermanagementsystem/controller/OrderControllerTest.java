package com.vikaan.ordermanagementsystem.controller;

import com.vikaan.ordermanagementsystem.controller.support.OrderQueryParams;
import com.vikaan.ordermanagementsystem.dto.response.OrderItemResponse;
import com.vikaan.ordermanagementsystem.dto.response.OrderResponse;
import com.vikaan.ordermanagementsystem.dto.response.PagedResponse;
import com.vikaan.ordermanagementsystem.entity.OrderStatus;
import com.vikaan.ordermanagementsystem.exception.InvalidRequestException;
import com.vikaan.ordermanagementsystem.exception.OrderNotFoundException;
import com.vikaan.ordermanagementsystem.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final String VALID_BODY = """
            {
              "customerId": "CUST-1001",
              "items": [
                {"productId":"SKU-MOUSE","productName":"Wireless Mouse","quantity":2,"unitPrice":25.00},
                {"productId":"SKU-PAD","productName":"Mouse Pad","quantity":1,"unitPrice":10.00}
              ]
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    @DisplayName("POST returns 201 with a Location header and the created order")
    void createReturnsCreated() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.createOrder(any())).thenReturn(sampleResponse(id));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/orders/" + id))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(60.00))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    @DisplayName("POST with no items returns 400 and never reaches the service")
    void createRejectsEmptyItems() throws Exception {
        String body = """
                {"customerId":"CUST-1001","items":[]}
                """;

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.details").isNotEmpty());

        verify(orderService, never()).createOrder(any());
    }

    @Test
    @DisplayName("POST with a zero quantity returns 400 from the nested item constraint")
    void createRejectsZeroQuantity() throws Exception {
        String body = """
                {"customerId":"CUST-1001","items":[
                  {"productId":"SKU-A","productName":"Item A","quantity":0,"unitPrice":10.00}]}
                """;

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value("items[0].quantity: quantity must be at least 1"));
    }

    @Test
    @DisplayName("POST with a negative unit price returns 400")
    void createRejectsNegativePrice() throws Exception {
        String body = """
                {"customerId":"CUST-1001","items":[
                  {"productId":"SKU-A","productName":"Item A","quantity":1,"unitPrice":-1.00}]}
                """;

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST with a blank customerId returns 400")
    void createRejectsBlankCustomer() throws Exception {
        String body = """
                {"customerId":"  ","items":[
                  {"productId":"SKU-A","productName":"Item A","quantity":1,"unitPrice":10.00}]}
                """;

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST with malformed JSON returns 400 rather than 500")
    void createRejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed JSON request body"));
    }

    @Test
    @DisplayName("POST with duplicate product ids surfaces the service rejection as 400")
    void createRejectsDuplicateProducts() throws Exception {
        when(orderService.createOrder(any()))
                .thenThrow(new InvalidRequestException("Duplicate productId in request: SKU-DUP"));

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Duplicate productId in request: SKU-DUP"));
    }

    @Test
    @DisplayName("GET returns 200 with the order when it exists")
    void getReturnsOrder() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.getOrderById(id)).thenReturn(sampleResponse(id));

        mockMvc.perform(get("/api/v1/orders/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.customerId").value("CUST-1001"));
    }

    @Test
    @DisplayName("GET returns 404 for an unknown order id")
    void getReturnsNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.getOrderById(id)).thenThrow(new OrderNotFoundException(id));

        mockMvc.perform(get("/api/v1/orders/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/v1/orders/" + id));
    }

    @Test
    @DisplayName("GET returns 400 when the id is not a UUID")
    void getRejectsMalformedId() throws Exception {
        mockMvc.perform(get("/api/v1/orders/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(orderService, never()).getOrderById(any());
    }

    @Test
    @DisplayName("GET list returns 200 with the pagination envelope")
    void listReturnsEnvelope() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.listOrders(isNull(), any(Pageable.class)))
                .thenReturn(new PagedResponse<>(List.of(sampleResponse(id)),
                        0, 20, 1L, 1, true, true, "createdAt: DESC"));

        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.sort").value("createdAt: DESC"));
    }

    @Test
    @DisplayName("GET list with no orders returns 200 and an empty content array")
    void listReturnsEmptyContent() throws Exception {
        when(orderService.listOrders(isNull(), any(Pageable.class)))
                .thenReturn(new PagedResponse<>(List.of(), 0, 20, 0L, 0, true, true, "createdAt: DESC"));

        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("GET list defaults to page 0, size 20, newest first")
    void listAppliesDefaults() throws Exception {
        when(orderService.listOrders(any(), any(Pageable.class))).thenReturn(emptyPage());

        mockMvc.perform(get("/api/v1/orders")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderService).listOrders(isNull(), captor.capture());
        Pageable pageable = captor.getValue();

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(20);
        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("GET list passes the parsed status filter through to the service")
    void listPassesStatusFilter() throws Exception {
        when(orderService.listOrders(any(), any(Pageable.class))).thenReturn(emptyPage());

        mockMvc.perform(get("/api/v1/orders").param("status", "PROCESSING"))
                .andExpect(status().isOk());

        verify(orderService).listOrders(eq(OrderStatus.PROCESSING), any(Pageable.class));
    }

    @Test
    @DisplayName("GET list clamps an oversized page size to the maximum")
    void listClampsOversizedPageSize() throws Exception {
        when(orderService.listOrders(any(), any(Pageable.class))).thenReturn(emptyPage());

        mockMvc.perform(get("/api/v1/orders").param("size", "5000"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderService).listOrders(isNull(), captor.capture());

        assertThat(captor.getValue().getPageSize()).isEqualTo(OrderQueryParams.MAX_PAGE_SIZE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "FOO"})
    @DisplayName("GET list with an invalid status returns 400 and never reaches the service")
    void listRejectsInvalidStatus(String status) throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("status", status))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(containsString("Accepted values")));

        verify(orderService, never()).listOrders(any(), any());
    }

    @Test
    @DisplayName("GET list with an unknown sort property returns 400 rather than 500")
    void listRejectsUnknownSortProperty() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("sort", "dropTable,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Cannot sort by 'dropTable'")));

        verify(orderService, never()).listOrders(any(), any());
    }

    @Test
    @DisplayName("GET list with a negative page returns 400")
    void listRejectsNegativePage() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("page must be 0 or greater")));
    }

    @Test
    @DisplayName("GET list with a zero page size returns 400")
    void listRejectsZeroSize() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("size must be at least 1")));
    }

    @Test
    @DisplayName("GET list with a non-numeric page returns 400")
    void listRejectsNonNumericPage() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    private PagedResponse<OrderResponse> emptyPage() {
        return new PagedResponse<>(List.of(), 0, 20, 0L, 0, true, true, "createdAt: DESC");
    }

    private OrderResponse sampleResponse(UUID id) {
        Instant now = Instant.parse("2026-09-03T14:30:00Z");
        return new OrderResponse(
                id,
                "CUST-1001",
                OrderStatus.PENDING,
                new BigDecimal("60.00"),
                List.of(
                        new OrderItemResponse("SKU-MOUSE", "Wireless Mouse", 2,
                                new BigDecimal("25.00"), new BigDecimal("50.00")),
                        new OrderItemResponse("SKU-PAD", "Mouse Pad", 1,
                                new BigDecimal("10.00"), new BigDecimal("10.00"))
                ),
                now,
                now,
                null
        );
    }
}
