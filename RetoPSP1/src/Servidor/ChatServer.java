package Servidor;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Paths;
import java.util.List;

public class ChatServer {
    private final int port;
    private final int maxClients;
    private final int statsInterval;
    private final long startTime = System.currentTimeMillis();
    private final ChatLogger logger = new ChatLogger(Paths.get("servidor.log"));
    private final ClientRegistry clients;
    private final ChatMessageHandler messageHandler;
    private ServerSocket serverSocket;

    private ChatServer(int port, int maxClients, int statsInterval) {
        this.port = port;
        this.maxClients = maxClients;
        this.statsInterval = statsInterval;
        clients = new ClientRegistry(maxClients, logger);
        messageHandler = new ChatMessageHandler(clients, logger);
    }

    public static void main(String[] args) throws IOException {
        int port = getNumber(args, 0, 5000);
        int maxClients = getNumber(args, 1, 10);
        int statsInterval = getNumber(args, 2, 10);

        if (port < 1 || port > 65535 || maxClients < 1 || statsInterval < 1) {
            throw new IllegalArgumentException("Revisa el puerto, el limite de clientes y el intervalo.");
        }

        ChatServer server = new ChatServer(port, maxClients, statsInterval);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
    }

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

    private void start() throws IOException {
        serverSocket = new ServerSocket(port);
        System.out.println("Servidor iniciado en el puerto " + port
                + ". Maximo de clientes: " + maxClients + ".");

        Thread statsThread = new Thread(this::showStatistics);
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

            System.out.printf("Usuarios conectados: %d | Tiempo activo: %02d:%02d:%02d | Ultimo mensaje: %s%n",
                    clients.size(), hours, minutes, seconds, messageHandler.lastMessage());
        }
    }
}