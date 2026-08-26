package com.fundradar.core.portfolio.importer;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** 仅在命令显式开启后导入本机文件，默认应用启动不会读取任何持仓文件。 */
@Component
@ConditionalOnProperty(prefix = "app.portfolio-import", name = "enabled", havingValue = "true")
public class LocalPortfolioSnapshotImportRunner implements ApplicationRunner {

    private final LocalPortfolioImportProperties properties;
    private final LocalPortfolioSnapshotImporter importer;

    public LocalPortfolioSnapshotImportRunner(
            LocalPortfolioImportProperties properties,
            LocalPortfolioSnapshotImporter importer
    ) {
        this.properties = properties;
        this.importer = importer;
    }

    @Override
    /** 校验显式文件参数后执行一次幂等导入。 */
    public void run(ApplicationArguments args) {
        if (properties.getFile() == null || properties.getFile().isBlank()) {
            throw new IllegalArgumentException("app.portfolio-import.file must be set when import is enabled");
        }
        importer.importFromFile(Path.of(properties.getFile()));
    }
}
