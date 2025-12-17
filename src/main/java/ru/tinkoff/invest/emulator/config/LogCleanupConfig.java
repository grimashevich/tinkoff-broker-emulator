package ru.tinkoff.invest.emulator.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Configuration for log file rotation management.
 * Cleans up old session log files on application startup, keeping only the most recent ones.
 */
@Slf4j
@Configuration
public class LogCleanupConfig {

    private static final int MAX_LOG_FILES = 50;
    private static final String LOG_DIR = "logs";
    private static final String LOG_FILE_PREFIX = "emulator_";
    private static final String LOG_FILE_SUFFIX = ".log";

    @PostConstruct
    public void cleanupOldLogs() {
        Path logsPath = Paths.get(LOG_DIR);

        if (!Files.exists(logsPath)) {
            log.debug("Logs directory does not exist yet: {}", logsPath.toAbsolutePath());
            return;
        }

        try (Stream<Path> logFiles = Files.list(logsPath)) {
            List<Path> sessionLogs = logFiles
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(LOG_FILE_PREFIX))
                    .filter(p -> p.getFileName().toString().endsWith(LOG_FILE_SUFFIX))
                    .sorted(Comparator.comparing(this::getFileLastModified).reversed())
                    .toList();

            if (sessionLogs.size() > MAX_LOG_FILES) {
                int filesToDelete = sessionLogs.size() - MAX_LOG_FILES;
                log.info("Found {} session log files, removing {} oldest files (keeping {})",
                        sessionLogs.size(), filesToDelete, MAX_LOG_FILES);

                // Delete oldest files (they are at the end of the sorted list)
                for (int i = MAX_LOG_FILES; i < sessionLogs.size(); i++) {
                    Path oldLog = sessionLogs.get(i);
                    try {
                        Files.delete(oldLog);
                        log.debug("Deleted old log file: {}", oldLog.getFileName());
                    } catch (IOException e) {
                        log.warn("Failed to delete old log file: {} - {}", oldLog.getFileName(), e.getMessage());
                    }
                }
            } else {
                log.debug("Session log files count ({}) is within limit ({})", sessionLogs.size(), MAX_LOG_FILES);
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup old log files: {}", e.getMessage());
        }
    }

    private long getFileLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
