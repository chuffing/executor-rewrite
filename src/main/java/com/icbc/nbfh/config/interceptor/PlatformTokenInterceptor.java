package com.icbc.nbfh.config.interceptor;

import cn.hutool.http.HttpStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.nbfh.common.Enum.AgentTaskResponseCodeEnum;
import com.icbc.nbfh.common.ResultBean;
import com.icbc.nbfh.entity.VO.ErrorResultVO;
import com.icbc.nbfh.service.RegisterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 校验管理平台调用执行器接口时携带的 Bearer Token。
 */
@Component
@RequiredArgsConstructor
public class PlatformTokenInterceptor implements HandlerInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final RegisterService registerService;
    private final ObjectMapper objectMapper;


    /**
     * 校验平台请求携带的Bearer Token。
     */
    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws IOException {
        String token = registerService.getToken();
        String authorization = request.getHeader(AUTHORIZATION_HEADER);

        if (registerService.isRegistered()
                && (BEARER_PREFIX + token).equals(authorization)) {
            return true;
        }

        response.setStatus(HttpStatus.HTTP_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ErrorResultVO result = new ErrorResultVO(
                String.valueOf(
                        AgentTaskResponseCodeEnum.AUTHENTICATION_REJECTED
                                .getCode()
                ),
                "身份校验失败",
                "平台令牌无效"
        );
        objectMapper.writeValue(
                response.getWriter(),
                ResultBean.resultBean(
                        result,
                        AgentTaskResponseCodeEnum.AUTHENTICATION_REJECTED
                                .getCode(),
                        result.getMsg()
                )
        );
        return false;
    }
}
