package com.shiftrepair;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import com.shiftrepair.theme.ThemeManager;

import java.io.IOException;

public class App extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(
            App.class.getResource("/com/shiftrepair/fxml/app-shell.fxml"));
        Scene scene = new Scene(loader.load());
        scene.getStylesheets().add(
            App.class.getResource("/com/shiftrepair/css/styles.css").toExternalForm());
        ThemeManager.apply(scene);

        stage.setTitle("ShiftRepair");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
