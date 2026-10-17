package com.icbc.nbfh.config.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.nbfh.service.RegisterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformTokenInterceptorTest {

    private RegisterService registerService;

    private PlatformTokenInterceptor interceptor;

    /**
     * 创建被测试的Token拦截器。
     */
    @BeforeEach
    void setUp() {
        registerService = mock(RegisterService.class);

        interceptor = new PlatformTokenInterceptor(
                registerService,
                new ObjectMapper()
        );
    }

    /**
     * 验证正确Token能够通过拦截器。
     */
    @Test
    void shouldAllowRequestWithCorrectToken()
            throws Exception {

        when(registerService.isRegistered())
                .thenReturn(true);

        when(registerService.getToken())
                .thenReturn("agent-token");

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.addHeader(
                "Authorization",
                "Bearer agent-token"
        );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        assertTrue(
                interceptor.preHandle(
                        request,
                        response,
                        new Object()
                )
        );
    }

    /**
     * 验证错误Token返回统一错误响应。
     */
    @Test
    void shouldRejectRequestWithWrongToken()
            throws Exception {

        when(registerService.isRegistered())
                .thenReturn(true);

        when(registerService.getToken())
                .thenReturn("agent-token");

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.addHeader(
                "Authorization",
                "Bearer wrong-token"
        );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        assertFalse(
                interceptor.preHandle(
                        request,
                        response,
                        new Object()
                )
        );

        assertEquals(401, response.getStatus());

        assertEquals(
                HttpStatus.UNAUTHORIZED.name(),
                new ObjectMapper()
                        .readTree(
                                response.getContentAsString()
                        )
                        .get("code")
                        .asText()
        );

        assertEquals(
                "身份校验失败",
                new ObjectMapper()
                        .readTree(
                                response.getContentAsString()
                        )
                        .get("msg")
                        .asText()
        );

        assertEquals(
                "平台令牌无效",
                new ObjectMapper()
                        .readTree(
                                response.getContentAsString()
                        )
                        .get("errorMsg")
                        .asText()
        );

        assertFalse(
                new ObjectMapper()
                        .readTree(
                                response.getContentAsString()
                        )
                        .has("taskNo")
        );
    }

    /**
     * 验证执行器未注册时拒绝平台请求。
     */
    @Test
    void shouldRejectRequestWhenAgentNotRegistered()
            throws Exception {

        when(registerService.isRegistered())
                .thenReturn(false);

        MockHttpServletRequest request =
                new MockHttpServletRequest();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        assertFalse(
                interceptor.preHandle(
                        request,
                        response,
                        new Object()
                )
        );

        assertEquals(401, response.getStatus());
    }
}
