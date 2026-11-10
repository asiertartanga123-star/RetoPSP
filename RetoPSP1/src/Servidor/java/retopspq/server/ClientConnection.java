package retopspq.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Objects;

/**
 * Atiende a un cliente en su propio hilo: login, mensajes y desconexion.
 * <p>
 * El primer mensaje del cliente tiene que ser {@code LOGIN, nombre}. Si el nombre es
 * valido y hay sitio, el servidor responde {@code WELCOME, nombre}; si no, {@code REJECT, motivo}.
 */
final class ClientConnection implements Runnable {
    /** Tiempo maximo que se espera al LOGIN de un cliente nuevo, en milisegundos. */
    private static final int LOGIN_TIMEOUT_MILLIS = 10_000;

    /** Longitud maxima del nombre de usuario. */
    private static final int MAX_USERNAME_LENGTH = 24;

    /**
     * Solo se aceptan textos y arrays de textos de como mucho 4 posiciones (los mensajes
     * del protocolo). Cualquier otra clase se rechaza antes de crearla, porque leer
     * objetos de la red sin filtrar es peligroso.
     */
    private static final ObjectInputFilter ONLY_TEXT =
            ObjectInputFilter.Config.createFilter("java.lang.String;maxdepth=2;maxarray=4;!*");

    /** Socket conectado con el cliente. */
    private final Socket socket;

    /** Registro de clientes del servidor. */
    private final ClientRegistry clients;

    /** Encargado de tratar los mensajes que envia este cliente. */
    private final ChatMessageHandler messageHandler;

    /**
     * Stream de salida hacia el cliente. Los dos streams se crean en run() y no en el
     * constructor, porque el constructor se ejecuta en el hilo del accept() y si el
     * cliente no envia nada lo dejaria bloqueado.
     */
    private ObjectOutputStream output;

    /** Stream de entrada desde el cliente, con el filtro {@link #ONLY_TEXT}. */
    private ObjectInputStream input;

    /** Nombre del usuario; vale null hasta que el login es correcto. */
    private volatile String username;

    /**
     * Prepara la conexion con un cliente. No lee nada del socket: eso se hace en {@link #run()}.
     *
     * @param socket socket conectado con el cliente
     * @param clients registro de clientes del servidor
     * @param messageHandler encargado de tratar los mensajes
     */
    ClientConnection(Socket socket, ClientRegistry clients, ChatMessageHandler messageHandler) {
        this.socket = Objects.requireNonNull(socket, "El socket no puede ser nulo.");
        this.clients = Objects.requireNonNull(clients, "El registro de clientes no puede ser nulo.");
        this.messageHandler = Objects.requireNonNull(messageHandler, "El manejador de mensajes no puede ser nulo.");
    }

    /**
     * Devuelve el nombre del usuario.
     *
     * @return nombre del usuario, o {@code null} si todavia no ha hecho login
     */
    String username() {
        return username;
    }

    /**
     * Envia un mensaje a este cliente. Pueden llamarlo varios hilos a la vez
     * (por ejemplo, dos difusiones), por eso escribe dentro de un bloque synchronized.
     *
     * @param frame mensaje del protocolo
     * @throws IOException si no se puede escribir en el socket
     */
    void send(String[] frame) throws IOException {
        synchronized (output) {
            output.writeObject(frame);
            output.flush();
        }
    }

    /**
     * Cierra el socket del cliente si sigue abierto.
     *
     * @throws IOException si falla el cierre
     */
    void closeSocket() throws IOException {
        if (!socket.isClosed()) {
            socket.close();
        }
    }

    /**
     * Hace el login del cliente y despues lee sus mensajes hasta que se desconecta.
     * Al terminar, por el motivo que sea, lo da de baja y cierra el socket.
     */
    @Override
    public void run() {
        try {
            socket.setSoTimeout(LOGIN_TIMEOUT_MILLIS);
            output = new ObjectOutputStream(socket.getOutputStream());
            output.flush();
            input = new ObjectInputStream(socket.getInputStream());
            input.setObjectInputFilter(ONLY_TEXT);

            Object handshake = input.readObject();
            if (!(handshake instanceof String[])) {
                throw new IOException("El servidor recibio un mensaje de login invalido.");
            }

            String[] credentials = (String[]) handshake;
            if (credentials.length != 2 || !"LOGIN".equals(credentials[0])) {
                throw new IOException("El servidor esperaba un LOGIN valido.");
            }

            String nameError = validateUsername(credentials[1]);
            if (nameError != null) {
                send(new String[] {"REJECT", nameError});
                return;
            }
            username = credentials[1];
            String registrationError = clients.register(this);
            if (registrationError != null) {
                send(new String[] {"REJECT", registrationError});
                return;
            }

            // Ya ha entrado (register le ha enviado el WELCOME): se quita el tiempo maximo
            // y se avisa al resto de usuarios.
            socket.setSoTimeout(0);
            clients.broadcast(new String[] {"INFO", username + " se ha unido al chat."}, this);
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

    /**
     * Comprueba el nombre igual que el cliente: de 1 a 24 caracteres, sin espacios al
     * principio o al final y sin caracteres de control. Se comprueba tambien aqui porque
     * cualquier programa puede conectarse al puerto del servidor.
     *
     * @param name nombre recibido en el LOGIN
     * @return el motivo del rechazo, o {@code null} si el nombre es valido
     */
    private static String validateUsername(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_USERNAME_LENGTH
                || !name.equals(name.trim())) {
            return "El nombre debe tener entre 1 y 24 caracteres.";
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                return "El nombre no puede contener caracteres de control.";
            }
        }
        return null;
    }
}
