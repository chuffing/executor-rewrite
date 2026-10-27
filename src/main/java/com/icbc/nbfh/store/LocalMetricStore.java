package com.icbc.nbfh.store;

import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson.JSON;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.DTO.MetricDataDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 采集数据本地留存
 * 采集后先落本地文件，上报成功再删除；上报失败保留，下次采集前重传，
 * 防止平台/网络短时不可用导致数据丢失
 */
@Slf4j
@Component
public class LocalMetricStore {

    private static final String FILE_PREFIX = "metric_";
    private static final String FILE_SUFFIX = ".json";

    private final AgentProperties properties;

    private File pendingDir;

    public LocalMetricStore(AgentProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        String dataDir = properties.getDataDir();
        pendingDir = new File(dataDir == null || dataDir.trim().isEmpty()
                ? "./data" : dataDir.trim(), "metrics/pending");
        if (!pendingDir.exists() && !pendingDir.mkdirs()) {
            log.error("创建本地采集数据目录失败: {}", pendingDir.getAbsolutePath());
        } else {
            log.info("本地采集数据目录: {}，当前待重传 {} 条",
                    pendingDir.getAbsolutePath(), pendingCount());
        }
        cleanExpired();
    }

    /**
     * 保存一条采集数据到本地
     */
    public File save(MetricDataDTO metric) {
        long ts = metric.getCollectTime() != null
                ? metric.getCollectTime() : System.currentTimeMillis();
        File file = new File(pendingDir, FILE_PREFIX + ts + FILE_SUFFIX);
        try {
            FileUtil.writeString(JSON.toJSONString(metric), file, StandardCharsets.UTF_8);
            log.debug("采集数据已本地留存: {}", file.getName());
        } catch (Exception e) {
            log.error("采集数据本地留存失败", e);
        }
        return file;
    }

    /**
     * 读取待重传的采集数据（按时间升序）
     */
    public List<MetricDataDTO> loadPending() {
        return loadPending(Integer.MAX_VALUE);
    }

    /**
     * 读取最多 limit 条待重传数据（按文件名时间升序）
     * @param limit
     * @return
     */
    public List<MetricDataDTO> loadPending(int limit) {
        List<MetricDataDTO> result = new ArrayList<>();
        if (pendingDir == null || !pendingDir.exists()) {
            return result;
        }
        File[] files = pendingDir.listFiles((dir, name) -> name.endsWith(FILE_SUFFIX));
        if (files == null || files.length == 0) {
            return result;
        }

        Arrays.sort(files, Comparator.comparing(File::getName));
        int loaded = 0;
        for (File file : files) {
            if (loaded >= limit) {
                break;
            }
            try {
                String content = FileUtil.readString(file, StandardCharsets.UTF_8);
                MetricDataDTO metric = JSON.parseObject(content, MetricDataDTO.class);
                if (metric != null) {
                    result.add(metric);
                    loaded++;
                }
            } catch (Exception e) {
                // 损坏文件无法重传，直接删除避免每轮重复报错
                log.error("读取本地采集数据失败，删除损坏文件: {}", file.getName(), e);
                if (!file.delete()) {
                    log.warn("删除损坏文件失败: {}", file.getName());
                }
            }
        }
        return result;
    }

    /**
     * 上报成功后删除对应文件
     * @param metric
     */
    public void remove(MetricDataDTO metric) {
        long ts = metric.getCollectTime() != null
                ? metric.getCollectTime() : System.currentTimeMillis();
        File file = new File(pendingDir, FILE_PREFIX + ts + FILE_SUFFIX);
        if (file.exists() && !file.delete()) {
            log.warn("删除已上报数据文件失败: {}", file.getName());
        }
    }

    /**
     * 待重传数据条数
     */
    public int pendingCount() {
        if (pendingDir == null) {
            return 0;
        }
        File[] files = pendingDir.listFiles((dir, name) -> name.endsWith(FILE_SUFFIX));
        return files == null ? 0 : files.length;
    }

    /**
     * 清理超过保留天数的过期数据，防止长期离线时磁盘无限堆积
     */
    public void cleanExpired() {
        if (pendingDir == null || !pendingDir.exists()) {
            return;
        }
        int retainDays = Math.max(1, properties.getDataRetainDays());
        long expireMs = retainDays * 86400000L;
        long now = System.currentTimeMillis();
        File[] files = pendingDir.listFiles((dir, name) -> name.endsWith(FILE_SUFFIX));
        if (files == null) {
            return;
        }
        int cleaned = 0;
        for (File file : files) {
            if (now - file.lastModified() > expireMs) {
                if (file.delete()) {
                    cleaned++;
                }
            }
        }
        if (cleaned > 0) {
            log.info("清理过期本地采集数据 {} 条", cleaned);
        }
    }

    /** 每日凌晨清理一次 */
    @Scheduled(cron = "0 10 3 * * ?")
    public void scheduledClean() {
        cleanExpired();
    }
}
