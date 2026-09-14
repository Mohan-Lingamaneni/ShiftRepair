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

public class RecoveryDetailController {

    @FXML
    private Button backButton;

    @FXML
    private Label recoveryStatus;

    @FXML
    private Label currentOffer;

    @FXML
    private Label flowOffer;

    @FXML
    private Label flowResult;

    @FXML
    private Button declineButton;

    @FXML
    private Button acceptButton;

    @FXML
    private Button cancelButton;

    @FXML
    private TableView<Candidate> candidateTable;

    @FXML
    private TableColumn<Candidate, String> rankColumn;

    @FXML
    private TableColumn<Candidate, String> candidateColumn;

    @FXML
    private TableColumn<Candidate, String> scoreColumn;

    @FXML
    private TableColumn<Candidate, String> candidateStatusColumn;

    @FXML
    private TableColumn<Candidate, String> availabilityColumn;

    @FXML
    private TableColumn<Candidate, String> overtimeColumn;

    private int currentOfferIndex;

    @FXML
    private void initialize() {
        rankColumn.setCellValueFactory(cell -> cell.getValue().rank());
        candidateColumn.setCellValueFactory(cell -> cell.getValue().name());
        scoreColumn.setCellValueFactory(cell -> cell.getValue().score());
        candidateStatusColumn.setCellValueFactory(cell -> cell.getValue().status());
        availabilityColumn.setCellValueFactory(cell -> cell.getValue().availability());
        overtimeColumn.setCellValueFactory(cell -> cell.getValue().overtimeRisk());
        candidateStatusColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                getStyleClass().removeAll("status-offer-sent", "status-waiting", "status-declined", "status-accepted");
                setText(empty ? null : status);
                if (!empty) {
                    getStyleClass().add("status-" + status.toLowerCase().replace(' ', '-'));
                }
            }
        });

        candidateTable.setItems(FXCollections.observableArrayList(
                new Candidate("1", "Jordan Lee", "92", "Offer Sent", "Available", "No"),
                new Candidate("2", "Priya Patel", "86", "Waiting", "Available", "No"),
                new Candidate("3", "Marcus Johnson", "74", "Waiting", "Available", "No")
        ));
        currentOfferIndex = 0;
    }

    @FXML
    private void simulateDecline() {
        Candidate currentCandidate = candidateTable.getItems().get(currentOfferIndex);
        currentCandidate.status().set("Declined");
        currentOfferIndex++;

        Candidate nextCandidate = candidateTable.getItems().get(currentOfferIndex);
        nextCandidate.status().set("Offer Sent");
        currentOffer.setText(nextCandidate.name().get());
        flowOffer.setText(nextCandidate.name().get() + " - Offer Sent");
        candidateTable.refresh();
        declineButton.setDisable(currentOfferIndex == candidateTable.getItems().size() - 1);
    }

    @FXML
    private void simulateAccept() {
        Candidate currentCandidate = candidateTable.getItems().get(currentOfferIndex);
        currentCandidate.status().set("Accepted");
        recoveryStatus.setText("COVERED");
        recoveryStatus.getStyleClass().remove("recovery-status");
        recoveryStatus.getStyleClass().add("recovery-covered");
        currentOffer.setText(currentCandidate.name().get() + " - Accepted");
        flowOffer.setText(currentCandidate.name().get() + " - Accepted");
        flowResult.setText("Shift Covered");
        candidateTable.refresh();
        disableRecoveryActions();
    }

    @FXML
    private void cancelRecovery() {
        recoveryStatus.setText("CANCELLED");
        recoveryStatus.getStyleClass().remove("recovery-status");
        recoveryStatus.getStyleClass().add("recovery-cancelled");
        currentOffer.setText("None");
        flowResult.setText("Recovery Cancelled");
        disableRecoveryActions();
    }

    @FXML
    private void backToDashboard() {
        AppShellController shellController = (AppShellController) backButton
                .getScene()
                .getRoot()
                .getUserData();
        shellController.showDashboard();
    }

    private void disableRecoveryActions() {
        declineButton.setDisable(true);
        acceptButton.setDisable(true);
        cancelButton.setDisable(true);
    }

    private static class Candidate {
        private final StringProperty rank;
        private final StringProperty name;
        private final StringProperty score;
        private final StringProperty status;
        private final StringProperty availability;
        private final StringProperty overtimeRisk;

        private Candidate(String rank, String name, String score, String status,
                          String availability, String overtimeRisk) {
            this.rank = new SimpleStringProperty(rank);
            this.name = new SimpleStringProperty(name);
            this.score = new SimpleStringProperty(score);
            this.status = new SimpleStringProperty(status);
            this.availability = new SimpleStringProperty(availability);
            this.overtimeRisk = new SimpleStringProperty(overtimeRisk);
        }

        private StringProperty rank() {
            return rank;
        }

        private StringProperty name() {
            return name;
        }

        private StringProperty score() {
            return score;
        }

        private StringProperty status() {
            return status;
        }

        private StringProperty availability() {
            return availability;
        }

        private StringProperty overtimeRisk() {
            return overtimeRisk;
        }
    }
}