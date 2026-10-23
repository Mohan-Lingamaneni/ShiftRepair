package com.shiftrepair.model;

import java.util.List;
import java.util.Objects;

/** Explicit positive windows only. No windows means no declared availability. */
public record Availability(List<TimeWindow> windows) {
    public Availability {
        windows = List.copyOf(Objects.requireNonNull(windows, "windows"));
    }

    /** Adjacent or overlapping windows may jointly cover a shift. */
    public boolean covers(TimeWindow requested) {
        Objects.requireNonNull(requested, "requested");
        var coveredUntil = requested.start();
        for (TimeWindow window : windows.stream()
                .sorted(java.util.Comparator.comparing(TimeWindow::start)).toList()) {
            if (window.end().isAfter(coveredUntil)) {
                if (window.start().isAfter(coveredUntil)) {
                    return false;
                }
                coveredUntil = window.end();
                if (!coveredUntil.isBefore(requested.end())) {
                    return true;
                }
            }
        }
        return false;
    }
}
