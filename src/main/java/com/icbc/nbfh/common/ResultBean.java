package com.icbc.nbfh.common;

import cn.hutool.http.HttpStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 执行器统一响应对象。
 *
 * @param <T> 响应数据类型
 */
@Accessors(chain = true)
//@AllArgsConstructor
public class ResultBean<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 响应码。 */
    @Getter
    @Setter
    private int code = HttpStatus.HTTP_OK;

    /** 响应信息。 */
    @Getter
    @Setter
    private String msg = "success";

    /** 响应数据。 */
    @Getter
    @Setter
    private T data;

    public ResultBean() {
        super();
    }

    public ResultBean(T data) {
        super();
        this.data = data;
    }

    public ResultBean(T data, String msg) {
        super();
        this.data = data;
        this.msg = msg;
    }

    public ResultBean(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public ResultBean(Throwable throwable) {
        super();
        this.code = HttpStatus.HTTP_INTERNAL_ERROR;
        this.msg = throwable.getMessage();
    }

    /**
     * 按指定响应码和响应信息创建响应对象。
     */
    public static <T> ResultBean<T> resultBean(T data, int code, String msg) {
        ResultBean<T> resultBean = new ResultBean<>();
        resultBean.setCode(code);
        resultBean.setData(data);
        resultBean.setMsg(msg);
        return resultBean;
    }

    /**
     * 创建成功响应。
     */
    public static <T> ResultBean<T> success(T data) {
        return new ResultBean<>(data);
    }
}
