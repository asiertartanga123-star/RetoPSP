package retopspq.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Paths;
import java.util.List;

/**
 * Servidor de consola del chat multiusuario.
 * <p>
 * Acepta conexiones de clientes y crea un hilo para cada uno. Ademas, un hilo aparte
 * muestra cada cierto tiempo los usuarios conectados, el tiempo activo y el ultimo mensaje.
 * <p>
 * Uso: {@code java retopspq.server.ChatServer [puerto] [maxClientes] [segundosEstadisticas]}
 */
public class ChatServer {
    /** Puerto TCP donde escucha el servidor. */
    private final int port;

    /** Numero maximo de clientes conectados a la vez. */
    private final int maxClients;

    /** Segundos entre cada linea de estadisticas. */
    private final int statsInterval;

    /** Momento en que arranco el servidor, en milisegundos. */
    private final long startTime = System.currentTimeMillis();

    /** Log global del servidor, en el fichero servidor.log. */
    private final ChatLogger logger = new ChatLogger(Paths.get("servidor.log"));

    /** Registro de los clientes conectados. */
    private final ClientRegistry clients;

    /** Encargado de tratar los mensajes publicos y privados. */
    private final ChatMessageHandler messageHandler;

    /** Socket donde se aceptan las conexiones. */
    private ServerSocket serverSocket;

    /**
     * Crea el servidor con su configuracion.
     *
     * @param port puerto TCP donde escucha
     * @param maxClients numero maximo de clientes conectados a la vez
     * @param statsInterval segundos entre cada linea de estadisticas
     */
    private ChatServer(int port, int maxClients, int statsInterval) {
        this.port = port;
        this.maxClients = maxClients;
        this.statsInterval = statsInterval;
        clients = new ClientRegistry(maxClients, logger);
        messageHandler = new ChatMessageHandler(clients, logger);
    }

    /**
     * Arranca el servidor.
     *
     * @param args puerto (5000 por defecto), maximo de clientes (10 por defecto)
     *             y segundos entre estadisticas (10 por defecto); los tres son opcionales
     * @throws IOException si no se puede abrir el puerto
     * @throws IllegalArgumentException si algun argumento no es valido
     */
    public static void main(String[] args) throws IOException {
        int port = getNumber(args, 0, 5000);
        int maxClients = getNumber(args, 1, 10);
        int statsInterval = getNumber(args, 2, 10);

        if (port < 1 || port > 65535 || maxClients < 1 || statsInterval < 1) {
            throw new IllegalArgumentException("Revisa el puerto, el limite de clientes y el intervalo.");
        }

        final ChatServer server = new ChatServer(port, maxClients, statsInterval);
        // Al parar el servidor con Ctrl+C se cierran los sockets de todos los clientes.
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                server.close();
            }
        }));
        server.start();
    }

    /**
     * Lee un argumento numerico de la linea de ordenes.
     *
     * @param args argumentos del programa
     * @param position posicion del argumento
     * @param defaultValue valor que se usa si el argumento no esta
     * @return el numero leido, o el valor por defecto
     * @throws IllegalArgumentException si el argumento no es un numero entero
     */
    private static int getNumber(String[] args, int position, int defaultValue) {
        if (args.length <= position) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(args[position]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Los argumentos deben ser numeros enteros.", exception);
        }
    }

    /**
     * Abre el puerto, lanza el hilo de estadisticas y acepta clientes hasta que se
     * cierre el servidor. Cada cliente se atiende en un hilo nuevo.
     *
     * @throws IOException si falla el socket del servidor mientras esta abierto
     */
    private void start() throws IOException {
        serverSocket = new ServerSocket(port);
        System.out.println("Servidor iniciado en el puerto " + port
                + ". Maximo de clientes: " + maxClients + ".");

        // Hilo aparte que muestra las estadisticas cada statsInterval segundos.
        Thread statsThread = new Thread(new Runnable() {
            @Override
            public void run() {
                showStatistics();
            }
        });
        statsThread.setDaemon(true);
        statsThread.start();

        try {
            while (!clients.isServerClosed()) {
                Socket socket = serverSocket.accept();
                Thread clientThread = new Thread(
                        new ClientConnection(socket, clients, messageHandler));
                clientThread.start();
            }
        } catch (IOException exception) {
            if (!clients.isServerClosed()) {
                throw exception;
            }
        } finally {
            close();
        }
    }

    /**
     * Cierra el servidor: deja de aceptar conexiones y cierra el socket de cada cliente.
     * Se puede llamar mas de una vez.
     */
    private void close() {
        clients.closeServer();
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException exception) {
            System.err.println("No se pudo cerrar el servidor: " + exception.getMessage());
        }

        List<ClientConnection> connectedClients = clients.snapshot();
        for (ClientConnection client : connectedClients) {
            try {
                client.closeSocket();
            } catch (IOException exception) {
                System.err.println("No se pudo cerrar la conexion de " + client.username());
            }
        }
    }

    /**
     * Muestra en consola, cada {@code statsInterval} segundos, los usuarios conectados,
     * el tiempo activo, el numero de mensajes y el ultimo mensaje. Se ejecuta en su propio hilo.
     */
    private void showStatistics() {
        while (!clients.isServerClosed()) {
            try {
                Thread.sleep(statsInterval * 1000L);
            } catch (InterruptedException exception) {
                return;
            }

            long totalSeconds = (System.currentTimeMillis() - startTime) / 1000;
            long hours = totalSeconds / 3600;
            long minutes = totalSeconds % 3600 / 60;
            long seconds = totalSeconds % 60;

            System.out.printf("Usuarios conectados: %d | Tiempo activo: %02d:%02d:%02d | Mensajes: %d | Ultimo mensaje: %s%n",
                    clients.size(), hours, minutes, seconds, messageHandler.messageCount(), messageHandler.lastMessage());
        }
    }
}
