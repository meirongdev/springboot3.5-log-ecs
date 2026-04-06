package dev.meirong.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DemoControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void helloReturns200() throws Exception {
        mvc.perform(get("/api/hello"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Spring Boot 3.5")));
    }

    @Test
    void orderReturnsJsonWithId() throws Exception {
        mvc.perform(get("/api/orders/123"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"orderId\":\"123\"")));
    }

    @Test
    void errorEndpointReturns200WithErrorBody() throws Exception {
        // Intentional error endpoint should still return 200 (handled internally)
        mvc.perform(get("/api/error"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("downstream failure")));
    }

    @Test
    void asyncDemoReturnsCompletionMessage() throws Exception {
        mvc.perform(get("/api/async-demo"))
                .andExpect(status().isOk())
                .andExpect(content().string("async demo complete"));
    }

    @Test
    void requestIdHeaderPropagated() throws Exception {
        mvc.perform(get("/api/hello").header("X-Request-ID", "test-req-001"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", "test-req-001"));
    }
}
