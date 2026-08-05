package com.example.jfx.spring.jms;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Controller
@RequiredArgsConstructor
public class PrimaryController
{

    private static final String STATUS_SUCCESS_STYLE_CLASS = "status-success";
    private static final String STATUS_WARNING_STYLE_CLASS = "status-warning";
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private final JmsConnectionService jmsConnectionService;
    private final UserPreferencesStore preferencesStore;
    private final AppProperties appProperties;
    private final JolokiaClient jolokiaClient;

    @FXML
    private VBox rootPane;
    @FXML
    private CheckBox darkModeCheckBox;
    @FXML
    private TextField brokerHostField;
    @FXML
    private TextField brokerPortField;
    @FXML
    private CheckBox virtualServiceCheckBox;
    @FXML
    private CheckBox anonymousLoginCheckBox;
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
    private Button listenButton;
    @FXML
    private RadioButton appendRadio;
    @FXML
    private RadioButton replaceRadio;
    @FXML
    private CheckBox formatJsonCheckBox;
    @FXML
    private TextArea messageArea;
    @FXML
    private TextArea messagePropertiesArea;
    @FXML
    private ComboBox<String> publishDestinationCombo;
    @FXML
    private TextArea messageToPublishArea;
    @FXML
    private TextArea messagePropertiesToPublishArea;
    @FXML
    private Button publishButton;
    @FXML
    private Label publishStatusLabel;

    private int jolokiaPort;
    private String jolokiaPath;
    private String addressSearchMbean;

    @FXML
    private void initialize()
    {
        var preferences = preferencesStore.load();
        jolokiaPort = preferences.jolokiaPort();
        jolokiaPath = preferences.jolokiaPath();
        addressSearchMbean = preferences.addressSearchMbean();
        brokerHostField.setText(preferences.brokerHost());
        brokerPortField.setText(String.valueOf(preferences.brokerPort()));
        virtualServiceCheckBox.setSelected(preferences.virtualService());
        brokerPortField.setDisable(preferences.virtualService());
        anonymousLoginCheckBox.setSelected(preferences.anonymousLogin());
        usernameField.setText(preferences.username());
        usernameField.setDisable(preferences.anonymousLogin());
        passwordField.setDisable(preferences.anonymousLogin());
        if (!preferencesStore.hasSavedConfig())
        {
            passwordField.setText(JmsSpyPreferences.defaultPassword());
        }
        destinationCombo.setValue(preferences.subscribeDestination());
        appendRadio.setSelected(preferences.appendMode());
        replaceRadio.setSelected(!preferences.appendMode());
        formatJsonCheckBox.setSelected(preferences.formatJson());
        darkModeCheckBox.setSelected(preferences.darkMode());
        applyTheme(preferences.darkMode());
        publishDestinationCombo.setValue(preferences.publishDestination());

        appendRadio.selectedProperty().addListener((observable, wasSelected, isSelected) -> savePreferences());
        formatJsonCheckBox.selectedProperty().addListener((observable, wasSelected, isSelected) -> savePreferences());
        darkModeCheckBox.selectedProperty().addListener((observable, wasDark, isDark) ->
        {
            applyTheme(isDark);
            savePreferences();
        });
        virtualServiceCheckBox.selectedProperty().addListener((observable, wasSelected, isSelected) ->
        {
            brokerPortField.setDisable(isSelected);
            savePreferences();
        });
        anonymousLoginCheckBox.selectedProperty().addListener((observable, wasSelected, isSelected) ->
        {
            usernameField.setDisable(isSelected);
            passwordField.setDisable(isSelected);
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
        messagePropertiesArea.clear();
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
        portField.setDisable(virtualServiceCheckBox.isSelected());
        var pathField = new TextField(jolokiaPath);
        var mbeanField = new TextField(addressSearchMbean);
        mbeanField.setPrefWidth(350.0);

        var grid = new GridPane();
        grid.setHgap(10.0);
        grid.setVgap(10.0);
        grid.addRow(0, new Label("Jolokia Port"), portField);
        grid.addRow(1, new Label("Jolokia Path"), pathField);
        grid.addRow(2, new Label("Address Search MBean"), mbeanField);
        dialog.getDialogPane().setContent(grid);

        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK)
        {
            return;
        }

        jolokiaPort = parseIntOrDefault(portField.getText(), JolokiaClient.DEFAULT_JOLOKIA_PORT);
        jolokiaPath = pathField.getText();
        addressSearchMbean = mbeanField.getText();
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
                + "Artemis JMS topic.\n\nVendor: Slobberknocker Productions");
        alert.showAndWait();
    }

    @FXML
    private void toggleConnection()
    {
        if (jmsConnectionService.isConnected())
        {
            jmsConnectionService.disconnect();
            connectButton.setText("Connect");
            setConnectionStatus("Disconnected", null);
            listenButton.setText("Listen");
            listenButton.setDisable(true);
            publishButton.setDisable(true);
            destinationCombo.getItems().clear();
            publishDestinationCombo.getItems().clear();
            setConnectionFieldsDisabled(false);
            return;
        }

        // Unlike HTTP, plain TCP has no universal default-port convention that Artemis's own URI
        // parser applies for us (a bare "tcp://host" parses to an explicit port=-1, not 61616), so
        // a virtual service still gets a real port here - just the well-known Artemis default,
        // rather than one the user has to know/type themselves.
        var brokerPort = virtualServiceCheckBox.isSelected()
                ? String.valueOf(JmsSpyPreferences.DEFAULT_BROKER_PORT)
                : brokerPortField.getText();
        var brokerUrl = "tcp://" + brokerHostField.getText() + ":" + brokerPort;
        // Anonymous Login always wins over whatever's left typed in the fields, matching how
        // Virtual Service overrides brokerPortField's own text rather than requiring it be cleared.
        var username = anonymousLoginCheckBox.isSelected() ? "" : usernameField.getText();
        var password = anonymousLoginCheckBox.isSelected() ? "" : passwordField.getText();
        try
        {
            jmsConnectionService.connect(brokerUrl, username, password);
            connectButton.setText("Disconnect");
            setConnectionStatus("Connected to " + brokerUrl, STATUS_SUCCESS_STYLE_CLASS);
            listenButton.setDisable(false);
            publishButton.setDisable(false);
            setConnectionFieldsDisabled(true);
            savePreferences();
            refreshDestinationList(brokerHostField.getText(), username, password);
        }
        catch (JMSException | RuntimeException ex)
        {
            log.error("Failed to connect to broker {}", brokerUrl, ex);
            setConnectionStatus("Connection failed: " + ex.getMessage(), STATUS_WARNING_STYLE_CLASS);
        }
    }

    /**
     * The broker connection details can't be changed without disconnecting first, so they're
     * locked while connected. brokerPortField/usernameField/passwordField are special cases:
     * re-enabling them on disconnect would ignore Virtual Service's/Anonymous Login's own claim
     * on those fields, so each is only re-enabled here when its owning checkbox is unchecked.
     */
    private void setConnectionFieldsDisabled(boolean disabled)
    {
        brokerHostField.setDisable(disabled);
        brokerPortField.setDisable(disabled || virtualServiceCheckBox.isSelected());
        virtualServiceCheckBox.setDisable(disabled);
        anonymousLoginCheckBox.setDisable(disabled);
        usernameField.setDisable(disabled || anonymousLoginCheckBox.isSelected());
        passwordField.setDisable(disabled || anonymousLoginCheckBox.isSelected());
    }

    /**
     * Sets the connection status text and, via a style class rather than a hardcoded color,
     * whether it reads as success (green), a problem (yellow), or neither - in which case it
     * falls back to the current theme's own default label color (light/dark mode).
     */
    private void setConnectionStatus(String text, String statusStyleClass)
    {
        connectionStatusLabel.setText(text);
        connectionStatusLabel.getStyleClass().removeAll(STATUS_SUCCESS_STYLE_CLASS, STATUS_WARNING_STYLE_CLASS);
        if (statusStyleClass != null)
        {
            connectionStatusLabel.getStyleClass().add(statusStyleClass);
        }
    }

    /**
     * Looks up the broker's known addresses via Jolokia and offers them as autocomplete
     * suggestions on both destination combo boxes. Runs off the JavaFX thread since it's a
     * network call; a failure here is non-fatal, the destination fields stay freely editable.
     */
    private void refreshDestinationList(String brokerHost, String username, String password)
    {
        var jolokiaUrl = jolokiaClient.deriveJolokiaUrl(brokerHost, jolokiaPort, jolokiaPath,
                virtualServiceCheckBox.isSelected());
        CompletableFuture.supplyAsync(() ->
        {
            try
            {
                return jolokiaClient.listTopicAddresses(jolokiaUrl, username, password, addressSearchMbean);
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
            setConnectionStatus("Enter at least one destination name before listening", null);
            return;
        }

        try
        {
            jmsConnectionService.listen(destinations, this::onMessageReceived);
            listenButton.setText("Stop");
            savePreferences();
        }
        catch (JMSException | RuntimeException ex)
        {
            log.error("Failed to listen to {}", destinations, ex);
            setConnectionStatus("Listen failed: " + ex.getMessage(), null);
        }
    }

    private List<String> parseDestinationList(String text)
    {
        return Arrays.stream(text.split(",")).map(String::trim).filter(StringUtils::hasText).toList();
    }

    private static int parseIntOrDefault(String text, int defaultValue)
    {
        try
        {
            return Integer.parseInt(text.trim());
        }
        catch (NumberFormatException ex)
        {
            return defaultValue;
        }
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

        try
        {
            var properties = parsePropertiesToPublish(messagePropertiesToPublishArea.getText());
            jmsConnectionService.publish(destination, messageToPublishArea.getText(), properties);
            publishStatusLabel.setText("Published to " + destination);
            savePreferences();
        }
        catch (JMSException | RuntimeException ex)
        {
            log.error("Failed to publish to {}", destination, ex);
            publishStatusLabel.setText("Publish failed: " + ex.getMessage());
        }
    }

    /**
     * Parses "name = value" lines from the Properties tab into a map; blank lines and lines
     * without an "=" (still being typed, or just stray whitespace) are silently skipped rather
     * than rejecting the whole publish.
     */
    private static Map<String, String> parsePropertiesToPublish(String text)
    {
        var properties = new LinkedHashMap<String, String>();
        for (var line : text.split("\\R"))
        {
            if (!StringUtils.hasText(line) || !line.contains("="))
            {
                continue;
            }
            var parts = line.split("=", 2);
            var name = parts[0].trim();
            var value = parts[1].trim();
            if (StringUtils.hasText(name))
            {
                properties.put(name, value);
            }
        }
        return properties;
    }

    private void onMessageReceived(Message message)
    {
        // extractContent reads formatJsonCheckBox, a JavaFX control, so the whole line has to be
        // built on the FX thread - onMessageReceived itself runs on a JMS provider thread.
        Platform.runLater(() ->
        {
            var line = "[" + extractDestinationName(message) + "] " + extractContent(message);
            var propertiesBlock = "[" + extractDestinationName(message) + "]" + System.lineSeparator()
                    + extractProperties(message);
            if (appendRadio.isSelected())
            {
                messageArea.appendText(line + System.lineSeparator());
                messagePropertiesArea.appendText(propertiesBlock + System.lineSeparator());
            }
            else
            {
                messageArea.setText(line);
                messagePropertiesArea.setText(propertiesBlock);
            }
        });
    }

    private String extractDestinationName(Message message)
    {
        try
        {
            if (message.getJMSDestination() instanceof Topic topic)
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
                var text = textMessage.getText();
                return formatJsonCheckBox.isSelected() ? formatIfJson(text) : text;
            }
            return message.toString();
        }
        catch (JMSException ex)
        {
            log.error("Failed to read message content", ex);
            return "<unreadable message: " + ex.getMessage() + ">";
        }
    }

    private String extractProperties(Message message)
    {
        try
        {
            var names = new ArrayList<String>();
            var propertyNames = message.getPropertyNames();
            while (propertyNames.hasMoreElements())
            {
                names.add((String) propertyNames.nextElement());
            }
            if (names.isEmpty())
            {
                return "<no properties>";
            }
            names.sort(Comparator.naturalOrder());
            var builder = new StringBuilder();
            for (var name : names)
            {
                builder.append(name).append(" = ").append(message.getObjectProperty(name))
                        .append(System.lineSeparator());
            }
            return builder.toString().stripTrailing();
        }
        catch (JMSException ex)
        {
            log.error("Failed to read message properties", ex);
            return "<unreadable properties: " + ex.getMessage() + ">";
        }
    }

    /**
     * Pretty-prints the message body when it's valid JSON; non-JSON text (the common case for
     * arbitrary test messages) is returned unchanged rather than surfacing a parse error.
     */
    private static String formatIfJson(String text)
    {
        if (!StringUtils.hasText(text))
        {
            return text;
        }
        try
        {
            return PRETTY_GSON.toJson(JsonParser.parseString(text));
        }
        catch (JsonSyntaxException ex)
        {
            return text;
        }
    }

    private void savePreferences()
    {
        preferencesStore.save(new JmsSpyPreferences(
                brokerHostField.getText(),
                parseIntOrDefault(brokerPortField.getText(), JmsSpyPreferences.DEFAULT_BROKER_PORT),
                usernameField.getText(),
                destinationCombo.getEditor().getText(),
                appendRadio.isSelected(),
                darkModeCheckBox.isSelected(),
                publishDestinationCombo.getEditor().getText(),
                jolokiaPort,
                jolokiaPath,
                addressSearchMbean,
                virtualServiceCheckBox.isSelected(),
                formatJsonCheckBox.isSelected(),
                anonymousLoginCheckBox.isSelected()));
    }
}
