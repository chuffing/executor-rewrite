package com.icbc.nbfh.config.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icbc.nbfh.common.ResultBean;
import com.icbc.nbfh.entity.VO.ErrorResultVO;
import com.icbc.nbfh.service.RegisterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 校验管理平台调用执行器接口时携带的 Bearer Token
 */
@Component
@RequiredArgsConstructor
public class PlatformTokenInterceptor implements HandlerInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final RegisterService registerService;
    private final ObjectMapper objectMapper;


    /**
     * 校验平台请求携带的Bearer Token
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

        // 身份校验失败，直接返回401，不再进入Controller
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ResultBean<ErrorResultVO> result = new ResultBean<>(
                HttpStatus.UNAUTHORIZED.value(),
                "身份校验失败",
                new ErrorResultVO(
                        String.valueOf(HttpStatus.UNAUTHORIZED.value()),
                        "身份校验失败",
                        "平台令牌无效")
        );

        objectMapper.writeValue(response.getWriter(), result);
        return false;
    }
}
