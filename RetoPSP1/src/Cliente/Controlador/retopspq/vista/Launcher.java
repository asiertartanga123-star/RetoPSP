package retopspq.vista;

/**
 * Clase de arranque del cliente.
 * Java no deja arrancar directamente una clase que hereda de Application si JavaFX
 * no esta en el module path, por eso el main esta aqui y llama a App.
 */
public class Launcher {
    /** No se crean objetos de esta clase: solo tiene el main. */
    private Launcher() {
    }

    /**
     * Arranca el cliente del chat.
     *
     * @param args argumentos del programa (no se usan)
     */
    public static void main(String[] args) {
        App.main(args);
    }
}
