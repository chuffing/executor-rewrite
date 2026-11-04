package com.icbc.nbfh.store;

import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.icbc.nbfh.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 执行器注册凭证的本地持久化
 * 负责 token 文件的读写、旧格式兼容与文件权限收敛，不参与注册流程决策
 *
 * 当前格式为 JSON：{token, hostIp, executorPort, instanceId, bound}；

 */
@Slf4j
@Component
public class AgentStateStore {

    private final AgentProperties properties;

    public AgentStateStore(AgentProperties properties) {
        this.properties = properties;
    }

    /**
     * 读取本地注册状态
     *
     * @return 无文件/内容为空/损坏时返回 null（损坏文件会被删除，等待重新注册）
     */
    public AgentState load() {
        String tokenFilePath = properties.getPlatform().getTokenFile();
        if (!StringUtils.hasText(tokenFilePath)) {
            return null;
        }
        File tokenFile = new File(tokenFilePath.trim());
        if (!tokenFile.isFile()) {
            return null;
        }
        try {
            String content = FileUtil.readString(tokenFile, StandardCharsets.UTF_8).trim();
            if (!StringUtils.hasText(content)) {
                return null;
            }
            return content.startsWith("{") ? parseJson(content, tokenFile) : parseLegacy(content, tokenFile);
        } catch (Exception e) {
            log.warn("读取本地 token 文件失败，将重新注册: {}", e.getMessage());
            delete();
            return null;
        }
    }

    /**
     * 持久化注册状态
     */
    public void save(AgentState state) {
        String path = properties.getPlatform().getTokenFile();
        if (!StringUtils.hasText(path) || state == null) {
            return;
        }
        File tokenFile = new File(path.trim());
        try {
            FileUtil.mkParentDirs(tokenFile);
            JSONObject content = new JSONObject(true);
            content.put("token", state.getToken());
            content.put("hostIp", state.getHostIp());
            content.put("executorPort", state.getExecutorPort());
            content.put("instanceId", state.getInstanceId());
            content.put("bound", state.isBound());
            content.put("registeredAt", System.currentTimeMillis());
            FileUtil.writeString(content.toJSONString(), tokenFile, StandardCharsets.UTF_8);
            restrictFilePermission(tokenFile);
        } catch (Exception e) {
            log.warn("持久化 token 失败（重启后会重新注册）: {}", e.getMessage());
        }
    }

    /**
     * 删除本地凭证文件
     */
    public void delete() {
        String path = properties.getPlatform().getTokenFile();
        if (!StringUtils.hasText(path)) {
            return;
        }
        try {
            File tokenFile = new File(path.trim());
            if (tokenFile.exists() && !tokenFile.delete()) {
                log.warn("删除本地 token 文件失败: {}", tokenFile.getAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("删除本地 token 文件异常: {}", e.getMessage());
        }
    }

    private AgentState parseJson(String content, File tokenFile) {
        JSONObject state = JSON.parseObject(content);
        String savedToken = state.getString("token");
        if (!StringUtils.hasText(savedToken)) {
            savedToken = state.getString("executorToken");
        }
        if (!StringUtils.hasText(savedToken)) {
            return null;
        }

        String savedInstanceId = state.getString("instanceId");
        Boolean savedBound = state.getBoolean("bound");
        // 兼容旧版本文件：无 bound 时按 instanceId 是否为空判断
        boolean bound = savedBound != null ? savedBound : StringUtils.hasText(savedInstanceId);
        if (!StringUtils.hasText(savedInstanceId)) {
            bound = false;
        }
        log.info("从 {} 读取到执行器状态（hostIp={}, executorPort={}, bound={}, instanceId={}）",
                tokenFile.getAbsolutePath(), state.getString("hostIp"), state.getString("executorPort"),
                bound, savedInstanceId);
        return new AgentState(savedToken.trim(), state.getString("hostIp"),
                state.getString("executorPort"), savedInstanceId, bound);
    }

    private AgentState parseLegacy(String content, File tokenFile) {
        // 兼容历史格式：agentId|token 或纯 token，绑定信息缺失，下次注册时补齐
        String legacyToken = content.contains("|")
                ? content.substring(content.lastIndexOf('|') + 1).trim()
                : content.trim();
        if (!StringUtils.hasText(legacyToken)) {
            return null;
        }
        log.info("从 {} 读取到旧格式执行器 token，将重新注册以补齐绑定关系",
                tokenFile.getAbsolutePath());
        return new AgentState(legacyToken, null, null, "", false);
    }

    /** token 属于敏感信息，Linux 下尽量设置为仅属主可读写 */
    private void restrictFilePermission(File file) {
        try {
            Files.setPosixFilePermissions(file.toPath(),
                    PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
            // Windows 等非 POSIX 文件系统忽略
        }
    }
}
