package com.shiftrepair.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** An absolute, half-open interval [start, end). Adjacent intervals do not overlap. */
public record TimeWindow(Instant start, Instant end) {
    public TimeWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
    }

    public boolean overlaps(TimeWindow other) {
        Objects.requireNonNull(other, "other");
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    public boolean contains(TimeWindow other) {
        Objects.requireNonNull(other, "other");
        return !other.start.isBefore(start) && !other.end.isAfter(end);
    }

    public Duration duration() {
        return Duration.between(start, end);
    }
}
