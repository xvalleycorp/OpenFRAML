//
// Copyright 2026 The OpenFRAML Authors
// SPDX-License-Identifier: Apache-2.0
//

package vn.io.dxa.openframl.report;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Which days count as working days for a reporting deadline. Public holidays move from year to year (Lunar New Year,
 * days off swapped by government decision), so the bank supplies them; nothing here knows a country's calendar.
 *
 * @param weekend  the days of the week that are never working days
 * @param holidays the public holidays
 */
public record WorkingDays(Set<DayOfWeek> weekend, Set<LocalDate> holidays) {

    public WorkingDays {
        weekend = weekend.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(weekend));
        holidays = Set.copyOf(holidays);
    }

    /** Monday to Friday, with these public holidays. */
    public static WorkingDays mondayToFriday(Collection<LocalDate> holidays) {
        return new WorkingDays(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), Set.copyOf(holidays));
    }

    public boolean isWorkingDay(LocalDate date) {
        return !weekend.contains(date.getDayOfWeek()) && !holidays.contains(date);
    }

    /**
     * The working day {@code days} working days after {@code date}: one working day after a Friday is the Monday,
     * if that is not a holiday.
     *
     * @throws IllegalArgumentException if {@code days} is negative, or every day of the week is a weekend day
     */
    public LocalDate plus(LocalDate date, int days) {
        if (days < 0) {
            throw new IllegalArgumentException("days must not be negative: " + days);
        }
        if (weekend.size() == 7) {
            throw new IllegalArgumentException("every day of the week is a weekend day");
        }
        LocalDate day = date;
        for (int left = days; left > 0; ) {
            day = day.plusDays(1);
            if (isWorkingDay(day)) {
                left--;
            }
        }
        return day;
    }
}
