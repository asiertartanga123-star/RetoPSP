package retopspq.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registro central de los clientes conectados al servidor.
 * <p>
 * Guarda los clientes por nombre de usuario y hace las operaciones que comparten
 * todos los hilos: dar de alta, dar de baja, buscar y difundir mensajes. Todos los
 * accesos al mapa estan protegidos con {@code synchronized} sobre {@code clientsLock}.
 */
final class ClientRegistry {
    /** Numero maximo de clientes conectados a la vez. */
    private final int maxClients;

    /** Log donde se apuntan las entradas y salidas de usuarios. */
    private final ChatLogger logger;

    /** Objeto de bloqueo que protege el mapa de clientes. */
    private final Object clientsLock = new Object();

    /** Clientes conectados, por nombre de usuario. */
    private final Map<String, ClientConnection> clients = new LinkedHashMap<>();

    /** Indica que el servidor se esta cerrando y ya no acepta conexiones. */
    private volatile boolean serverClosed;

    /**
     * Crea el registro de clientes.
     *
     * @param maxClients numero maximo de clientes conectados a la vez
     * @param logger log donde se apuntan las entradas y salidas
     */
    ClientRegistry(int maxClients, ChatLogger logger) {
        this.maxClients = maxClients;
        this.logger = logger;
    }

    /**
     * Da de alta a un cliente si hay sitio y su nombre no esta en uso.
     * El nombre se compara sin distinguir mayusculas de minusculas.
     *
     * @param client cliente que quiere entrar, con su nombre ya asignado
     * @return {@code null} si ha entrado, o el motivo del rechazo
     */
    String register(ClientConnection client) {
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

    /**
     * Da de baja a un cliente y avisa al resto de que ha salido.
     * Solo lo quita si es el mismo objeto que se registro, para que un cliente
     * rechazado no borre al que ya tenia ese nombre.
     *
     * @param client cliente que se ha desconectado
     */
    void unregister(ClientConnection client) {
        boolean removed;
        synchronized (clientsLock) {
            removed = clients.remove(client.username(), client);
            if (removed) {
                logger.log("SALE | " + client.username());
            }
        }
        if (removed) {
            broadcast(new String[] {"INFO", client.username() + " ha salido del chat."}, null);
        }
    }

    /**
     * Busca un cliente conectado por su nombre, sin distinguir mayusculas de minusculas.
     *
     * @param username nombre del usuario que se busca
     * @return el cliente, o {@code null} si no esta conectado
     */
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

    /**
     * Envia un mensaje a todos los clientes conectados menos a uno.
     * Se recorre una copia de la lista para no enviar con el bloqueo cogido.
     * Si un envio falla, ese cliente se da de baja.
     *
     * @param frame mensaje del protocolo que se envia
     * @param excluded cliente que no lo recibe, o {@code null} para enviarlo a todos
     */
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

    /**
     * Devuelve cuantos clientes hay conectados.
     *
     * @return numero de clientes conectados
     */
    int size() {
        synchronized (clientsLock) {
            return clients.size();
        }
    }

    /**
     * Devuelve una copia de la lista de clientes conectados, para poder recorrerla
     * sin bloquear al resto de hilos.
     *
     * @return copia de la lista de clientes
     */
    List<ClientConnection> snapshot() {
        synchronized (clientsLock) {
            return new ArrayList<>(clients.values());
        }
    }

    /** Marca el servidor como cerrado para que deje de aceptar conexiones. */
    void closeServer() {
        serverClosed = true;
    }

    /**
     * Indica si el servidor se esta cerrando.
     *
     * @return {@code true} si el servidor esta cerrado
     */
    boolean isServerClosed() {
        return serverClosed;
    }
}
