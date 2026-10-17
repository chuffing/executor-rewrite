package com.icbc.nbfh.entity.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CommandResultDTO {

    /** Shell 进程退出码 */
    private final int exitCode;

    /** 标准输出 */
    private final String stdout;

    /** 错误输出 */
    private final String stderr;

    /** 是否执行超时 */
    private final boolean timedOut;
}
