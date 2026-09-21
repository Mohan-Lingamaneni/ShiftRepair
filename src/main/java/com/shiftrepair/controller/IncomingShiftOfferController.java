package com.shiftrepair.controller;

import com.shiftrepair.model.ShiftOffer;
import com.shiftrepair.model.ShiftOffer.Status;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public class IncomingShiftOfferController {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern(
                    "EEE, MMM d, yyyy", Locale.US
            );

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    // Keeps the demo offer when navigating between pages.
    // This resets when the application closes.
    private static ShiftOffer demoOffer = createDemoOffer();

    @FXML private VBox offerCard;
    @FXML private VBox offerContent;
    @FXML private VBox emptyState;
    @FXML private VBox reasonsBox;
    @FXML private VBox confirmationBox;

    @FXML private FlowPane responseActions;

    @FXML private Label offerStatus;
    @FXML private Label employeeName;
    @FXML private Label shiftDate;
    @FXML private Label shiftTime;
    @FXML private Label shiftDuration;
    @FXML private Label role;
    @FXML private Label locationLabel;
    @FXML private Label originalEmployee;
    @FXML private Label timeRemaining;
    @FXML private Label deadlineHint;
    @FXML private Label responseMessage;
    @FXML private Label confirmationSummary;
    @FXML private Label demoLabel;

    @FXML private ToggleButton reasonsToggle;

    @FXML private Button acceptButton;
    @FXML private Button declineButton;
    @FXML private Button confirmAcceptButton;
    @FXML private Button doneButton;
    @FXML private Button resetButton;
    @FXML private Button scheduleButton;

    private ShiftOffer offer;
    private Timeline countdown;

    private boolean demoMode = true;
    private boolean confirming;

    private Runnable onViewSchedule;

    private Consumer<ShiftOffer> onResponse =
            completedOffer -> { };

    @FXML
    private void initialize() {
        countdown = new Timeline(
                new KeyFrame(
                        Duration.seconds(1),
                        event -> refreshOffer()
                )
        );

        countdown.setCycleCount(Timeline.INDEFINITE);

        // Stop this view's timer when it leaves the scene.
        // Returning uses the original expiration timestamp.
        offerCard.sceneProperty().addListener(
                (observable, oldScene, newScene) -> {
                    countdown.stop();
                    confirming = false;

                    if (newScene != null) {
                        refreshOffer();
                        startCountdown();
                    }
                }
        );

        showOffer(demoOffer);
    }

    @FXML
    private void acceptShift() {
        refreshOffer();

        if (!isPending()) {
            return;
        }

        confirming = true;

        confirmationSummary.setText(
                offer.getRole() + " · " + offer.getLocation()
                        + "\n"
                        + DATE_FORMAT.format(offer.getDate())
                        + " · "
                        + shiftTimeText()
        );

        renderState(Instant.now());
        confirmAcceptButton.requestFocus();
    }

    @FXML
    private void confirmAcceptance() {
        if (confirming) {
            respond(Status.ACCEPTED);
        }
    }

    @FXML
    private void cancelAcceptance() {
        confirming = false;
        refreshOffer();

        if (isPending()) {
            acceptButton.requestFocus();
        }
    }

    @FXML
    private void declineShift() {
        respond(Status.DECLINED);
    }

    private void respond(Status response) {
        if (offer == null) {
            return;
        }

        ShiftOffer respondingOffer = offer;
        Status previous = respondingOffer.getStatus();
        Instant now = Instant.now();

        // Checks the deadline again before recording a response.
        respondingOffer.respond(response, now);

        confirming = false;
        renderState(now);

        if (previous == Status.PENDING
                && respondingOffer.getStatus() != Status.PENDING) {
            onResponse.accept(respondingOffer);
        }
    }

    private void refreshOffer() {
        Instant now = Instant.now();
        ShiftOffer current = offer;

        boolean justExpired =
                current != null && current.expireIfNeeded(now);

        renderState(now);

        if (justExpired) {
            onResponse.accept(current);
        }
    }

    private void renderState(Instant now) {
        boolean pending = isPending();

        if (!pending) {
            countdown.stop();
            confirming = false;
        }

        show(offerContent, offer != null);
        show(emptyState, offer == null);
        show(offerStatus, offer != null);

        show(demoLabel, demoMode);
        show(resetButton, demoMode);
        show(scheduleButton, onViewSchedule != null);

        show(responseActions, !confirming);
        show(confirmationBox, confirming);
        show(doneButton, offer != null && !pending);

        acceptButton.setDisable(!pending || confirming);
        declineButton.setDisable(!pending || confirming);
        confirmAcceptButton.setDisable(!pending || !confirming);

        if (offer == null) {
            return;
        }

        offerStatus.getStyleClass().removeAll(
                "status-warning",
                "status-success",
                "status-danger",
                "status-neutral"
        );

        show(deadlineHint, pending);

        switch (offer.getStatus()) {
            case PENDING -> {
                setStatus("Pending Response", "status-warning");

                long seconds = offer.getRemainingSeconds(now);

                timeRemaining.setText(
                        String.format(
                                Locale.US,
                                "%02d:%02d",
                                seconds / 60,
                                seconds % 60
                        )
                );

                deadlineHint.setText(
                        seconds <= 60
                                ? "One minute or less to respond"
                                : "Time left to respond"
                );

                responseMessage.setText(
                        confirming
                                ? "Review the shift below before confirming."
                                : "Choose whether you can cover this shift."
                );
            }

            case ACCEPTED -> {
                setStatus("Accepted", "status-success");
                timeRemaining.setText("Response received");
                responseMessage.setText(
                        "You accepted this shift offer."
                );
            }

            case DECLINED -> {
                setStatus("Declined", "status-neutral");
                timeRemaining.setText("Response received");
                responseMessage.setText(
                        "You declined this shift offer."
                );
            }

            case EXPIRED -> {
                setStatus("Expired", "status-danger");
                timeRemaining.setText("00:00");
                responseMessage.setText(
                        "This offer expired. You can no longer respond."
                );
            }
        }
    }

    private void setStatus(String text, String styleClass) {
        offerStatus.setText(text);
        offerStatus.getStyleClass().add(styleClass);
    }

    @FXML
    private void toggleReasons() {
        boolean expanded = reasonsToggle.isSelected();

        show(reasonsBox, expanded);

        reasonsToggle.setText(
                "Why you were selected " + (expanded ? "▾" : "▸")
        );

        reasonsToggle.setAccessibleText(
                expanded
                        ? "Hide selection reasons"
                        : "Show selection reasons"
        );
    }

    @FXML
    private void dismissOffer() {
        if (offer == null || isPending()) {
            return;
        }

        if (demoMode) {
            demoOffer = null;
        }

        showOffer(null);
    }

    @FXML
    private void resetDemo() {
        if (!demoMode) {
            return;
        }

        demoOffer = createDemoOffer();
        showOffer(demoOffer);
    }

    @FXML
    private void viewSchedule() {
        if (onViewSchedule != null) {
            onViewSchedule.run();
        }
    }

    private void showOffer(ShiftOffer nextOffer) {
        countdown.stop();

        offer = nextOffer;
        confirming = false;

        reasonsToggle.setSelected(false);
        toggleReasons();
        reasonsBox.getChildren().clear();

        if (offer != null) {
            employeeName.setText(
                    "Offer for " + offer.getEmployee()
            );

            role.setText(offer.getRole());

            shiftDate.setText(
                    DATE_FORMAT.format(offer.getDate())
            );

            shiftTime.setText(shiftTimeText());

            long minutes = offer.getDuration().toMinutes();

            shiftDuration.setText(
                    minutes % 60 == 0
                            ? (minutes / 60) + "-hour shift"
                            : (minutes / 60) + " hr "
                            + (minutes % 60) + " min shift"
            );

            locationLabel.setText(
                    "Location: " + offer.getLocation()
            );

            originalEmployee.setText(
                    "Covering for " + offer.getOriginalEmployee()
            );

            // Display only reasons supplied with this offer.
            for (String reason :
                    offer.getSelectionReasons()
                            .stream()
                            .limit(3)
                            .toList()) {

                Label label = new Label("• " + reason);

                label.getStyleClass().add("detail-label");
                label.setWrapText(true);
                label.setMaxWidth(Double.MAX_VALUE);

                reasonsBox.getChildren().add(label);
            }
        }

        show(
                reasonsToggle,
                !reasonsBox.getChildren().isEmpty()
        );

        refreshOffer();
        startCountdown();
    }

    private boolean isPending() {
        return offer != null
                && offer.getStatus() == Status.PENDING;
    }

    private void startCountdown() {
        if (offerCard.getScene() != null && isPending()) {
            countdown.playFromStart();
        }
    }

    private String shiftTimeText() {
        return TIME_FORMAT.format(offer.getStartTime())
                + " - "
                + TIME_FORMAT.format(offer.getEndTime())
                + (
                offer.getEndDate().equals(offer.getDate())
                        ? ""
                        : " (next day)"
        );
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    // The recovery teammate can connect shared mock logic here.
    // This notification is not a database-save confirmation.
    public void setOnResponse(Consumer<ShiftOffer> handler) {
        onResponse = Objects.requireNonNull(handler);
    }

    // Supply a shared offer, or null when there is no offer.
    public void setOffer(ShiftOffer offer) {
        demoMode = false;
        showOffer(offer);
    }

    // The navigation teammate connects the employee schedule here.
    public void setOnViewSchedule(Runnable handler) {
        onViewSchedule = handler;
        show(scheduleButton, handler != null);
    }

    public ShiftOffer getOffer() {
        return offer;
    }

    // Sample data for the frontend demonstration.
    private static ShiftOffer createDemoOffer() {
        return new ShiftOffer(
                "DEMO-" + UUID.randomUUID(),
                "Jordan Lee",
                "Sarah Martinez",
                "Cashier",
                "Main Store",
                LocalDate.now().plusDays(1),
                LocalTime.of(16, 0),
                LocalTime.of(22, 0),
                92,
                Instant.now().plusSeconds(300),
                List.of(
                        "Available during these hours",
                        "Qualified for the Cashier role",
                        "Within your weekly hour limit"
                )
        );
    }
}