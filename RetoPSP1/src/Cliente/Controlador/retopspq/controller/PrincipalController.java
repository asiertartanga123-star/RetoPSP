package retopspq.controller;

import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

/**
 * Controlador de la ventana del chat (Principal.fxml) y de la comunicacion con el servidor.
 * <p>
 * La conexion y la recepcion de mensajes van en hilos aparte para que la ventana no se
 * bloquee nunca. Los cambios en la ventana se hacen siempre con {@code Platform.runLater},
 * porque JavaFX solo deja tocar los controles desde su propio hilo.
 */
public class PrincipalController {
    /** Tiempo maximo para conectar con el servidor, en milisegundos. */
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    /** Tiempo maximo para recibir la respuesta al LOGIN, en milisegundos. */
    private static final int LOGIN_TIMEOUT_MILLIS = 10_000;
    /** Longitud maxima de un mensaje, en caracteres. */
    private static final int MAX_MESSAGE_LENGTH = 2_000;
    /** Longitud maxima del nombre de usuario. */
    private static final int MAX_USERNAME_LENGTH = 24;

    /**
     * Solo se aceptan textos y arrays de textos de como mucho 4 posiciones (los mensajes
     * del protocolo). Cualquier otra clase que llegue del servidor se rechaza antes de crearla.
     */
    private static final ObjectInputFilter ONLY_TEXT =
            ObjectInputFilter.Config.createFilter("java.lang.String;maxdepth=2;maxarray=4;!*");

    // Controles enlazados con Principal.fxml

    /** Campo de la IP o nombre del servidor. */
    @FXML
    private TextField ipField;
    /** Campo del puerto del servidor. */
    @FXML
    private TextField portField;
    /** Campo del nombre de usuario. */
    @FXML
    private TextField usernameField;
    /** Campo "Para": destinatario de los mensajes privados. */
    @FXML
    private TextField recipientField;
    /** Campo donde se escribe el mensaje. */
    @FXML
    private TextField messageField;
    /** Casilla "Privado". */
    @FXML
    private CheckBox privateCheckBox;
    /** Boton "Conectar". */
    @FXML
    private Button connectButton;
    /** Boton "Desconectar". */
    @FXML
    private Button disconnectButton;
    /** Boton "Enviar". */
    @FXML
    private Button sendButton;
    /** Etiqueta del estado de la conexion; muestra "Conectado como ..." con el nombre. */
    @FXML
    private Label statusLabel;
    /** Zona donde se muestran los mensajes del chat. */
    @FXML
    private TextArea chatArea;

    // Datos de la conexion actual. Son volatile porque los usan el hilo de la ventana
    // y los hilos de conexion y recepcion.

    /** Socket conectado con el servidor, o null si no hay conexion. */
    private volatile Socket clientSocket;
    /** Stream para enviar mensajes al servidor, o null si no hay conexion. */
    private volatile ObjectOutputStream clientWriter;
    /** Nombre con el que se ha entrado al chat. */
    private volatile String currentUsername;
    /** Indica que hay una conexion en curso, para no lanzar dos a la vez. */
    private volatile boolean connecting;

    /** Constructor vacio; lo usa el FXMLLoader al cargar Principal.fxml. */
    public PrincipalController() {
    }

    /** Al abrir la ventana, deja los botones en el estado correcto. */
    @FXML
    private void initialize() {
        updateControls();
    }

    /**
     * Boton "Conectar": valida la IP, el puerto y el nombre, y conecta en un hilo aparte
     * para no bloquear la ventana mientras tanto.
     */
    @FXML
    private void onConnect() {
        if (connecting || clientSocket != null) {
            return;
        }

        String host = ipField.getText().trim();
        String username = usernameField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException exception) {
            statusLabel.setText("El puerto debe ser un numero entre 1 y 65535.");
            return;
        }
        if (host.isEmpty() || port < 1 || port > 65_535) {
            statusLabel.setText("Indica una IP o nombre de servidor y un puerto valido.");
            return;
        }
        if (username.isEmpty() || username.length() > MAX_USERNAME_LENGTH
                || containsControlCharacter(username)) {
            statusLabel.setText("El nombre debe tener entre 1 y 24 caracteres y no contener controles.");
            return;
        }

        connecting = true;
        statusLabel.setText("Conectando...");
        updateControls();
        // La conexion puede tardar, por eso no se hace en el hilo de la interfaz.
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                openConnection(host, port, username);
            }
        }, "chat-connect");
        thread.setDaemon(true);
        thread.start();
    }

    /** Boton "Desconectar": cierra la conexion con el servidor. */
    @FXML
    private void onDisconnect() {
        closeConnection();
    }

    /**
     * Cierra la conexion con el servidor y deja la ventana lista para volver a conectar.
     * Tambien se llama al cerrar la ventana.
     */
    public void closeConnection() {
        Socket socket = clientSocket;
        clientSocket = null;
        clientWriter = null;
        currentUsername = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException exception) {
                appendMessage("Error al cerrar la conexion: " + exception.getMessage());
            }
        }
        connecting = false;
        updateControls();
        statusLabel.setText("No conectado");
    }

    /**
     * Boton "Enviar" (o tecla Intro): prepara un mensaje publico o, si esta marcada la
     * casilla "Privado", uno privado para el usuario del campo "Para", y lo envia al servidor.
     */
    @FXML
    private void onSend() {
        String message = messageField.getText().trim();
        if (message.isEmpty()) {
            return;
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            statusLabel.setText("El mensaje no puede superar 2000 caracteres.");
            return;
        }

        String[] frame;
        if (privateCheckBox.isSelected()) {
            String recipient = recipientField.getText().trim();
            if (recipient.isEmpty()) {
                statusLabel.setText("Indica el destinatario del mensaje privado.");
                return;
            }
            frame = new String[] {"PRIVATE", recipient, message};
        } else {
            frame = new String[] {"PUBLIC", message};
        }

        ObjectOutputStream writer = clientWriter;
        if (writer == null) {
            statusLabel.setText("No hay una conexion activa.");
            return;
        }
        synchronized (writer) {
            try {
                writer.writeObject(frame);
                writer.flush();
            } catch (IOException exception) {
                closeConnection();
                statusLabel.setText("Se perdio la conexion con el servidor.");
                return;
            }
        }
        messageField.clear();
    }

    /** Casilla "Privado": activa o desactiva el campo del destinatario. */
    @FXML
    private void onPrivateToggled() {
        updateControls();
    }

    /**
     * Abre el socket, envia el LOGIN y espera la respuesta del servidor. Si entra, lanza
     * el hilo que recibe los mensajes. Se ejecuta en el hilo "chat-connect", no en el de la ventana.
     *
     * @param host IP o nombre del servidor
     * @param port puerto del servidor
     * @param username nombre de usuario
     */
    private void openConnection(String host, int port, String username) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(LOGIN_TIMEOUT_MILLIS);
            ObjectOutputStream writer = new ObjectOutputStream(socket.getOutputStream());
            writer.flush();
            ObjectInputStream reader = new ObjectInputStream(socket.getInputStream());
            reader.setObjectInputFilter(ONLY_TEXT);

            writer.writeObject(new String[] {"LOGIN", username});
            writer.flush();

            // El servidor debe confirmar el inicio de sesion antes de continuar.
            Object responseObj = reader.readObject();
            if (!(responseObj instanceof String[])) {
                throw new IOException("El servidor envio una respuesta de conexion no valida.");
            }
            String[] fields = (String[]) responseObj;
            if (fields.length == 2 && "REJECT".equals(fields[0])) {
                throw new IOException(fields[1]);
            }
            if (fields.length != 2 || !"WELCOME".equals(fields[0]) || !username.equals(fields[1])) {
                throw new IOException("El servidor envio una respuesta de conexion no valida.");
            }

            socket.setSoTimeout(0);
            clientSocket = socket;
            clientWriter = writer;
            currentUsername = username;
            connecting = false;
            // JavaFX solo permite actualizar la interfaz desde su propio hilo.
            Platform.runLater(new Runnable() {
                @Override
                public void run() {
                    if (clientSocket == socket) {
                        statusLabel.setText("Conectado como " + username);
                        appendMessage("Conectado al servidor " + host + ":" + port + ".");
                        updateControls();
                    }
                }
            });
            // Un hilo separado escucha los mensajes que llegan del servidor.
            Thread receiver = new Thread(new Runnable() {
                @Override
                public void run() {
                    receiveMessages(socket, reader);
                }
            }, "chat-receiver-" + username);
            receiver.setDaemon(true);
            receiver.start();
        } catch (IOException | ClassNotFoundException | IllegalArgumentException exception) {
            closeSocket(socket);
            connecting = false;
            Platform.runLater(new Runnable() {
                @Override
                public void run() {
                    if (clientSocket == null) {
                        statusLabel.setText("Error de conexion: " + exception.getMessage());
                        updateControls();
                    }
                }
            });
        }
    }

    /**
     * Lee los mensajes del servidor hasta que se cierra la conexion. Se ejecuta en su
     * propio hilo, separado del envio, para que la ventana nunca se bloquee.
     *
     * @param socket socket conectado con el servidor
     * @param reader stream de entrada del socket
     */
    private void receiveMessages(Socket socket, ObjectInputStream reader) {
        String disconnectReason = "El servidor ha cerrado la conexion.";
        try {
            while (true) {
                Object message = reader.readObject();
                if (message == null) {
                    break;
                }
                if (message instanceof String[]) {
                    displayFrame((String[]) message);
                }
            }
        } catch (IOException | ClassNotFoundException exception) {
            if (!socket.isClosed()) {
                disconnectReason = "Conexion perdida: " + exception.getMessage();
            }
        } finally {
            if (clientSocket == socket) {
                clientSocket = null;
                clientWriter = null;
                currentUsername = null;
                String reason = disconnectReason;
                Platform.runLater(new Runnable() {
                    @Override
                    public void run() {
                        if (clientSocket == null) {
                            statusLabel.setText(reason);
                            updateControls();
                        }
                    }
                });
            }
            closeSocket(socket);
        }
    }

    /**
     * Muestra un mensaje recibido en el chat, indicando si es publico o privado y quien lo envia.
     *
     * @param fields mensaje del protocolo recibido del servidor
     */
    private void displayFrame(String[] fields) {
        try {
            switch (fields[0]) {
                case "INFO":
                    requireFieldCount(fields, 2);
                    appendMessage(fields[1]);
                    break;
                case "PUBLIC":
                    requireFieldCount(fields, 3);
                    appendMessage("[Publico] " + fields[1] + ": " + fields[2]);
                    break;
                case "PRIVATE":
                    requireFieldCount(fields, 4);
                    String sender = fields[1];
                    String recipient = fields[2];
                    String direction = sender.equals(currentUsername)
                            ? "para " + recipient : "de " + sender;
                    appendMessage("[Privado " + direction + "] " + fields[3]);
                    break;
                case "ERROR":
                    requireFieldCount(fields, 2);
                    appendMessage("Error: " + fields[1]);
                    break;
                case "REJECT":
                    requireFieldCount(fields, 2);
                    throw new IOException(fields[1]);
                case "WELCOME":
                    requireFieldCount(fields, 2);
                    break;
                default:
                    appendMessage("El servidor envio un mensaje desconocido.");
                    break;
            }
        } catch (IOException | IllegalArgumentException exception) {
            appendMessage("Se recibio un mensaje no valido del servidor.");
        }
    }

    /** Activa o desactiva los controles segun si hay conexion o no. */
    private void updateControls() {
        boolean connected = clientSocket != null;
        connectButton.setDisable(connected || connecting);
        disconnectButton.setDisable(!connected);
        ipField.setDisable(connected || connecting);
        portField.setDisable(connected || connecting);
        usernameField.setDisable(connected || connecting);
        privateCheckBox.setDisable(!connected);
        recipientField.setDisable(!connected || !privateCheckBox.isSelected());
        messageField.setDisable(!connected);
        sendButton.setDisable(!connected);
    }

    /**
     * Anade una linea al chat. Se puede llamar desde cualquier hilo, porque el cambio
     * se hace con {@code Platform.runLater} en el hilo de JavaFX.
     *
     * @param message texto que se anade
     */
    private void appendMessage(String message) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                chatArea.appendText(message + System.lineSeparator());
            }
        });
    }

    /**
     * Comprueba si un texto tiene caracteres de control (saltos de linea, tabuladores...).
     *
     * @param value texto que se comprueba
     * @return {@code true} si tiene algun caracter de control
     */
    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Comprueba que un mensaje tenga el numero de campos esperado.
     *
     * @param fields mensaje recibido
     * @param expected numero de campos que deberia tener
     * @throws IllegalArgumentException si no tiene ese numero de campos
     */
    private static void requireFieldCount(String[] fields, int expected) {
        if (fields.length != expected) {
            throw new IllegalArgumentException("Numero de campos incorrecto.");
        }
    }

    /**
     * Cierra un socket y escribe el error en consola si no se puede cerrar.
     *
     * @param socket socket que se cierra
     */
    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException exception) {
            System.err.println("No se pudo cerrar el socket: " + exception.getMessage());
        }
    }
}