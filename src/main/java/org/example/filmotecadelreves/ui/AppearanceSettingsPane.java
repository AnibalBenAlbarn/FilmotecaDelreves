package org.example.filmotecadelreves.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.example.filmotecadelreves.ui.AjustesUI.DARK_THEME_FILE;

/**
 * Panel reutilizable para ajustes de apariencia
 * - Genera / escribe src/main/resources/Styles.css
 * - Guarda preferencias en config.json (fusionando con otras claves)
 * - Llama a mainUI.loadStylesheet() para recargar estilos en la UI
 *
 * Integración:
 *   AppearanceSettingsPane appearance = new AppearanceSettingsPane(primaryStage, mainUI);
 *   someContainer.getChildren().add(appearance.getNode());
 */
public class AppearanceSettingsPane {

    private final VBox root;
    private final ComboBox<String> themeBox;
    private final ColorPicker accentPicker;
    private final ComboBox<String> fontFamilyBox;
    private final ComboBox<Integer> fontSizeBox;
    private final Button applyBtn;
    private final Button saveBtn;
    private final Button resetBtn;
    private final Region previewArea;
    private final Stage owner;
    private final MainUI mainUI;

    private static final String CONFIG_FILE = "config.json";
    private static final String CSS_OUTPUT = "src/main/resources/Styles.css";

    public AppearanceSettingsPane(Stage owner, MainUI mainUI) {
        this.owner = owner;
        this.mainUI = mainUI;
        root = new VBox(12);
        root.setPadding(new Insets(12));
        root.setFillWidth(true);

        // Controles
        themeBox = new ComboBox<>();
        themeBox.getItems().addAll("Oscuro", "Claro");
        themeBox.setValue("Oscuro");

        accentPicker = new ColorPicker(Color.web("#6c63ff"));

        fontFamilyBox = new ComboBox<>();
        fontFamilyBox.getItems().addAll(
                "System", "Segoe UI", "Roboto", "Arial", "Tahoma", "Verdana", "Monospace"
        );
        fontFamilyBox.setValue("Segoe UI");

        fontSizeBox = new ComboBox<>();
        fontSizeBox.getItems().addAll(12, 13, 14, 15, 16, 18, 20);
        fontSizeBox.setValue(13);

        applyBtn = new Button("Aplicar (vista)");
        saveBtn = new Button("Guardar");
        resetBtn = new Button("Reset valores");

        // Controls layout
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(6));
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setPercentWidth(35);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setPercentWidth(65);
        grid.getColumnConstraints().addAll(c1, c2);

        grid.add(new Label("Tema:"), 0, 0);
        grid.add(themeBox, 1, 0);

        grid.add(new Label("Color acento:"), 0, 1);
        grid.add(accentPicker, 1, 1);

        grid.add(new Label("Fuente:"), 0, 2);
        grid.add(fontFamilyBox, 1, 2);

        grid.add(new Label("Tamaño fuente:"), 0, 3);
        grid.add(fontSizeBox, 1, 3);

        HBox buttons = new HBox(10, applyBtn, saveBtn, resetBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);

        // Preview area
        previewArea = new Region();
        previewArea.setPrefHeight(120);
        previewArea.setStyle("-fx-background-radius: 8; -fx-border-radius: 8; -fx-border-width: 1;");
        updatePreviewStyle(); // inicial

        // Listeners
        themeBox.setOnAction(e -> updatePreviewStyle());
        accentPicker.setOnAction(e -> updatePreviewStyle());
        fontFamilyBox.setOnAction(e -> updatePreviewStyle());
        fontSizeBox.setOnAction(e -> updatePreviewStyle());

        applyBtn.setOnAction(e -> {
            try {
                applyStylesToApp(false);
            } catch (Exception ex) {
                showError("No se pudo aplicar estilos: " + ex.getMessage());
                ex.printStackTrace();
            }
        });

        saveBtn.setOnAction(e -> {
            try {
                applyStylesToApp(true); // guarda también en config.json
                showInfo("Preferencias guardadas y aplicadas.");
            } catch (Exception ex) {
                showError("No se pudo guardar estilos: " + ex.getMessage());
                ex.printStackTrace();
            }
        });

        resetBtn.setOnAction(e -> {
            resetDefaults();
            updatePreviewStyle();
        });

        // Montaje
        root.getChildren().addAll(new Label("Apariencia"), grid, new Label("Vista previa:"), previewArea, buttons);
    }

    public Node getNode() {
        return root;
    }

    private void updatePreviewStyle() {
        String theme = themeBox.getValue();
        String accent = colorToHex(accentPicker.getValue());
        String fontFamily = fontFamilyBox.getValue();
        int fontSize = fontSizeBox.getValue();

        String bg = theme.equals("Claro") ? "#f4f4f9" : "#1e1e2f";
        String text = theme.equals("Claro") ? "#222222" : "#e7e7e7";
        String border = theme.equals("Claro") ? "#d0d0df" : "#3c3c50";

        previewArea.setStyle(
                String.format("-fx-background-color: %s; -fx-border-color: %s; -fx-border-width:1; -fx-background-radius:8; -fx-border-radius:8; -fx-font-family: '%s'; -fx-font-size:%dpx; -fx-padding:10; -fx-text-fill:%s;",
                        bg, border, fontFamily, fontSize, text)
        );
    }

    private void resetDefaults() {
        themeBox.setValue("Oscuro");
        accentPicker.setValue(Color.web("#6c63ff"));
        fontFamilyBox.setValue("Segoe UI");
        fontSizeBox.setValue(13);
    }

    /**
     * Crea el CSS, lo escribe en disco y (si mainUI != null) pide recargar stylesheet.
     * @param saveToConfig si true también guarda las preferencias en config.json
     */
    private void applyStylesToApp(boolean saveToConfig) throws IOException {
        String css = generateCss(
                themeBox.getValue(),
                colorToHex(accentPicker.getValue()),
                fontFamilyBox.getValue(),
                fontSizeBox.getValue()
        );

        writeCssFile(css);

        // Guardar en config.json (fusionando si existe)
        if (saveToConfig) {
            JSONObject uiObj = new JSONObject();
            uiObj.put("theme", themeBox.getValue());
            uiObj.put("accent", colorToHex(accentPicker.getValue()));
            uiObj.put("fontFamily", fontFamilyBox.getValue());
            uiObj.put("fontSize", fontSizeBox.getValue());
            saveUiConfig(uiObj);
        }

        // Recargar stylesheet en la aplicación principal (si se proporcionó)
        if (mainUI != null) {
            // Tu MainUI ya tiene loadStylesheet() que carga src/main/resources/Styles.css
            mainUI.loadStylesheet(DARK_THEME_FILE);
        }
    }

    private String generateCss(String theme, String accentHex, String fontFamily, int fontSize) {
        // Paleta según tema
        String bg, bgLight, textLight, textDark, border;
        if ("Claro".equalsIgnoreCase(theme)) {
            bg = "#f4f4f9";
            bgLight = "#ffffff";
            textLight = "#222222";
            textDark = "#222222";
            border = "#d0d0df";
        } else {
            // Oscuro por defecto
            bg = "#1e1e2f";
            bgLight = "#2b2b3f";
            textLight = "#e7e7e7";
            textDark = "#e7e7e7";
            border = "#3c3c50";
        }

        // Aquí definimos un CSS moderno y limpio con variables "inline"
        return "/* Auto-generado por AppearanceSettingsPane */\n" +
                String.format(":root {\n" +
                        "    -color-primary: %s;\n" +
                        "    -color-bg-dark: %s;\n" +
                        "    -color-bg-light: %s;\n" +
                        "    -color-text-light: %s;\n" +
                        "    -color-text-dark: %s;\n" +
                        "    -color-border: %s;\n" +
                        "}\n", accentHex, bg, bgLight, textLight, textDark, border) +
                "/* General */\n" +
                String.format(".root {\n" +
                        "    -fx-background-color: %s;\n" +
                        "    -fx-font-family: '%s';\n" +
                        "    -fx-font-size: %dpx;\n" +
                        "    -fx-text-fill: %s;\n" +
                        "}\n", bg, fontFamily, fontSize, textLight) +
                "/* Tabs */\n" +
                ".tab-pane .tab-header-area .tab-header-background {\n" +
                "    -fx-background-color: transparent;\n" +
                "}\n" +
                ".tab-pane .tab:selected {\n" +
                "    -fx-background-color: -color-primary;\n" +
                "    -fx-text-fill: white;\n" +
                "    -fx-background-radius: 8 8 0 0;\n" +
                "}\n" +
                "/* Buttons */\n" +
                ".button {\n" +
                "    -fx-background-color: linear-gradient(to bottom, -color-primary, derive(-color-primary, -10%));\n" +
                "    -fx-text-fill: white;\n" +
                "    -fx-font-weight: bold;\n" +
                "    -fx-background-radius: 8;\n" +
                "    -fx-padding: 8 14;\n" +
                "}\n" +
                ".button:hover {\n" +
                "    -fx-effect: dropshadow( gaussian , rgba(0,0,0,0.25) , 6, 0.0 , 0 , 2 );\n" +
                "}\n" +
                "/* Inputs */\n" +
                ".text-field, .combo-box, .text-area {\n" +
                "    -fx-background-color: -color-bg-light;\n" +
                "    -fx-text-fill: -color-text-dark;\n" +
                "    -fx-border-radius: 6;\n" +
                "    -fx-background-radius: 6;\n" +
                "    -fx-padding: 6;\n                -fx-border-color: -color-border;\n" +
                "}\n" +
                "/* Panel titled */\n" +
                ".titled-pane > .title {\n" +
                "    -fx-background-color: derive(-color-primary, -10%);\n" +
                "    -fx-text-fill: white;\n                -fx-font-weight: bold;\n" +
                "}\n" +
                "/* Preview border */\n" +
                ".preview-box {\n" +
                "    -fx-border-color: -color-border;\n" +
                "}\n";
    }

    private void writeCssFile(String css) throws IOException {
        File cssFile = new File(CSS_OUTPUT);
        // Asegurar directorio
        File parent = cssFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        try (Writer out = new OutputStreamWriter(new FileOutputStream(cssFile), StandardCharsets.UTF_8)) {
            out.write(css);
            out.flush();
        }
    }

    private void saveUiConfig(JSONObject uiObj) {
        JSONParser parser = new JSONParser();
        JSONObject top = new JSONObject();

        // Leer si existe para fusionar
        try (Reader r = new FileReader(CONFIG_FILE)) {
            Object parsed = parser.parse(r);
            if (parsed instanceof JSONObject) top = (JSONObject) parsed;
        } catch (FileNotFoundException e) {
            // ignorar: se creará uno nuevo
        } catch (IOException | ParseException e) {
            e.printStackTrace();
        }

        top.put("uiPreferences", uiObj);

        // Escribir
        try (Writer w = new FileWriter(CONFIG_FILE)) {
            w.write(top.toJSONString());
            w.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /* Utils */
    private String colorToHex(Color c) {
        return String.format("#%02X%02X%02X",
                (int) (c.getRed() * 255),
                (int) (c.getGreen() * 255),
                (int) (c.getBlue() * 255));
    }

    private void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.initOwner(owner);
        a.showAndWait();
    }

    private void showInfo(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.initOwner(owner);
        a.showAndWait();
    }
}
