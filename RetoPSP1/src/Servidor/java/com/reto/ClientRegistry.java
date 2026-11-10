package retopspq.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mantiene la lista de clientes conectados y centraliza las operaciones compartidas.
 * Esta clase actúa como registro central del servidor.
 */
final class ClientRegistry {
    // Límite máximo de clientes simultáneos permitidos.
    private final int maxClients;

    // Logger para registrar eventos de entrada y salida.
    private final ChatLogger logger;

    // Objeto de bloqueo para proteger la estructura compartida de clientes.
    private final Object clientsLock = new Object();

    // Mapa que guarda los clientes conectados por nombre de usuario.
    private final Map<String, ClientConnection> clients = new LinkedHashMap<>();

    // Señal para indicar que el servidor ha sido cerrado.
    private volatile boolean serverClosed;

    ClientRegistry(int maxClients, ChatLogger logger) {
        this.maxClients = maxClients;
        this.logger = logger;
    }

    // Registra a un cliente si cumple las reglas del servidor.
    String register(ClientConnection client) throws IOException {
        synchronized (clientsLock) {
            if (clients.size() >= maxClients) {
                return "El servidor ha alcanzado el maximo de clientes.";
            }
            for (ClientConnection connected : clients.values()) {
                if (connected.username().equalsIgnoreCase(client.username())) {
                    return "Ese nombre de usuario ya esta conectado.";
                }
            }
            clients.put(client.username(), client);
            logger.log("ENTRA | " + client.username());
            return null;
        }
    }

    // Elimina a un cliente de la lista y registra su desconexión.
    void unregister(ClientConnection client) {
        boolean removed;
        synchronized (clientsLock) {
            removed = clients.remove(client.username(), client);
            if (removed) {
                try {
                    logger.log("SALE | " + client.username());
                } catch (IOException exception) {
                    System.err.println("No se pudo registrar la desconexion de " + client.username() + ": "
                            + exception.getMessage());
                }
            }
        }
        if (removed) {
            broadcast(new String[] {"INFO", client.username() + " ha salido del chat."}, null);
        }
    }

    // Busca un cliente por nombre ignorando mayúsculas y minúsculas.
    ClientConnection find(String username) {
        synchronized (clientsLock) {
            for (ClientConnection client : clients.values()) {
                if (client.username().equalsIgnoreCase(username)) {
                    return client;
                }
            }
            return null;
        }
    }

    // Envía un frame a todos los clientes conectados salvo al excluido.
    void broadcast(String[] frame, ClientConnection excluded) {
        for (ClientConnection client : snapshot()) {
            if (client != excluded) {
                try {
                    client.send(frame);
                } catch (IOException exception) {
                    System.err.println("Error enviando a " + client.username() + ": " + exception.getMessage());
                    unregister(client);
                }
            }
        }
    }

    // Devuelve la cantidad de clientes actualmente conectados.
    int size() {
        synchronized (clientsLock) {
            return clients.size();
        }
    }

    // Genera una copia de la colección para evitar problemas de concurrencia.
    List<ClientConnection> snapshot() {
        synchronized (clientsLock) {
            return new ArrayList<>(clients.values());
        }
    }

    // Marca el servidor como cerrado para evitar aceptar nuevas conexiones.
    void closeServer() {
        serverClosed = true;
    }

    // Indica si el servidor está cerrado.
    boolean isServerClosed() {
        return serverClosed;
    }
}
