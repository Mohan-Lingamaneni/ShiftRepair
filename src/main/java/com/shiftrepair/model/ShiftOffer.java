package com.shiftrepair.model;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

public class ShiftOffer {

    public enum Status {
        PENDING,
        ACCEPTED,
        DECLINED,
        EXPIRED
    }

    private final String id;
    private final String employee;
    private final String originalEmployee;
    private final String role;
    private final String location;
    private final LocalDate date;
    private final LocalTime startTime;
    private final LocalTime endTime;
    private final int coverageScore;
    private final Instant expiresAt;
    private final List<String> selectionReasons;

    private Status status = Status.PENDING;

    public ShiftOffer(
            String id,
            String employee,
            String originalEmployee,
            String role,
            String location,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime,
            int coverageScore,
            Instant expiresAt) {

        this(
                id, employee, originalEmployee, role, location,
                date, startTime, endTime, coverageScore,
                expiresAt, List.of()
        );
    }

    public ShiftOffer(
            String id,
            String employee,
            String originalEmployee,
            String role,
            String location,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime,
            int coverageScore,
            Instant expiresAt,
            List<String> selectionReasons) {

        this.id = Objects.requireNonNull(id);
        this.employee = Objects.requireNonNull(employee);
        this.originalEmployee = Objects.requireNonNull(originalEmployee);
        this.role = Objects.requireNonNull(role);
        this.location = Objects.requireNonNull(location);
        this.date = Objects.requireNonNull(date);
        this.startTime = Objects.requireNonNull(startTime);
        this.endTime = Objects.requireNonNull(endTime);
        this.coverageScore = coverageScore;
        this.expiresAt = Objects.requireNonNull(expiresAt);

        this.selectionReasons =
                List.copyOf(Objects.requireNonNull(selectionReasons))
                        .stream()
                        .map(String::strip)
                        .filter(reason -> !reason.isEmpty())
                        .toList();

        if (startTime.equals(endTime)) {
            throw new IllegalArgumentException(
                    "A shift must have different start and end times."
            );
        }
    }

    // A response is allowed only once and before the deadline.
    public boolean respond(Status response, Instant now) {
        if (response != Status.ACCEPTED && response != Status.DECLINED) {
            throw new IllegalArgumentException(
                    "Choose ACCEPTED or DECLINED."
            );
        }

        expireIfNeeded(now);

        if (status != Status.PENDING) {
            return false;
        }

        status = response;
        return true;
    }

    public boolean expireIfNeeded(Instant now) {
        if (status == Status.PENDING && !now.isBefore(expiresAt)) {
            status = Status.EXPIRED;
            return true;
        }

        return false;
    }

    public long getRemainingSeconds(Instant now) {
        if (status != Status.PENDING || !now.isBefore(expiresAt)) {
            return 0;
        }

        Duration remaining = Duration.between(now, expiresAt);

        // Round up so an available offer never displays 00:00.
        return remaining.getSeconds()
                + (remaining.getNano() > 0 ? 1 : 0);
    }

    public LocalDate getEndDate() {
        return endTime.isBefore(startTime)
                ? date.plusDays(1)
                : date;
    }

    public Duration getDuration() {
        return Duration.between(
                date.atTime(startTime),
                getEndDate().atTime(endTime)
        );
    }

    public String getId() {
        return id;
    }

    public String getEmployee() {
        return employee;
    }

    public String getOriginalEmployee() {
        return originalEmployee;
    }

    public String getRole() {
        return role;
    }

    public String getLocation() {
        return location;
    }

    public LocalDate getDate() {
        return date;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public int getCoverageScore() {
        return coverageScore;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Status getStatus() {
        return status;
    }

    public List<String> getSelectionReasons() {
        return selectionReasons;
    }
}