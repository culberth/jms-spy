package com.example.jfx.spring.jms;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.val;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import static com.example.jfx.spring.jms.JavaFxApplication.*;

import java.io.IOException;

@Component
@RequiredArgsConstructor
final class PrimaryStageInitializer implements ApplicationListener<StageReadyEvent>
{

    private final AppProperties appProperties;
    private final ApplicationContext applicationContext;

    @Override
    @SneakyThrows
    public void onApplicationEvent(StageReadyEvent event)
    {
        val parent = loadFXML(appProperties.indexView());
        val scene = new Scene(parent, appProperties.width(), appProperties.height());

        val stage = event.getStage();
        stage.setTitle(appProperties.title());
        stage.setScene(scene);
        stage.show();
    }

    private Parent loadFXML(String fxml) throws IOException
    {
        var resource = PrimaryStageInitializer.class.getResource(fxml + ".fxml");

        if (resource == null)
        {
            throw new IOException("Failed to find FXML resource " + fxml);
        }

        val fxmlLoader = new FXMLLoader(resource);
        fxmlLoader.setControllerFactory(applicationContext::getBean);
        return fxmlLoader.load();
    }
}
