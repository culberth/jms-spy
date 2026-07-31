package com.example.jfx.spring.jms;

import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.TextMessage;
import jakarta.jms.Topic;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Controller
@RequiredArgsConstructor
public class PrimaryController
{

    private final JmsConnectionService jmsConnectionService;
    private final UserPreferencesStore preferencesStore;
    private final AppProperties appProperties;
    private final JolokiaClient jolokiaClient;

    @FXML
    private VBox rootPane;
    @FXML
    private CheckBox darkModeCheckBox;
    @FXML
    private TextField brokerUrlField;
    @FXML
    private TextField usernameField;
    @FXML
    private PasswordField passwordField;
    @FXML
    private Button connectButton;
    @FXML
    private Label connectionStatusLabel;
    @FXML
    private ComboBox<String> destinationCombo;
    @FXML
    private RadioButton queueRadio;
    @FXML
    private RadioButton topicRadio;
    @FXML
    private Button listenButton;
    @FXML
    private RadioButton appendRadio;
    @FXML
    private RadioButton replaceRadio;
    @FXML
    private TextArea messageArea;
    @FXML
    private ComboBox<String> publishDestinationCombo;
    @FXML
    private RadioButton publishQueueRadio;
    @FXML
    private RadioButton publishTopicRadio;
    @FXML
    private TextArea messageToPublishArea;
    @FXML
    private Button publishButton;
    @FXML
    private Label publishStatusLabel;

    private int jolokiaPort;
    private String jolokiaPath;
    private String addressSearchMbean;
    private boolean jolokiaVirtualService;

    @FXML
    private void initialize()
    {
        var preferences = preferencesStore.load();
        jolokiaPort = preferences.jolokiaPort();
        jolokiaPath = preferences.jolokiaPath();
        addressSearchMbean = preferences.addressSearchMbean();
        jolokiaVirtualService = preferences.jolokiaVirtualService();
        brokerUrlField.setText(preferences.brokerUrl());
        usernameField.setText(preferences.username());
        if (!preferencesStore.hasSavedConfig())
        {
            passwordField.setText(JmsSpyPreferences.defaultPassword());
        }
        destinationCombo.setValue(preferences.subscribeDestination());
        queueRadio.setSelected(preferences.subscribeDestinationType() == DestinationType.QUEUE);
        topicRadio.setSelected(preferences.subscribeDestinationType() == DestinationType.TOPIC);
        appendRadio.setSelected(preferences.appendMode());
        replaceRadio.setSelected(!preferences.appendMode());
        darkModeCheckBox.setSelected(preferences.darkMode());
        applyTheme(preferences.darkMode());
        publishDestinationCombo.setValue(preferences.publishDestination());
        publishQueueRadio.setSelected(preferences.publishDestinationType() == DestinationType.QUEUE);
        publishTopicRadio.setSelected(preferences.publishDestinationType() == DestinationType.TOPIC);

        appendRadio.selectedProperty().addListener((observable, wasSelected, isSelected) -> savePreferences());
        queueRadio.selectedProperty().addListener((observable, wasSelected, isSelected) -> savePreferences());
        publishQueueRadio.selectedProperty().addListener((observable, wasSelected, isSelected) -> savePreferences());
        darkModeCheckBox.selectedProperty().addListener((observable, wasDark, isDark) ->
        {
            applyTheme(isDark);
            savePreferences();
        });
    }

    private void applyTheme(boolean dark)
    {
        var stylesheet = dark ? "/dark-theme.css" : "/light-theme.css";
        rootPane.getStylesheets().setAll(getClass().getResource(stylesheet).toExternalForm());
    }

    @FXML
    private void clearOutput()
    {
        messageArea.clear();
    }

    @FXML
    private void closeApplication()
    {
        Platform.exit();
    }

    @FXML
    private void showSettingsDialog()
    {
        var dialog = new Dialog<ButtonType>();
        dialog.initOwner(rootPane.getScene().getWindow());
        dialog.setTitle("Settings");
        dialog.setHeaderText("Jolokia Settings");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var portField = new TextField(String.valueOf(jolokiaPort));
        var pathField = new TextField(jolokiaPath);
        var mbeanField = new TextField(addressSearchMbean);
        mbeanField.setPrefWidth(350.0);

        var virtualServiceCheckBox = new CheckBox("Virtual service (DNS host name only, no port)");
        virtualServiceCheckBox.setSelected(jolokiaVirtualService);
        portField.setDisable(jolokiaVirtualService);
        virtualServiceCheckBox.selectedProperty()
                .addListener((observable, wasSelected, isSelected) -> portField.setDisable(isSelected));

        var grid = new GridPane();
        grid.setHgap(10.0);
        grid.setVgap(10.0);
        grid.addRow(0, new Label("Jolokia Port"), portField);
        grid.addRow(1, new Label("Jolokia Path"), pathField);
        grid.addRow(2, new Label("Address Search MBean"), mbeanField);
        grid.addRow(3, virtualServiceCheckBox);
        GridPane.setColumnSpan(virtualServiceCheckBox, 2);
        dialog.getDialogPane().setContent(grid);

        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK)
        {
            return;
        }

        try
        {
            jolokiaPort = Integer.parseInt(portField.getText().trim());
        }
        catch (NumberFormatException ex)
        {
            jolokiaPort = JolokiaClient.DEFAULT_JOLOKIA_PORT;
        }
        jolokiaPath = pathField.getText();
        addressSearchMbean = mbeanField.getText();
        jolokiaVirtualService = virtualServiceCheckBox.isSelected();
        savePreferences();
    }

    @FXML
    private void showAboutDialog()
    {
        var alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(rootPane.getScene().getWindow());
        alert.setTitle("About " + appProperties.title());
        alert.setHeaderText(appProperties.title() + " " + appProperties.version());
        alert.setContentText("A desktop client for inspecting and publishing messages on an Apache ActiveMQ "
                + "Artemis JMS queue or topic.\n\nVendor: Slobberknocker Productions");
        alert.showAndWait();
    }

    @FXML
    private void toggleConnection()
    {
        if (jmsConnectionService.isConnected())
        {
            jmsConnectionService.disconnect();
            connectButton.setText("Connect");
            connectionStatusLabel.setText("Disconnected");
            listenButton.setText("Listen");
            listenButton.setDisable(true);
            publishButton.setDisable(true);
            destinationCombo.getItems().clear();
            publishDestinationCombo.getItems().clear();
            return;
        }

        var brokerUrl = brokerUrlField.getText();
        try
        {
            jmsConnectionService.connect(brokerUrl, usernameField.getText(), passwordField.getText());
            connectButton.setText("Disconnect");
            connectionStatusLabel.setText("Connected to " + brokerUrl);
            listenButton.setDisable(false);
            publishButton.setDisable(false);
            savePreferences();
            refreshDestinationList(brokerUrl, usernameField.getText(), passwordField.getText());
        }
        catch (JMSException | RuntimeException ex)
        {
            log.error("Failed to connect to broker {}", brokerUrl, ex);
            connectionStatusLabel.setText("Connection failed: " + ex.getMessage());
        }
    }

    /**
     * Looks up the broker's known addresses via Jolokia and offers them as autocomplete
     * suggestions on both destination combo boxes. Runs off the JavaFX thread since it's a
     * network call; a failure here is non-fatal, the destination fields stay freely editable.
     */
    private void refreshDestinationList(String brokerUrl, String username, String password)
    {
        var jolokiaUrl = jolokiaClient.deriveJolokiaUrl(brokerUrl, jolokiaPort, jolokiaPath, jolokiaVirtualService);
        CompletableFuture.supplyAsync(() ->
        {
            try
            {
                return jolokiaClient.searchAddresses(jolokiaUrl, username, password, addressSearchMbean);
            }
            catch (Exception ex)
            {
                log.warn("Failed to search Jolokia addresses at {}", jolokiaUrl, ex);
                return List.<String>of();
            }
        }).thenAccept(addresses -> Platform.runLater(() ->
        {
            destinationCombo.getItems().setAll(addresses);
            publishDestinationCombo.getItems().setAll(addresses);
        }));
    }

    @FXML
    private void toggleListening()
    {
        if (jmsConnectionService.isListening())
        {
            jmsConnectionService.stopListening();
            listenButton.setText("Listen");
            return;
        }

        var destinations = parseDestinationList(destinationCombo.getEditor().getText());
        if (destinations.isEmpty())
        {
            connectionStatusLabel.setText("Enter at least one destination name before listening");
            return;
        }

        var destinationType = queueRadio.isSelected() ? DestinationType.QUEUE : DestinationType.TOPIC;
        try
        {
            jmsConnectionService.listen(destinations, destinationType, this::onMessageReceived);
            listenButton.setText("Stop");
            savePreferences();
        }
        catch (JMSException | RuntimeException ex)
        {
            log.error("Failed to listen to {} {}", destinationType, destinations, ex);
            connectionStatusLabel.setText("Listen failed: " + ex.getMessage());
        }
    }

    private List<String> parseDestinationList(String text)
    {
        return Arrays.stream(text.split(",")).map(String::trim).filter(StringUtils::hasText).toList();
    }

    @FXML
    private void publish()
    {
        var destination = publishDestinationCombo.getEditor().getText();
        if (!StringUtils.hasText(destination))
        {
            publishStatusLabel.setText("Enter a destination name before publishing");
            return;
        }

        var destinationType = publishQueueRadio.isSelected() ? DestinationType.QUEUE : DestinationType.TOPIC;
        try
        {
            jmsConnectionService.publish(destination, destinationType, messageToPublishArea.getText());
            publishStatusLabel.setText("Published to " + destination);
            savePreferences();
        }
        catch (JMSException | RuntimeException ex)
        {
            log.error("Failed to publish to {} {}", destinationType, destination, ex);
            publishStatusLabel.setText("Publish failed: " + ex.getMessage());
        }
    }

    private void onMessageReceived(Message message)
    {
        var line = "[" + extractDestinationName(message) + "] " + extractContent(message);
        Platform.runLater(() ->
        {
            if (appendRadio.isSelected())
            {
                messageArea.appendText(line + System.lineSeparator());
            }
            else
            {
                messageArea.setText(line);
            }
        });
    }

    private String extractDestinationName(Message message)
    {
        try
        {
            var destination = message.getJMSDestination();
            if (destination instanceof Queue queue)
            {
                return queue.getQueueName();
            }
            if (destination instanceof Topic topic)
            {
                return topic.getTopicName();
            }
            return "?";
        }
        catch (JMSException ex)
        {
            return "?";
        }
    }

    private String extractContent(Message message)
    {
        try
        {
            if (message instanceof TextMessage textMessage)
            {
                return textMessage.getText();
            }
            return message.toString();
        }
        catch (JMSException ex)
        {
            log.error("Failed to read message content", ex);
            return "<unreadable message: " + ex.getMessage() + ">";
        }
    }

    private void savePreferences()
    {
        preferencesStore.save(new JmsSpyPreferences(
                brokerUrlField.getText(),
                usernameField.getText(),
                destinationCombo.getEditor().getText(),
                queueRadio.isSelected() ? DestinationType.QUEUE : DestinationType.TOPIC,
                appendRadio.isSelected(),
                darkModeCheckBox.isSelected(),
                publishDestinationCombo.getEditor().getText(),
                publishQueueRadio.isSelected() ? DestinationType.QUEUE : DestinationType.TOPIC,
                jolokiaPort,
                jolokiaPath,
                addressSearchMbean,
                jolokiaVirtualService));
    }
}
