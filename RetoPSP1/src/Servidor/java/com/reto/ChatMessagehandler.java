package retopspq.server;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Valida y distribuye los mensajes públicos y privados del protocolo del chat.
 * Esta clase interpreta los frames que reciben los clientes.
 */
final class ChatMessageHandler {
    // Longitud máxima que admitirán los mensajes del chat.
    private static final int MAX_MESSAGE_LENGTH = 2_000;

    // Registro central de clientes para encontrar destinatarios y difundir mensajes.
    private final ClientRegistry clients;

    // Logger para guardar la actividad del protocolo en el fichero de registro.
    private final ChatLogger logger;

    // Último mensaje registrado para mostrarse en la consola de estadísticas.
    private final AtomicReference<String> lastMessage = new AtomicReference<>("Ninguno");

    ChatMessageHandler(ClientRegistry clients, ChatLogger logger) {
        this.clients = clients;
        this.logger = logger;
    }

    // Analiza el contenido recibido de un cliente y decide qué acción tomar.
    void handleFrame(ClientConnection sender, String[] frame) throws IOException {
        if (frame == null || frame.length == 0) {
            sender.send(new String[] {"ERROR", "Formato de mensaje no valido."});
            return;
        }

        try {
            if ("PUBLIC".equals(frame[0]) && frame.length == 2) {
                handlePublicMessage(sender, frame[1]);
            } else if ("PRIVATE".equals(frame[0]) && frame.length == 3) {
                handlePrivateMessage(sender, frame[1], frame[2]);
            } else {
                sender.send(new String[] {"ERROR", "Formato de mensaje no valido."});
            }
        } catch (IllegalArgumentException exception) {
            sender.send(new String[] {"ERROR", "El mensaje contiene datos no validos."});
        }
    }

    // Devuelve el último mensaje procesado.
    String lastMessage() {
        return lastMessage.get();
    }

    // Procesa un mensaje público y lo difunde a todos los clientes conectados.
    private void handlePublicMessage(ClientConnection sender, String message) throws IOException {
        if (!isValidMessage(message)) {
            sender.send(new String[] {"ERROR", "El mensaje no puede estar vacio ni superar 2000 caracteres."});
            return;
        }
        logger.log("PUBLICO | " + sender.username() + " | " + logSafe(message));
        lastMessage.set("Publico de " + sender.username() + ": " + logSafe(message));
        clients.broadcast(new String[] {"PUBLIC", sender.username(), message}, null);
    }

    // Procesa un mensaje privado y lo entrega solo al destinatario indicado.
    private void handlePrivateMessage(ClientConnection sender, String recipientName, String message)
            throws IOException {
        if (!isValidMessage(message)) {
            sender.send(new String[] {"ERROR", "El mensaje no puede estar vacio ni superar 2000 caracteres."});
            return;
        }
        ClientConnection recipient = clients.find(recipientName);
        if (recipient == null) {
            sender.send(new String[] {"ERROR", "El destinatario no esta conectado."});
            return;
        }
        if (recipient == sender) {
            sender.send(new String[] {"ERROR", "No puedes enviarte un mensaje privado a ti mismo."});
            return;
        }

        logger.log("PRIVADO | " + sender.username() + " para " + recipient.username() + " | " + logSafe(message));
        lastMessage.set("Privado de " + sender.username() + " para " + recipient.username() + ": "
                + logSafe(message));
        String[] frame = new String[] {"PRIVATE", sender.username(), recipient.username(), message};
        try {
            recipient.send(frame);
        } catch (IOException exception) {
            clients.unregister(recipient);
            sender.send(new String[] {"ERROR", "No se pudo entregar el mensaje: el destinatario se ha desconectado."});
            return;
        }
        sender.send(frame);
    }

    // Comprueba si un mensaje cumple las reglas del protocolo.
    private static boolean isValidMessage(String message) {
        return !message.trim().isEmpty() && message.length() <= MAX_MESSAGE_LENGTH;
    }

    // Ajusta los caracteres para que sigan siendo legibles en el fichero de log.
    private static String logSafe(String value) {
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }
}
