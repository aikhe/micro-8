package edu.ucc.micro8;

import edu.ucc.micro8.db.DatabaseManager;
import edu.ucc.micro8.ui.MainWorkbenchController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;
import java.util.Objects;

public final class Micro8App extends Application {

    private static final String WINDOW_TITLE = "MICRO-8 | 8-Bit Micro-Architecture Emulator & Debugging Workbench";
    private static final double DEFAULT_WIDTH = 1440.0;
    private static final double DEFAULT_HEIGHT = 900.0;
    private static final double MIN_WIDTH = 1280.0;
    private static final double MIN_HEIGHT = 800.0;

    @Override
    public void start(Stage primaryStage) throws Exception {
        DatabaseManager dbManager = DatabaseManager.createDefault();
        dbManager.initialize();

        URL fxmlUrl = Objects.requireNonNull(
                getClass().getResource("/edu/ucc/micro8/ui/MainWorkbench.fxml"),
                "Missing MainWorkbench.fxml resource"
        );
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        Parent root = loader.load();

        MainWorkbenchController controller = loader.getController();
        if (controller != null) {
            controller.setDatabaseManager(dbManager);
        }

        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        URL cssUrl = Objects.requireNonNull(
                getClass().getResource("/edu/ucc/micro8/ui/workbench.css"),
                "Missing workbench.css resource"
        );
        scene.getStylesheets().add(cssUrl.toExternalForm());

        primaryStage.setTitle(WINDOW_TITLE);
        primaryStage.setMinWidth(MIN_WIDTH);
        primaryStage.setMinHeight(MIN_HEIGHT);
        primaryStage.setScene(scene);
        primaryStage.show();
    }
}
