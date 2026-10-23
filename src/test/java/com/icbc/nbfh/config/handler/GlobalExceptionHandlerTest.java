package com.icbc.nbfh.config.handler;

import com.icbc.nbfh.common.Enum.ParameterErrorCode;
import com.icbc.nbfh.common.ResultBean;
import com.icbc.nbfh.common.exception.ParameterException;
import com.icbc.nbfh.entity.VO.ErrorResultVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证全局异常处理器遵循行内统一响应协议。
 */
class GlobalExceptionHandlerTest {

    /**
     * 参数异常必须通过ResultBean返回400业务码和完整错误信息。
     */
    @Test
    void shouldReturnUnifiedResultForParameterException() {

        // 1. 准备统一异常处理器和参数异常。
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ParameterException exception = new ParameterException(
                ParameterErrorCode.TASK_PARAM_INVALID,
                "任务报文不能为空"
        );

        // 2. 交给全局异常处理器转换为统一响应体。
        ResultBean<ErrorResultVO> response =
                handler.handleParameterException(exception);

        // 3. 验证ResultBean的code、msg和data完整对应参数异常。
        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getCode());
        assertEquals(ParameterErrorCode.TASK_PARAM_INVALID.getMsg(), response.getMsg());
        assertNotNull(response.getData());
        assertEquals(ParameterErrorCode.TASK_PARAM_INVALID.getCode(),
                response.getData().getCode());
        assertEquals(ParameterErrorCode.TASK_PARAM_INVALID.getMsg(),
                response.getData().getMsg());
        assertEquals("任务报文不能为空", response.getData().getErrorMsg());
    }
}
