package com.learning.spring.financialdashboard.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Backup file names and which old backups may be deleted. The rule: keep the newest backup of each of the last 30
 * days, and keep the first backup of every month for ever. Everything else can go. The newest backup is never deleted.
 */
public final class BackupRetention {

    public static final String PREFIX = "backup-";
    public static final String SUFFIX = ".sql.zip";
    public static final int KEEP_DAYS = 30;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    private BackupRetention() {
    }

    public static String fileName(LocalDateTime time) {
        return PREFIX + STAMP.format(time) + SUFFIX;
    }

    /** The time in a backup's file name, or empty when the name is not one of ours. */
    public static Optional<LocalDateTime> parse(String fileName) {
        if (fileName == null || !fileName.startsWith(PREFIX) || !fileName.endsWith(SUFFIX)) {
            return Optional.empty();
        }
        String stamp = fileName.substring(PREFIX.length(), fileName.length() - SUFFIX.length());
        try {
            return Optional.of(LocalDateTime.parse(stamp, STAMP));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** The backups (by time) that can be deleted today. */
    public static Set<LocalDateTime> toDelete(Collection<LocalDateTime> all, LocalDate today) {
        TreeSet<LocalDateTime> sorted = new TreeSet<>(all);
        Set<LocalDateTime> keep = new HashSet<>();
        if (sorted.isEmpty()) {
            return keep;
        }
        keep.add(sorted.last());                                   // the newest is never deleted
        Map<YearMonth, LocalDateTime> firstOfMonth = new HashMap<>();
        Map<LocalDate, LocalDateTime> newestOfDay = new HashMap<>();
        for (LocalDateTime t : sorted) {                            // oldest first
            firstOfMonth.putIfAbsent(YearMonth.from(t), t);
            newestOfDay.put(t.toLocalDate(), t);                    // later ones replace earlier ones
        }
        keep.addAll(firstOfMonth.values());
        LocalDate oldestDayKept = today.minusDays(KEEP_DAYS - 1L);
        newestOfDay.forEach((day, t) -> {
            if (!day.isBefore(oldestDayKept)) {
                keep.add(t);
            }
        });
        Set<LocalDateTime> delete = new HashSet<>(sorted);
        delete.removeAll(keep);
        return delete;
    }
}
