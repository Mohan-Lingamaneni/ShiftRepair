package com.shiftrepair.model;

import java.math.BigDecimal;
import java.util.Objects;

/** Separate totals for the same explicit accounting period; neither total implies approval. */
public record WorkHourSummary(String employeeId, TimeWindow period,
                              BigDecimal scheduledHours, BigDecimal actualWorkedHours) {
    public WorkHourSummary {
        employeeId = ModelChecks.text(employeeId, "employeeId");
        Objects.requireNonNull(period, "period");
        scheduledHours = ModelChecks.hours(scheduledHours, "scheduledHours");
        actualWorkedHours = ModelChecks.hours(actualWorkedHours, "actualWorkedHours");
    }
}
