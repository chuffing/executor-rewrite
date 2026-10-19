package com.icbc.nbfh.client;

import com.icbc.nbfh.entity.DTO.TaskResultReportDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 验证任务结果只有在平台业务状态成功时才算上报完成。
 */
class PlatformClientTaskResultTest {

    private MockRestServiceServer server;

    private PlatformClient client;

    @BeforeEach
    void prepare() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new PlatformClient(restTemplate);
    }

    /** 平台返回统一成功状态码200时确认上报成功。 */
    @Test
    void shouldAcceptSuccessfulBusinessResponse() {
        server.expect(once(), requestTo("http://platform/api/task/report"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"code\":200,\"msg\":\"success\",\"data\":null}",
                        MediaType.APPLICATION_JSON
                ));

        client.reportTaskResult(
                report(),
                "http://platform",
                "test-token"
        );

        server.verify();
    }

    /** HTTP成功但平台业务处理失败时仍然需要保留并重试。 */
    @Test
    void shouldRejectFailedBusinessResponse() {
        server.expect(once(), requestTo("http://platform/api/task/report"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"code\":40000,\"data\":null,"
                                + "\"msg\":\"任务不存在\"}",
                        MediaType.APPLICATION_JSON
                ));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> client.reportTaskResult(
                        report(),
                        "http://platform",
                        "test-token"
                )
        );
        assertEquals("任务不存在", exception.getMessage());

        server.verify();
    }

    /** 构造平台任务结果报文。 */
    private TaskResultReportDTO report() {
        return new TaskResultReportDTO(
                "TASK202610180001",
                1,
                "agent-001",
                "STOP_REDIS",
                "SUCCESS",
                "{\"success\":true}"
        );
    }
}
