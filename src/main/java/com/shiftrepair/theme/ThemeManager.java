package com.shiftrepair.theme;

import javafx.scene.Scene;

public final class ThemeManager {

    public enum Mode {
        LIGHT,
        DARK
    }

    private static final String LIGHT_THEME = "/com/shiftrepair/css/theme-light.css";
    private static final String DARK_THEME = "/com/shiftrepair/css/theme-dark.css";
    private static Mode currentMode = Mode.DARK;

    private ThemeManager() {
    }

    public static void apply(Scene scene) {
        apply(scene, currentMode);
    }

    public static void toggle(Scene scene) {
        currentMode = currentMode == Mode.LIGHT ? Mode.DARK : Mode.LIGHT;
        apply(scene, currentMode);
    }

    public static Mode getCurrentMode() {
        return currentMode;
    }

    private static void apply(Scene scene, Mode mode) {
        scene.getStylesheets().removeIf(stylesheet -> stylesheet.endsWith("theme-light.css")
                || stylesheet.endsWith("theme-dark.css"));

        String themePath = mode == Mode.LIGHT ? LIGHT_THEME : DARK_THEME;
        String themeUrl = ThemeManager.class.getResource(themePath).toExternalForm();
        scene.getStylesheets().add(themeUrl);
    }
}
