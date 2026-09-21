package com.shiftrepair.controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import com.shiftrepair.theme.ThemeManager;

import java.io.IOException;

public class AppShellController {

    @FXML
    private BorderPane shell;

    @FXML
    private Label pageTitle;

    @FXML
    private Button dashboardButton;

    @FXML
    private Button scheduleButton;

    @FXML
    private Button recoveriesButton;

    @FXML
    private Button employeesButton;

    @FXML
    private Button employeeViewButton;

    @FXML
    private Button themeToggleButton;

    @FXML
    private void initialize() {
        shell.setUserData(this);
        showPage("Dashboard", "/com/shiftrepair/fxml/dashboard-placeholder.fxml", dashboardButton);
    }

    @FXML
    public void showDashboard() {
        showPage("Dashboard", "/com/shiftrepair/fxml/dashboard-placeholder.fxml", dashboardButton);
    }

    @FXML
    private void toggleTheme() {
        ThemeManager.toggle(themeToggleButton.getScene());
        themeToggleButton.setText(ThemeManager.getCurrentMode() == ThemeManager.Mode.LIGHT ? "☾" : "☀");
    }

    public void showRecoveryDetail() {
        showPage("Recovery Detail", "/com/shiftrepair/fxml/recovery-detail.fxml", recoveriesButton);
    }

    @FXML
    private void showSchedule() {
        showPage("Schedule", "/com/shiftrepair/fxml/schedule-placeholder.fxml", scheduleButton);
    }

    @FXML
    private void showRecoveries() {
        showPage("Recoveries", "/com/shiftrepair/fxml/recoveries-placeholder.fxml", recoveriesButton);
    }

    @FXML
    private void showEmployees() {
        showPage("Employees", "/com/shiftrepair/fxml/employees-placeholder.fxml", employeesButton);
    }

    @FXML
    private void showEmployeeView() {
        showEmployeeViewPage();
    }

    public void showEmployeeViewPage() {
        showPage("Employee View", "/com/shiftrepair/fxml/employee-view-placeholder.fxml", employeeViewButton);
    }

    public void showIncomingShiftOffer() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(
                    "/com/shiftrepair/fxml/incoming-shift-offer.fxml"));
            Node page = loader.load();
            IncomingShiftOfferController controller = loader.getController();
            controller.setOnBack(this::showEmployeeViewPage);
            controller.setOnViewSchedule(this::showEmployeeViewPage);
            shell.setCenter(page);
            pageTitle.setText("Incoming Shift Offer");
            clearActiveNavigation();
            employeeViewButton.getStyleClass().add("active");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load incoming shift offer", exception);
        }
    }

    private void showPage(String title, String viewPath, Button selectedButton) {
        try {
            Node page = FXMLLoader.load(getClass().getResource(viewPath));
            shell.setCenter(page);
            pageTitle.setText(title);

            clearActiveNavigation();
            selectedButton.getStyleClass().add("active");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load page: " + viewPath, exception);
        }
    }

    private void clearActiveNavigation() {
        dashboardButton.getStyleClass().remove("active");
        scheduleButton.getStyleClass().remove("active");
        recoveriesButton.getStyleClass().remove("active");
        employeesButton.getStyleClass().remove("active");
        employeeViewButton.getStyleClass().remove("active");
    }
}
