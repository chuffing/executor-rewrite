package com.icbc.nbfh.util;

import com.icbc.nbfh.common.exception.BusinessCode;
import com.icbc.nbfh.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平台凭证失效判定单元测试。
 */
class AuthFailureClassifierTest {

    @Test
    @DisplayName("平台认证/身份错误码应判定为凭证失效")
    void shouldTreatAuthCodesAsCredentialInvalid() {
        assertTrue(AuthFailureClassifier.isCredentialInvalid(
                new BusinessException(BusinessCode.PARAMS_ERROR.getCode(), "无效的注册码")));
        assertTrue(AuthFailureClassifier.isCredentialInvalid(
                new BusinessException(BusinessCode.NOT_LOGIN_ERROR.getCode(), "未登录")));
        assertTrue(AuthFailureClassifier.isCredentialInvalid(
                new BusinessException(BusinessCode.NO_AUTH_ERROR.getCode(), "无权限")));
    }

    @Test
    @DisplayName("绑定丢失错误码只在采集场景视为需要重新注册")
    void shouldTreatBindingLostOnlyWhenRequested() {
        BusinessException bindingLost = new BusinessException(
                BusinessCode.NOT_FOUND_ERROR.getCode(), "执行器尚未完成 Redis 安装与实例绑定");

        assertFalse(AuthFailureClassifier.isCredentialInvalid(bindingLost));
        assertTrue(AuthFailureClassifier.isCredentialOrBindingInvalid(bindingLost));
    }

    @Test
    @DisplayName("通用错误码按文案兜底，其它错误不误判")
    void shouldFallbackToMessageOnlyForGenericErrors() {
        assertTrue(AuthFailureClassifier.isCredentialInvalid(
                new BusinessException(BusinessCode.OPERATION_ERROR.getCode(), "invalid token")));
        assertTrue(AuthFailureClassifier.isCredentialInvalid(
                new BusinessException(BusinessCode.OPERATION_ERROR.getCode(), "Agent 身份不匹配")));
        assertFalse(AuthFailureClassifier.isCredentialInvalid(
                new BusinessException(BusinessCode.OPERATION_ERROR.getCode(), "连接平台超时")));
        assertFalse(AuthFailureClassifier.isCredentialInvalid(null));
    }
}
