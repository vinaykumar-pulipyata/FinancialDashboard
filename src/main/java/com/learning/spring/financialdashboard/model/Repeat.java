package com.learning.spring.financialdashboard.model;

/** How often a reminder comes back. The reminder's date is its first day. */
public enum Repeat {
    NONE,        // once
    WEEKLY,      // every 7 days, on the same weekday
    MONTHLY,     // on the same day of the month (a short month uses its last day)
    YEARLY       // on the same month and day (29 February falls on the 28th in other years)
}
