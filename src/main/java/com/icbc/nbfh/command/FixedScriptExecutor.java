package com.icbc.nbfh.command;

import com.icbc.nbfh.common.Enum.ScriptType;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.CommandResultDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 执行指定的Shell脚本。
 */
@Component
@RequiredArgsConstructor
public class FixedScriptExecutor {

    private final AgentProperties agentProperties;

    // 进程正常终止的等待时间：1秒
    private static final long PROCESS_STOP_TIMEOUT_SECONDS = 1;

    // 进程强制终止后的等待时间：1秒
    private static final long PROCESS_KILL_TIMEOUT_SECONDS = 1;

    // 等待读取Shell输出的超时时间：2秒
    private static final long OUTPUT_READ_TIMEOUT_SECONDS = 2;

    // 每个输出流最多保存64KB字符，防止日志过大
    private static final int MAX_OUTPUT_CHARS = 64 * 1024;


    /**
     * 执行指定的Shell脚本。
     */
    public CommandResultDTO execute(ScriptType scriptType, long timeoutSeconds)
            throws IOException, InterruptedException {

        return execute(scriptType, timeoutSeconds, new String[0]);
    }


    /**
     * 执行指定的Shell脚本并传入独立参数。
     */
    public CommandResultDTO execute(ScriptType scriptType,
                                    long timeoutSeconds,
                                    String... arguments)
            throws IOException, InterruptedException {

        // 1. 校验并获取脚本文件
        File scriptFile = resolveAndValidateScript(scriptType, timeoutSeconds);

        // 2. 创建Shell命令，参数由ProcessBuilder独立传递。
        List<String> command = new ArrayList<>();
        command.add("/bin/bash");
        command.add(scriptFile.getAbsolutePath());

        if (arguments != null) {
            for (String argument : arguments) {
                if (argument == null) {
                    throw new IllegalArgumentException("Shell参数不能为空");
                }

                command.add(argument);
            }
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);

        // 设置脚本工作目录
        processBuilder.directory(scriptFile.getParentFile());

        // 3. 启动进程
        Process process = processBuilder.start();

        // 4. 创建两个线程，分别读取标准输出和错误输出
        ExecutorService outputExecutor = Executors.newFixedThreadPool(
                2,
                task -> {
                    Thread thread = new Thread(task, "shell-output-reader");
                    thread.setDaemon(true);
                    return thread;
                }
        );

        try {
            // 不向Shell脚本提供标准输入
            process.getOutputStream().close();

            Future<String> stdoutFuture = outputExecutor.submit(
                    () -> readStream(process.getInputStream())
            );

            Future<String> stderrFuture = outputExecutor.submit(
                    () -> readStream(process.getErrorStream())
            );

            // 5. 等待脚本执行结束
            boolean finished = process.waitFor(
                    timeoutSeconds,
                    TimeUnit.SECONDS
            );

            // 6. 执行超时
            if (!finished) {

                terminateProcess(process);

                return new CommandResultDTO(
                        -1,
                        getOutputOrEmpty(stdoutFuture),
                        getOutputOrEmpty(stderrFuture),
                        true
                );
            }

            // 7. 正常结束，获取退出码及输出
            int exitCode = process.exitValue();

            String stdout = getOutput(stdoutFuture);
            String stderr = getOutput(stderrFuture);

            // 8. 返回执行结果
            return new CommandResultDTO(
                    exitCode,
                    stdout,
                    stderr,
                    false
            );

        } catch (InterruptedException e) {

            // 保留线程中断状态
            Thread.currentThread().interrupt();
            throw e;

        } finally {

            // 9. 确保进程不会继续运行
            terminateProcess(process);

            // 10. 关闭进程相关的输入输出流
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            closeQuietly(process.getOutputStream());

            // 11. 关闭输出读取线程池
            outputExecutor.shutdownNow();
        }
    }


    /**
     * 校验参数和脚本路径，返回合法的脚本文件。
     */
    private File resolveAndValidateScript(
            ScriptType scriptType,
            long timeoutSeconds
    ) throws IOException {

        // 校验脚本类型
        if (scriptType == null) {
            throw new IllegalArgumentException("脚本类型不能为空");
        }

        // 校验超时时间
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("脚本执行超时时间必须大于0");
        }

        // 检查脚本目录是否配置
        String scriptDir = agentProperties.getOperation().getScriptDir();

        if (scriptDir == null || scriptDir.trim().isEmpty()) {
            throw new IllegalArgumentException("Shell脚本目录未配置");
        }

        // 获取脚本目录
        File directory = new File(scriptDir.trim()).getCanonicalFile();

        // 检查脚本目录是否存在
        if (!directory.isDirectory()) {
            throw new FileNotFoundException("脚本目录不存在");
        }

        // 获取脚本文件
        File scriptFile = new File(
                directory,
                scriptType.getFileName()
        ).getCanonicalFile();

        // 检查脚本是否位于指定目录
        if (!directory.equals(scriptFile.getParentFile())) {
            throw new IllegalArgumentException("脚本路径不合法");
        }

        // 检查脚本文件是否存在
        if (!scriptFile.isFile()) {
            throw new FileNotFoundException(
                    "脚本文件不存在：" + scriptType.getFileName()
            );
        }

        // 检查脚本是否可读
        if (!scriptFile.canRead()) {
            throw new IOException(
                    "脚本文件不可读：" + scriptType.getFileName()
            );
        }

        return scriptFile;
    }


    /**
     * 读取Shell进程的输出。
     * 超过大小限制后不再保存内容，但继续读取，避免管道阻塞。
     */
    private String readStream(InputStream inputStream) throws IOException {

        StringBuilder output = new StringBuilder();

        boolean truncated = false;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            char[] buffer = new char[2048];
            int length;

            while ((length = reader.read(buffer)) != -1) {

                int remaining = MAX_OUTPUT_CHARS - output.length();

                if (remaining > 0) {
                    int appendLength = Math.min(length, remaining);
                    output.append(buffer, 0, appendLength);

                    if (appendLength < length) {
                        truncated = true;
                    }
                } else {
                    truncated = true;
                }
            }
        }

        if (truncated) {
            output.append("\n[输出内容过长，已截断]");
        }

        return output.toString();
    }


    /**
     * 获取异步读取任务的结果。
     */
    private String getOutput(Future<String> future)
            throws IOException, InterruptedException {

        try {
            return future.get(
                    OUTPUT_READ_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

        } catch (ExecutionException e) {
            throw new IOException("读取Shell输出失败", e.getCause());

        } catch (TimeoutException e) {
            throw new IOException("读取Shell输出超时", e);
        }
    }


    /**
     * 脚本超时后，尽量获取已有输出。
     * 如果无法读取，不影响任务超时结果的返回。
     */
    private String getOutputOrEmpty(Future<String> future)
            throws InterruptedException {

        try {
            return getOutput(future);

        } catch (IOException e) {
            return "";
        }
    }


    /**
     * 终止Shell进程。
     */
    private void terminateProcess(Process process) {

        if (!process.isAlive()) {
            return;
        }

        // 先尝试正常终止
        process.destroy();

        try {
            boolean stopped = process.waitFor(
                    PROCESS_STOP_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );

            // 如果仍未退出，则强制终止
            if (!stopped) {
                process.destroyForcibly();

                process.waitFor(
                        PROCESS_KILL_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                );
            }

        } catch (InterruptedException e) {

            // 即使线程被中断，也尝试强制终止进程
            process.destroyForcibly();

            Thread.currentThread().interrupt();
        }
    }


    /**
     * 安全关闭输入输出流。
     */
    private void closeQuietly(Closeable closeable) {

        if (closeable == null) {
            return;
        }

        try {
            closeable.close();
        } catch (IOException e) {
            // 资源清理阶段不覆盖原有执行结果或异常
        }
    }
}
