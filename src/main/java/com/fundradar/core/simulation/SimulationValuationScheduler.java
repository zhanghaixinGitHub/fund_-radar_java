package com.fundradar.core.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.UUID;

/** 仅开启收益刷新。账号来自现有绑定文件，不硬编码基金或扩大到其他账号。 */
@Component
@ConditionalOnProperty(name="simulation.valuation.enabled",havingValue="true")
public class SimulationValuationScheduler {
    private static final Logger LOGGER=LoggerFactory.getLogger(SimulationValuationScheduler.class);
    private final SimulationValuationService service;
    private final DataSource dataSource;
    private final ObjectMapper json;
    private final Path ownerFile;
    private boolean storageFailed;
    public SimulationValuationScheduler(SimulationValuationService service,DataSource dataSource,ObjectMapper json,
                                        @Value("${simulation.valuation.owner-file}") String ownerFile) {
        this.service=service; this.dataSource=dataSource; this.json=json; this.ownerFile=Path.of(ownerFile);
    }
    @Scheduled(fixedDelayString="${simulation.valuation.fixed-delay:PT1M}",initialDelayString="${simulation.valuation.initial-delay:PT10S}")
    public void tick() {
        if(storageFailed) return;
        try {
            UUID owner=UUID.fromString(json.readTree(Files.readAllBytes(ownerFile)).path("owner_user_id").asText());
            // 与旧结算共用全局互斥锁，写持仓时再用账号行锁，避免与手动交易交错。
            try(var connection=dataSource.getConnection();var lock=connection.prepareStatement("SELECT pg_try_advisory_lock(721105,1)")) {
                var acquired=lock.executeQuery(); acquired.next(); if(!acquired.getBoolean(1)) return;
                try {
                    var result=service.refresh(owner);
                    if(!result.unchanged()) LOGGER.info("SimulationValuationScheduler.tick >>> updated={}, waiting={}",result.updated(),result.waiting());
                } finally { try(var release=connection.prepareStatement("SELECT pg_advisory_unlock(721105,1)")) { release.execute(); } }
            }
        } catch(Exception error) {
            for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
                if(cause instanceof FileSystemException || cause instanceof SQLException sql
                        &&("58030".equals(sql.getSQLState())||"53100".equals(sql.getSQLState()))) storageFailed=true;
            }
            // 不输出绑定文件内容、账号、凭据或账户响应。失败不推进水位，存储错误停写等待处理。
            LOGGER.error("SimulationValuationScheduler.tick >>> valuation unavailable, storageStopped={}, errorType={}",storageFailed,error.getClass().getSimpleName());
        }
    }
}
