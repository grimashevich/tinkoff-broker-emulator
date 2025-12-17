package ru.tinkoff.invest.emulator.web.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.tinkoff.invest.emulator.core.orderbook.OrderBookManager;
import org.junit.jupiter.api.BeforeEach;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "grpc.server.port=9099",
    "grpc.server.inProcessName=test-admin"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderBookManager orderBookManager;

    @BeforeEach
    void setUp() {
        orderBookManager.clear();
    }

    @Test
    void testGetOrderBook() throws Exception {
        mockMvc.perform(get("/api/orderbook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instrumentId").exists())
                .andExpect(jsonPath("$.bids").isArray());
    }

    @Test
    void testGetAccount() throws Exception {
        mockMvc.perform(get("/api/account"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.balance").exists());
    }

    @Test
    void testCreateAndCancelOrder() throws Exception {
        String orderJson = """
            {
                "instrumentId": "TBRU",
                "direction": "BUY",
                "orderType": "LIMIT",
                "price": 100.00,
                "quantity": 10,
                "accountId": "admin"
            }
            """;

        String response = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.quantity").value(10))
                .andReturn().getResponse().getContentAsString();
        
        // Extract ID (naive regex)
        String id = response.split("\"id\":\"")[1].split("\"")[0];

        mockMvc.perform(delete("/api/orders/" + id))
                .andExpect(status().isOk());
    }
}
