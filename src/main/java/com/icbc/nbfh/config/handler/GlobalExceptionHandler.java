package com.icbc.nbfh.config.handler;

import com.icbc.nbfh.common.ResultBean;
import com.icbc.nbfh.common.exception.BusinessCode;
import com.icbc.nbfh.common.exception.BusinessException;
import com.icbc.nbfh.common.exception.ParameterException;
import com.icbc.nbfh.common.exception.ParameterCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 接口统一异常处理器。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理参数校验异常。
     */
    @ExceptionHandler(ParameterException.class)
    public ResultBean<Void> handleParameterException(
            ParameterException exception) {
        return ResultBean.error(
                exception.getCode(),
                exception.getErrorMsg()
        );
    }

    /**
     * 处理业务异常。
     */
    @ExceptionHandler(BusinessException.class)
    public ResultBean<Void> handleBusinessException(
            BusinessException exception) {
        String message = exception.getErrorMsg();
        if (message == null || message.trim().isEmpty()) {
            message = exception.getMessage();
        }
        return ResultBean.error(exception.getCode(), message);
    }

    /**
     * 处理上传文件或整个multipart请求超过大小上限的情况。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResultBean<Void> handleUploadTooLarge() {
        return ResultBean.error(
                ParameterCode.PARAM_FILE_SIZE_ERROR.getCode(),
                "安装包最大50 MiB，上传请求总大小最大51 MiB"
        );
    }

    /**
     * 处理未预期的系统异常。
     */
    @ExceptionHandler(Exception.class)
    public ResultBean<Void> handleException(Exception exception) {
        log.error("系统异常", exception);
        return ResultBean.error(
                BusinessCode.SYSTEM_ERROR.getCode(),
                BusinessCode.SYSTEM_ERROR.getMsg()
        );
    }
}
