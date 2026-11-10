package Servidor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

final class ChatLogger {
    private final Path logFile;

    ChatLogger(Path logFile) {
        this.logFile = logFile;
    }

    synchronized void log(String event) throws IOException {
        String line = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                + " | " + event.replace("\r", "\\r").replace("\n", "\\n")
                + System.lineSeparator();
        Files.writeString(logFile, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }
}