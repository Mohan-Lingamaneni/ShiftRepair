package com.shiftrepair.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

public class RecoveriesController {

    @FXML
    private TableView<RecoveryRow> recoveryTable;
    @FXML
    private TableColumn<RecoveryRow, String> employeeColumn;
    @FXML
    private TableColumn<RecoveryRow, String> shiftColumn;
    @FXML
    private TableColumn<RecoveryRow, String> roleColumn;
    @FXML
    private TableColumn<RecoveryRow, String> statusColumn;
    @FXML
    private TableColumn<RecoveryRow, String> candidateColumn;
    @FXML
    private TableColumn<RecoveryRow, String> scoreColumn;
    @FXML
    private Label selectedEmployee;
    @FXML
    private Label selectedShift;
    @FXML
    private Label selectedStatus;
    @FXML
    private Label selectedCandidate;
    @FXML
    private Label selectedScore;
    @FXML
    private Button viewRecoveryButton;

    @FXML
    private void initialize() {
        employeeColumn.setCellValueFactory(cell -> cell.getValue().employee());
        shiftColumn.setCellValueFactory(cell -> cell.getValue().shift());
        roleColumn.setCellValueFactory(cell -> cell.getValue().role());
        statusColumn.setCellValueFactory(cell -> cell.getValue().status());
        candidateColumn.setCellValueFactory(cell -> cell.getValue().candidate());
        scoreColumn.setCellValueFactory(cell -> cell.getValue().score());
        statusColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                getStyleClass().removeAll("status-searching", "status-covered", "status-cancelled");
                setText(empty ? null : status);
                if (!empty) {
                    getStyleClass().add("status-" + status.toLowerCase());
                }
            }
        });

        recoveryTable.setItems(FXCollections.observableArrayList(
                new RecoveryRow("Sarah Martinez", "4:00 PM - 10:00 PM", "Cashier", "SEARCHING", "Jordan Lee", "92"),
                new RecoveryRow("Emily Chen", "12:00 PM - 6:00 PM", "Stock Associate", "COVERED", "Noah Williams", "88"),
                new RecoveryRow("Priya Patel", "10:00 AM - 4:00 PM", "Barista", "COVERED", "Avery Thompson", "84"),
                new RecoveryRow("Marcus Johnson", "5:00 PM - 11:00 PM", "Shift Lead", "CANCELLED", "-", "-"),
                new RecoveryRow("Noah Williams", "9:00 AM - 3:00 PM", "Stock Associate", "COVERED", "Avery Thompson", "81")
        ));

        recoveryTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> updateSelection(selected));
        recoveryTable.getSelectionModel().selectFirst();
    }

    private void updateSelection(RecoveryRow selected) {
        if (selected == null) {
            viewRecoveryButton.setDisable(true);
            return;
        }

        selectedEmployee.setText(selected.employee().get());
        selectedShift.setText(selected.role().get() + "  |  " + selected.shift().get());
        selectedStatus.setText(selected.status().get().equals("SEARCHING")
                ? "SEARCHING FOR COVERAGE" : selected.status().get());
        selectedStatus.getStyleClass().removeAll("status-warning", "status-success", "status-danger");
        selectedStatus.getStyleClass().add(statusStyle(selected.status().get()));
        selectedCandidate.setText(selected.candidate().get());
        selectedScore.setText(selected.score().get());
        viewRecoveryButton.setDisable(!selected.employee().get().equals("Sarah Martinez"));
    }

    private String statusStyle(String status) {
        return switch (status) {
            case "COVERED" -> "status-success";
            case "CANCELLED" -> "status-danger";
            default -> "status-warning";
        };
    }

    @FXML
    private void viewRecovery() {
        AppShellController shellController = (AppShellController) viewRecoveryButton
                .getScene().getRoot().getUserData();
        shellController.showRecoveryDetail();
    }

    private static class RecoveryRow {
        private final StringProperty employee;
        private final StringProperty shift;
        private final StringProperty role;
        private final StringProperty status;
        private final StringProperty candidate;
        private final StringProperty score;

        private RecoveryRow(String employee, String shift, String role, String status,
                            String candidate, String score) {
            this.employee = new SimpleStringProperty(employee);
            this.shift = new SimpleStringProperty(shift);
            this.role = new SimpleStringProperty(role);
            this.status = new SimpleStringProperty(status);
            this.candidate = new SimpleStringProperty(candidate);
            this.score = new SimpleStringProperty(score);
        }

        private StringProperty employee() { return employee; }
        private StringProperty shift() { return shift; }
        private StringProperty role() { return role; }
        private StringProperty status() { return status; }
        private StringProperty candidate() { return candidate; }
        private StringProperty score() { return score; }
    }
}