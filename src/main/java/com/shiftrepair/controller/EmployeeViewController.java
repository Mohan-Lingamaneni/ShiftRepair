package com.shiftrepair.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

public class EmployeeViewController {

    @FXML
    private ComboBox<String> availabilitySelector;
    @FXML
    private Label availabilityValue;
    @FXML
    private TableView<ShiftRow> upcomingShiftsTable;
    @FXML
    private TableColumn<ShiftRow, String> dayColumn;
    @FXML
    private TableColumn<ShiftRow, String> shiftColumn;
    @FXML
    private TableColumn<ShiftRow, String> roleColumn;
    @FXML
    private TableColumn<ShiftRow, String> statusColumn;
    @FXML
    private Label offerStatus;
    @FXML
    private Label responseMessage;
    @FXML
    private Button acceptButton;
    @FXML
    private Button declineButton;

    @FXML
    private void initialize() {
        dayColumn.setCellValueFactory(cell -> cell.getValue().day());
        shiftColumn.setCellValueFactory(cell -> cell.getValue().shift());
        roleColumn.setCellValueFactory(cell -> cell.getValue().role());
        statusColumn.setCellValueFactory(cell -> cell.getValue().status());
        upcomingShiftsTable.setItems(FXCollections.observableArrayList(
                new ShiftRow("Today", "8:00 AM - 2:00 PM", "Cashier", "Scheduled"),
                new ShiftRow("Tomorrow", "9:00 AM - 3:00 PM", "Cashier", "Scheduled"),
                new ShiftRow("Friday", "12:00 PM - 6:00 PM", "Cashier", "Scheduled")
        ));

        availabilitySelector.getItems().setAll("Available", "Unavailable");
        availabilitySelector.getSelectionModel().select("Available");
        availabilitySelector.valueProperty().addListener((observable, previous, current) -> {
            if (current != null) {
                availabilityValue.setText(current);
                availabilityValue.getStyleClass().removeAll("status-success", "status-neutral");
                availabilityValue.getStyleClass().add(current.equals("Available") ? "status-success" : "status-neutral");
            }
        });
    }

    @FXML
    private void acceptShift() {
        offerStatus.setText("Accepted");
        offerStatus.getStyleClass().removeAll("status-warning", "status-success", "status-danger");
        offerStatus.getStyleClass().add("status-success");
        responseMessage.setText("You accepted the 4:00 PM - 10:00 PM shift.");
        acceptButton.setDisable(true);
        declineButton.setDisable(true);
    }

    @FXML
    private void declineShift() {
        offerStatus.setText("Declined");
        offerStatus.getStyleClass().removeAll("status-warning", "status-success", "status-danger");
        offerStatus.getStyleClass().add("status-danger");
        responseMessage.setText("You declined this coverage request.");
        acceptButton.setDisable(true);
        declineButton.setDisable(true);
    }

    @FXML
    private void resetDemo() {
        offerStatus.setText("Pending Response");
        offerStatus.getStyleClass().removeAll("status-warning", "status-success", "status-danger");
        offerStatus.getStyleClass().add("status-warning");
        responseMessage.setText("");
        acceptButton.setDisable(false);
        declineButton.setDisable(false);
    }

    private record ShiftRow(StringProperty day, StringProperty shift,
                            StringProperty role, StringProperty status) {
        private ShiftRow(String day, String shift, String role, String status) {
            this(new SimpleStringProperty(day), new SimpleStringProperty(shift),
                    new SimpleStringProperty(role), new SimpleStringProperty(status));
        }
    }
}