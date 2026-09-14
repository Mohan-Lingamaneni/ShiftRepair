package com.shiftrepair.controller;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

public class EmployeesController {

    @FXML
    private TextField searchField;
    @FXML
    private ComboBox<String> roleFilter;
    @FXML
    private TableView<EmployeeRow> employeeTable;
    @FXML
    private TableColumn<EmployeeRow, String> employeeColumn;
    @FXML
    private TableColumn<EmployeeRow, String> roleColumn;
    @FXML
    private TableColumn<EmployeeRow, String> availabilityColumn;
    @FXML
    private TableColumn<EmployeeRow, String> hoursColumn;
    @FXML
    private TableColumn<EmployeeRow, String> skillsColumn;
    @FXML
    private TableColumn<EmployeeRow, String> statusColumn;
    @FXML
    private Label selectedEmployee;
    @FXML
    private Label selectedRole;
    @FXML
    private Label selectedAvailability;
    @FXML
    private Label selectedHours;
    @FXML
    private Label selectedSkills;
    @FXML
    private Label selectedEmploymentStatus;
    @FXML
    private Label skillMatch;
    @FXML
    private Label availableForShift;
    @FXML
    private Label overtimeRisk;
    @FXML
    private Label eligibleForShift;

    private final ObservableList<EmployeeRow> employees = FXCollections.observableArrayList(
            new EmployeeRow("Jordan Lee", "Cashier", "Available", "24", "Cashier, Register", "Active", "Yes", "Yes", "No", "Yes"),
            new EmployeeRow("Priya Patel", "Barista", "Available", "28", "Barista, Register", "Active", "Yes", "Yes", "No", "Yes"),
            new EmployeeRow("Sarah Martinez", "Cashier", "Called Out", "32", "Cashier, Register", "Active", "Yes", "No", "No", "No"),
            new EmployeeRow("Marcus Johnson", "Shift Lead", "Available", "36", "Shift Lead, Cashier, Register", "Active", "Yes", "Yes", "No", "Yes"),
            new EmployeeRow("Emily Chen", "Stock Associate", "Available", "20", "Stock, Inventory", "Active", "No", "Yes", "No", "No"),
            new EmployeeRow("Avery Thompson", "Cashier", "Unavailable", "30", "Cashier, Register", "Active", "Yes", "No", "No", "No"),
            new EmployeeRow("Noah Williams", "Stock Associate", "Available", "26", "Stock, Inventory", "Active", "No", "Yes", "No", "No")
    );

    @FXML
    private void initialize() {
        employeeColumn.setCellValueFactory(cell -> cell.getValue().employee());
        roleColumn.setCellValueFactory(cell -> cell.getValue().role());
        availabilityColumn.setCellValueFactory(cell -> cell.getValue().availability());
        hoursColumn.setCellValueFactory(cell -> cell.getValue().hours());
        skillsColumn.setCellValueFactory(cell -> cell.getValue().skills());
        statusColumn.setCellValueFactory(cell -> cell.getValue().employmentStatus());
        availabilityColumn.setCellFactory(column -> statusCell("status-availability-"));
        statusColumn.setCellFactory(column -> statusCell("status-employment-"));

        FilteredList<EmployeeRow> filteredEmployees = new FilteredList<>(employees, employee -> true);
        searchField.textProperty().addListener((observable, previous, current) -> updateFilter(filteredEmployees));
        roleFilter.getItems().setAll("All Roles", "Cashier", "Barista", "Shift Lead", "Stock Associate");
        roleFilter.getSelectionModel().selectFirst();
        roleFilter.valueProperty().addListener((observable, previous, current) -> updateFilter(filteredEmployees));
        employeeTable.setItems(filteredEmployees);
        employeeTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> updateDetails(selected));
        employeeTable.getSelectionModel().selectFirst();
    }

    private TableCell<EmployeeRow, String> statusCell(String prefix) {
        return new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().removeAll("status-success", "status-danger", "status-neutral");
                setText(empty ? null : value);
                if (!empty) {
                    if (prefix.equals("status-availability-")) {
                        getStyleClass().add(value.equals("Available") ? "status-success"
                                : value.equals("Called Out") ? "status-danger" : "status-neutral");
                    } else {
                        getStyleClass().add("status-success");
                    }
                }
            }
        };
    }

    private void updateFilter(FilteredList<EmployeeRow> filteredEmployees) {
        String search = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        String selectedRole = roleFilter.getValue();
        filteredEmployees.setPredicate(employee ->
                employee.employee().get().toLowerCase().contains(search)
                        && (selectedRole == null || selectedRole.equals("All Roles")
                        || employee.role().get().equals(selectedRole)));
        if (!filteredEmployees.isEmpty()) {
            employeeTable.getSelectionModel().selectFirst();
        } else {
            updateDetails(null);
        }
    }

    private void updateDetails(EmployeeRow employee) {
        if (employee == null) {
            return;
        }
        selectedEmployee.setText(employee.employee().get());
        selectedRole.setText(employee.role().get());
        selectedAvailability.setText(employee.availability().get());
        selectedHours.setText(employee.hours().get());
        selectedSkills.setText(employee.skills().get());
        selectedEmploymentStatus.setText(employee.employmentStatus().get());
        skillMatch.setText(employee.skillMatch().get());
        availableForShift.setText(employee.availableForShift().get());
        overtimeRisk.setText(employee.overtimeRisk().get());
        eligibleForShift.setText(employee.eligibleForShift().get());
    }

    private static class EmployeeRow {
        private final StringProperty employee;
        private final StringProperty role;
        private final StringProperty availability;
        private final StringProperty hours;
        private final StringProperty skills;
        private final StringProperty employmentStatus;
        private final StringProperty skillMatch;
        private final StringProperty availableForShift;
        private final StringProperty overtimeRisk;
        private final StringProperty eligibleForShift;

        private EmployeeRow(String employee, String role, String availability, String hours,
                            String skills, String employmentStatus, String skillMatch,
                            String availableForShift, String overtimeRisk, String eligibleForShift) {
            this.employee = new SimpleStringProperty(employee);
            this.role = new SimpleStringProperty(role);
            this.availability = new SimpleStringProperty(availability);
            this.hours = new SimpleStringProperty(hours);
            this.skills = new SimpleStringProperty(skills);
            this.employmentStatus = new SimpleStringProperty(employmentStatus);
            this.skillMatch = new SimpleStringProperty(skillMatch);
            this.availableForShift = new SimpleStringProperty(availableForShift);
            this.overtimeRisk = new SimpleStringProperty(overtimeRisk);
            this.eligibleForShift = new SimpleStringProperty(eligibleForShift);
        }

        private StringProperty employee() { return employee; }
        private StringProperty role() { return role; }
        private StringProperty availability() { return availability; }
        private StringProperty hours() { return hours; }
        private StringProperty skills() { return skills; }
        private StringProperty employmentStatus() { return employmentStatus; }
        private StringProperty skillMatch() { return skillMatch; }
        private StringProperty availableForShift() { return availableForShift; }
        private StringProperty overtimeRisk() { return overtimeRisk; }
        private StringProperty eligibleForShift() { return eligibleForShift; }
    }
}