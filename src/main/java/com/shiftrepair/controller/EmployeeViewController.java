package com.shiftrepair.controller;

import com.shiftrepair.model.ShiftOffer;
import com.shiftrepair.model.ShiftOffer.Status;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.time.Instant;

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
    private Button viewOfferButton;

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
        refreshOfferState();
    }

    private void refreshOfferState() {
        ShiftOffer offer = IncomingShiftOfferController.getDemoOffer();
        if (offer != null) {
            offer.expireIfNeeded(Instant.now());
        }
        Status status = offer == null ? null : offer.getStatus();
        offerStatus.getStyleClass().removeAll(
                "status-warning", "status-success", "status-danger", "status-neutral");
        if (status == null) {
            offerStatus.setText("No Pending Offer");
            offerStatus.getStyleClass().add("status-neutral");
        } else {
            switch (status) {
                case PENDING -> {
                    offerStatus.setText("Pending Response");
                    offerStatus.getStyleClass().add("status-warning");
                }
                case ACCEPTED -> {
                    offerStatus.setText("Accepted");
                    offerStatus.getStyleClass().add("status-success");
                    responseMessage.setText("You accepted the 4:00 PM - 10:00 PM shift.");
                }
                case DECLINED -> {
                    offerStatus.setText("Declined");
                    offerStatus.getStyleClass().add("status-danger");
                    responseMessage.setText("You declined this coverage request.");
                }
                case EXPIRED -> {
                    offerStatus.setText("Expired");
                    offerStatus.getStyleClass().add("status-danger");
                    responseMessage.setText("This coverage request has expired.");
                }
            }
        }
        viewOfferButton.setDisable(offer == null);
    }

    @FXML
    private void viewOffer() {
        AppShellController shellController = (AppShellController) viewOfferButton
                .getScene().getRoot().getUserData();
        shellController.showIncomingShiftOffer();
    }

    private record ShiftRow(StringProperty day, StringProperty shift,
                            StringProperty role, StringProperty status) {
        private ShiftRow(String day, String shift, String role, String status) {
            this(new SimpleStringProperty(day), new SimpleStringProperty(shift),
                    new SimpleStringProperty(role), new SimpleStringProperty(status));
        }
    }
}
