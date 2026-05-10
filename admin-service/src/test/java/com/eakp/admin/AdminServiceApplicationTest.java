package com.eakp.admin;

import com.eakp.admin.service.AnalyticsService;
import com.eakp.admin.service.WorkspaceAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminServiceApplicationTest {

    @Autowired  MockMvc mockMvc;
    @MockBean   AnalyticsService analyticsService;
    @MockBean   WorkspaceAdminService workspaceAdminService;

    @Test
    void unauthenticated_analytics_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/overview"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticated_admin_returns_401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/health"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    void health_endpoint_is_public() throws Exception {
        mockMvc.perform(get("/actuator/health"))
               .andExpect(status().isOk());
    }
}
