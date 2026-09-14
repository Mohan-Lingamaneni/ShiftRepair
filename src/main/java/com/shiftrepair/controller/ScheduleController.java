package com.shiftrepair.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

public class ScheduleController {

    @FXML
    private TableView<ShiftRow> scheduleTable;

    @FXML
    private TableColumn<ShiftRow, String> timeColumn;

    @FXML
    private TableColumn<ShiftRow, String> employeeColumn;

    @FXML
    private TableColumn<ShiftRow, String> roleColumn;

    @FXML
    private TableColumn<ShiftRow, String> statusColumn;

    @FXML
    private void initialize() {
        timeColumn.setCellValueFactory(cell -> cell.getValue().time());
        employeeColumn.setCellValueFactory(cell -> cell.getValue().employee());
        roleColumn.setCellValueFactory(cell -> cell.getValue().role());
        statusColumn.setCellValueFactory(cell -> cell.getValue().status());
        statusColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                getStyleClass().removeAll("status-scheduled", "status-called-out", "status-open");
                setText(empty ? null : status);
                if (!empty) {
                    getStyleClass().add("status-" + status.toLowerCase().replace(' ', '-'));
                }
            }
        });

        scheduleTable.setItems(FXCollections.observableArrayList(
                new ShiftRow("8:00 AM - 2:00 PM", "Jordan Lee", "Cashier", "SCHEDULED"),
                new ShiftRow("10:00 AM - 4:00 PM", "Priya Patel", "Barista", "SCHEDULED"),
                new ShiftRow("12:00 PM - 6:00 PM", "Emily Chen", "Stock Associate", "SCHEDULED"),
                new ShiftRow("2:00 PM - 8:00 PM", "Open Shift", "Barista", "OPEN"),
                new ShiftRow("4:00 PM - 10:00 PM", "Sarah Martinez", "Cashier", "CALLED OUT"),
                new ShiftRow("5:00 PM - 11:00 PM", "Marcus Johnson", "Shift Lead", "SCHEDULED"),
                new ShiftRow("6:00 PM - 10:00 PM", "Avery Thompson", "Cashier", "SCHEDULED"),
                new ShiftRow("7:00 PM - 11:00 PM", "Noah Williams", "Stock Associate", "SCHEDULED")
        ));
    }

    private static class ShiftRow {
        private final StringProperty time;
        private final StringProperty employee;
        private final StringProperty role;
        private final StringProperty status;

        private ShiftRow(String time, String employee, String role, String status) {
            this.time = new SimpleStringProperty(time);
            this.employee = new SimpleStringProperty(employee);
            this.role = new SimpleStringProperty(role);
            this.status = new SimpleStringProperty(status);
        }

        private StringProperty time() {
            return time;
        }

        private StringProperty employee() {
            return employee;
        }

        private StringProperty role() {
            return role;
        }

        private StringProperty status() {
            return status;
        }
    }
}