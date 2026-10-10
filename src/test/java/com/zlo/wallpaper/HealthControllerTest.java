package com.zlo.wallpaper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @Test
    void readyResponseUsesExistingContractAndMatchingRequestId() throws Exception {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        var response = mockMvc.perform(get("/api/v1/health").contextPath("/api/v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value(""))
                .andExpect(jsonPath("$.data").isMap())
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andReturn().getResponse();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
        assertEquals(response.getHeader("X-Request-Id"), json.get("requestId").asText());
    }

    @Test
    void unavailableDatabaseReturns503WithoutConnectionDetails() throws Exception {
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("private connection detail"));
        mockMvc.perform(get("/api/v1/health").contextPath("/api/v1")
                        .header("Origin", "http://127.0.0.1:4173"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:4173"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("数据库暂不可用"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void adminOriginCanPreflightAndUnconfiguredOriginIsRejected() throws Exception {
        mockMvc.perform(options("/api/v1/health").contextPath("/api/v1")
                        .header("Origin", "http://127.0.0.1:4173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:4173"));
        mockMvc.perform(options("/api/v1/health").contextPath("/api/v1")
                        .header("Origin", "https://unconfigured.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
