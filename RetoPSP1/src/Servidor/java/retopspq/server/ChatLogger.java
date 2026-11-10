package retopspq.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Log global del servidor: guarda en un fichero las entradas, salidas y mensajes
 * del chat, una linea por evento y con fecha y hora.
 */
final class ChatLogger {
    /** Fichero donde se escribe el log. */
    private final Path logFile;

    /**
     * Crea el log.
     *
     * @param logFile fichero donde se escribe; se crea si no existe
     */
    ChatLogger(Path logFile) {
        this.logFile = logFile;
    }

    /**
     * Anade una linea al log con la fecha y hora actuales.
     * Es synchronized para que las lineas de varios hilos no se mezclen.
     * Si el fichero no se puede escribir, se avisa por consola y el chat sigue funcionando.
     *
     * @param event texto del evento, por ejemplo {@code "ENTRA | ana"}
     */
    synchronized void log(String event) {
        String line = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                + " | " + event.replace("\r", "\\r").replace("\n", "\\n")
                + System.lineSeparator();
        try {
            Files.writeString(logFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            System.err.println("No se pudo escribir en " + logFile + ": " + exception.getMessage());
        }
    }
}