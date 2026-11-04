package com.icbc.nbfh.util;

import com.icbc.nbfh.common.exception.BusinessCode;
import com.icbc.nbfh.common.exception.BusinessException;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 平台凭证失效判定
 * 优先依据平台返回的业务码
 * 仅在错误码缺失/为通用码时回退到文案匹配，避免依赖平台中文提示
 *
 * 平台约定：
 *   30002 业务参数错误：无效的注册码 / Agent 身份不匹配；
 *   30003 未登录、30004 无权限：token 失效；
 *   30005 业务对象不存在：执行器尚未完成 Redis 安装与实例绑定（需重新注册补齐绑定
 */
public final class AuthFailureClassifier {

    /** token 失效/身份不匹配的错误码 */
    private static final Set<Integer> CREDENTIAL_INVALID_CODES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    BusinessCode.PARAMS_ERROR.getCode(),
                    BusinessCode.NOT_LOGIN_ERROR.getCode(),
                    BusinessCode.NO_AUTH_ERROR.getCode())));

    /** 实例绑定丢失的错误码 */
    private static final Set<Integer> BINDING_LOST_CODES = Collections.unmodifiableSet(
            new HashSet<>(Collections.singletonList(BusinessCode.NOT_FOUND_ERROR.getCode())));

    private static final String MSG_TOKEN = "token";
    private static final String MSG_IDENTITY_MISMATCH = "身份不匹配";
    private static final String MSG_INVALID_REGISTER_CODE = "无效的注册码";
    private static final String MSG_NOT_BOUND = "尚未完成 Redis 安装与实例绑定";

    private AuthFailureClassifier() {
    }

    /** token 失效 / 身份不匹配 */
    public static boolean isCredentialInvalid(Throwable error) {
        return match(error, CREDENTIAL_INVALID_CODES, false);
    }

    /** 凭证失效或实例绑定丢失（采集上报场景） */
    public static boolean isCredentialOrBindingInvalid(Throwable error) {
        return match(error, CREDENTIAL_INVALID_CODES, true);
    }

    private static boolean match(Throwable error, Set<Integer> invalidCodes, boolean includeBindingLost) {
        if (error == null) {
            return false;
        }
        Integer code = error instanceof BusinessException
                ? ((BusinessException) error).getCode() : null;
        if (invalidCodes.contains(code)) {
            return true;
        }
        if (BINDING_LOST_CODES.contains(code)) {
            return includeBindingLost;
        }
        // 平台未返回业务码（旧版本/通用 30001）时才回退文案匹配
        return matchesLegacyMessage(error.getMessage(), includeBindingLost);
    }

    private static boolean matchesLegacyMessage(String message, boolean includeBindingLost) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        if (message.toLowerCase().contains(MSG_TOKEN)
                || message.contains(MSG_IDENTITY_MISMATCH)
                || message.contains(MSG_INVALID_REGISTER_CODE)) {
            return true;
        }
        return includeBindingLost && message.contains(MSG_NOT_BOUND);
    }
}
