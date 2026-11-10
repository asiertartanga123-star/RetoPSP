package retopspq.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Valida y reparte los mensajes publicos y privados que envian los clientes.
 * <p>
 * Un mensaje del protocolo es un {@code String[]} cuya primera posicion es el tipo:
 * {@code PUBLIC, texto} o {@code PRIVATE, destinatario, texto}.
 */
final class ChatMessageHandler {
    /** Longitud maxima de un mensaje, en caracteres. */
    private static final int MAX_MESSAGE_LENGTH = 2_000;

    /** Registro de clientes, para buscar destinatarios y difundir mensajes. */
    private final ClientRegistry clients;

    /** Log donde se apuntan los mensajes enviados. */
    private final ChatLogger logger;

    /**
     * Ultimo mensaje enviado, para la consola de estadisticas.
     * Es volatile para que el hilo de estadisticas vea siempre el valor nuevo.
     */
    private volatile String lastMessage = "Ninguno";

    /** Mensajes enviados. Varios hilos escriben en ella, por eso se usa synchronized. */
    private final List<String> sentMessages = new ArrayList<>();

    /**
     * Crea el manejador de mensajes.
     *
     * @param clients registro de clientes conectados
     * @param logger log donde se apuntan los mensajes
     */
    ChatMessageHandler(ClientRegistry clients, ChatLogger logger) {
        this.clients = clients;
        this.logger = logger;
    }

    /**
     * Mira el tipo de un mensaje recibido y lo trata como publico o privado.
     * Si el formato no es valido, responde al emisor con un {@code ERROR}.
     *
     * @param sender cliente que ha enviado el mensaje
     * @param frame mensaje recibido
     * @throws IOException si no se puede responder al emisor
     */
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

    /**
     * Devuelve el ultimo mensaje enviado.
     *
     * @return resumen del ultimo mensaje, o "Ninguno" si todavia no hay
     */
    String lastMessage() {
        return lastMessage;
    }

    /**
     * Devuelve cuantos mensajes se han enviado desde que arranco el servidor.
     *
     * @return numero de mensajes enviados
     */
    int messageCount() {
        synchronized (sentMessages) {
            return sentMessages.size();
        }
    }

    /**
     * Guarda un mensaje en la lista de enviados y lo marca como el ultimo.
     *
     * @param summary resumen del mensaje (tipo, emisor y texto)
     */
    private void record(String summary) {
        synchronized (sentMessages) {
            sentMessages.add(summary);
        }
        lastMessage = summary;
    }

    /**
     * Envia un mensaje publico a todos los clientes, el emisor incluido.
     *
     * @param sender cliente que envia el mensaje
     * @param message texto del mensaje
     * @throws IOException si no se puede responder al emisor
     */
    private void handlePublicMessage(ClientConnection sender, String message) throws IOException {
        if (!isValidMessage(message)) {
            sender.send(new String[] {"ERROR", "El mensaje no puede estar vacio ni superar 2000 caracteres."});
            return;
        }
        logger.log("PUBLICO | " + sender.username() + " | " + logSafe(message));
        record("Publico de " + sender.username() + ": " + logSafe(message));
        clients.broadcast(new String[] {"PUBLIC", sender.username(), message}, null);
    }

    /**
     * Envia un mensaje privado solo al destinatario, y una copia al emisor.
     * Responde con un {@code ERROR} si el destinatario no esta conectado o es el propio emisor.
     *
     * @param sender cliente que envia el mensaje
     * @param recipientName nombre del destinatario
     * @param message texto del mensaje
     * @throws IOException si no se puede responder al emisor
     */
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
        record("Privado de " + sender.username() + " para " + recipient.username() + ": " + logSafe(message));
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

    /**
     * Comprueba que un mensaje no sea nulo, no este vacio y no pase de 2000 caracteres.
     *
     * @param message texto del mensaje
     * @return {@code true} si el mensaje es valido
     */
    private static boolean isValidMessage(String message) {
        return message != null && !message.trim().isEmpty() && message.length() <= MAX_MESSAGE_LENGTH;
    }

    /**
     * Cambia los saltos de linea por {@code \r} y {@code \n} escritos, para que cada
     * mensaje ocupe una sola linea en el log.
     *
     * @param value texto original
     * @return texto sin saltos de linea
     */
    private static String logSafe(String value) {
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }
}
