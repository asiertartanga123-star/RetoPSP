package retopspq.vista;

import java.io.IOException;

import javafx.application.Application;
import javafx.event.EventHandler;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import retopspq.controller.PrincipalController;

/**
 * Aplicacion JavaFX del cliente del chat: carga la ventana desde Principal.fxml.
 * Se arranca desde {@link Launcher}.
 */
public class App extends Application {

    /** Ruta de la ventana dentro de los recursos. */
    private static final String FXML_PATH = "/com/Vista/Principal.fxml";

    /** Constructor vacio; JavaFX crea la aplicacion al llamar a launch(). */
    public App() {
    }

    /**
     * Carga la ventana del chat y la muestra.
     *
     * @param stage ventana principal que crea JavaFX
     * @throws IOException si no se puede cargar Principal.fxml
     */
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(App.class.getResource(FXML_PATH));
        Parent root = loader.load();
        final PrincipalController controller = loader.getController();

        Scene scene = new Scene(root, 1100, 735);
        stage.setTitle("Chat multiusuario");
        stage.setMinWidth(850);
        stage.setMinHeight(500);
        stage.setScene(scene);
        // Si se cierra la ventana con la X, tambien se cierra la conexion con el servidor.
        stage.setOnCloseRequest(new EventHandler<WindowEvent>() {
            @Override
            public void handle(WindowEvent event) {
                controller.closeConnection();
            }
        });
        stage.show();
    }

    /**
     * Arranca JavaFX.
     *
     * @param args argumentos del programa (no se usan)
     */
    public static void main(String[] args) {
        launch();
    }
}