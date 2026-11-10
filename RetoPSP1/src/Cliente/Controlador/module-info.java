module com.reto {
    requires javafx.controls;
    requires javafx.fxml;

    opens com.reto to javafx.fxml;
    exports com.reto;
}
