package retopspq.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Objects;

final class ClientConnection implements Runnable {
    private final Socket socket;
    private final ClientRegistry clients;
    private final ChatMessageHandler messageHandler;
    private final ObjectOutputStream output;
    private final ObjectInputStream input;
    private String username;

    ClientConnection(Socket socket, ClientRegistry clients, ChatMessageHandler messageHandler) throws IOException {
        this.socket = Objects.requireNonNull(socket, "El socket no puede ser nulo.");
        this.clients = Objects.requireNonNull(clients, "El registro de clientes no puede ser nulo.");
        this.messageHandler = Objects.requireNonNull(messageHandler, "El manejador de mensajes no puede ser nulo.");
        this.output = new ObjectOutputStream(socket.getOutputStream());
        this.output.flush();
        this.input = new ObjectInputStream(socket.getInputStream());
    }

    String username() {
        return username;
    }

    void send(String[] frame) throws IOException {
        synchronized (output) {
            output.writeObject(frame);
            output.flush();
        }
    }

    void closeSocket() throws IOException {
        if (!socket.isClosed()) {
            socket.close();
        }
    }

    @Override
    public void run() {
        try {
            Object handshake = input.readObject();
            if (!(handshake instanceof String[])) {
                throw new IOException("El servidor recibio un mensaje de login invalido.");
            }

            String[] credentials = (String[]) handshake;
            if (credentials.length != 2 || !"LOGIN".equals(credentials[0])) {
                throw new IOException("El servidor esperaba un LOGIN valido.");
            }

            username = credentials[1];
            String registrationError = clients.register(this);
            if (registrationError != null) {
                send(new String[] {"REJECT", registrationError});
                return;
            }

            send(new String[] {"WELCOME", username});
            while (!socket.isClosed()) {
                Object received = input.readObject();
                if (received == null) {
                    break;
                }
                if (received instanceof String[]) {
                    messageHandler.handleFrame(this, (String[]) received);
                } else {
                    send(new String[] {"ERROR", "Formato de mensaje no valido."});
                }
            }
        } catch (EOFException exception) {
            // La desconexion del cliente queda registrada al cerrar la sesion.
        } catch (IOException | ClassNotFoundException exception) {
            System.err.println("Error en la conexion de " + username + ": " + exception.getMessage());
        } finally {
            clients.unregister(this);
            try {
                closeSocket();
            } catch (IOException ignored) {
                // Ignoramos el cierre ya que el cliente puede haber abandonado la conexion.
            }
        }
    }
}
