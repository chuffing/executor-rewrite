package com.icbc.nbfh.config.handler;

import com.icbc.nbfh.common.ResultBean;
import com.icbc.nbfh.common.exception.ParameterException;
import com.icbc.nbfh.common.Enum.ParameterErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import com.icbc.nbfh.entity.VO.ErrorResultVO;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 接口统一异常处理器。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理参数校验异常。
     */
    @ExceptionHandler(ParameterException.class)
    public ResultBean<ErrorResultVO> handleParameterException(
            ParameterException exception) {

        ErrorResultVO result = new ErrorResultVO(
                exception.getCode(),
                exception.getMsg(),
                exception.getErrorMsg()
        );

        return new ResultBean<>(
                HttpStatus.BAD_REQUEST.value(),
                result.getMsg(),
                result);
    }
    /**
     * 处理上传文件或整个multipart请求超过大小上限的情况。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResultBean<ErrorResultVO> handleUploadTooLarge(
            MaxUploadSizeExceededException exception) {

        ErrorResultVO result = new ErrorResultVO(
                        ParameterErrorCode.PACKAGE_FILE_INVALID.getCode(),
                        ParameterErrorCode.PACKAGE_FILE_INVALID.getMsg(),
                        "安装包最大50 MiB，上传请求总大小最大51 MiB"
                );

        return new ResultBean<>(
                HttpStatus.REQUEST_ENTITY_TOO_LARGE.value(),
                result.getMsg(),
                result
        );
    }
}
