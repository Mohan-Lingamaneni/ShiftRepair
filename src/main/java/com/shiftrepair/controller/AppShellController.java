package com.shiftrepair.controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;

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
    private void initialize() {
        showPage("Dashboard", "/com/shiftrepair/fxml/dashboard-placeholder.fxml", dashboardButton);
    }

    @FXML
    private void showDashboard() {
        showPage("Dashboard", "/com/shiftrepair/fxml/dashboard-placeholder.fxml", dashboardButton);
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
        showPage("Employee View", "/com/shiftrepair/fxml/employee-view-placeholder.fxml", employeeViewButton);
    }

    private void showPage(String title, String viewPath, Button selectedButton) {
        try {
            Node page = FXMLLoader.load(getClass().getResource(viewPath));
            shell.setCenter(page);
            pageTitle.setText(title);

            dashboardButton.getStyleClass().remove("active");
            scheduleButton.getStyleClass().remove("active");
            recoveriesButton.getStyleClass().remove("active");
            employeesButton.getStyleClass().remove("active");
            employeeViewButton.getStyleClass().remove("active");
            selectedButton.getStyleClass().add("active");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load page: " + viewPath, exception);
        }
    }
}