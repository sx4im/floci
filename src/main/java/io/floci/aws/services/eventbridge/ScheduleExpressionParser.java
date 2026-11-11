package io.floci.aws.services.eventbridge;

import com.cronutils.model.Cron;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import io.floci.aws.core.common.AwsCronDefinitions;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ScheduleExpressionParser {

    private static final Pattern RATE_PATTERN = Pattern.compile(
            "^rate\\(\\s*(\\d+)\\s+(minutes?|hours?|days?|weeks?)\\s*\\)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CRON_PATTERN = Pattern.compile(
            "^cron\\((.+)\\)$",
            Pattern.CASE_INSENSITIVE);

    private static final CronParser CRON_PARSER = AwsCronDefinitions.newParser();

    private ScheduleExpressionParser() {}

    /**
     * Validates a schedule expression the way AWS PutRule does for rate expressions:
     * the unit must be singular for a value of 1 and plural otherwise, and only
     * minute, hour and day units are accepted. Non-rate expressions are left alone.
     * Already persisted schedules keep parsing leniently through {@link #parseRateToMillis}.
     */
    public static void validate(String expression) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        String trimmed = expression.trim();
        if (!trimmed.regionMatches(true, 0, "rate(", 0, 5)) {
            return;
        }
        Matcher rateMatcher = RATE_PATTERN.matcher(trimmed);
        if (!rateMatcher.matches()) {
            throw new IllegalArgumentException("Not a valid rate expression: " + expression);
        }
        int value = Integer.parseInt(rateMatcher.group(1));
        if (value < 1) {
            throw new IllegalArgumentException("Rate value must be >= 1, got: " + value);
        }
        String unit = rateMatcher.group(2).toLowerCase();
        if (unit.equals("week") || unit.equals("weeks")) {
            throw new IllegalArgumentException("Week-based rates are not supported by EventBridge rules: " + expression);
        }
        boolean plural = unit.endsWith("s");
        if ((value == 1) == plural) {
            throw new IllegalArgumentException(
                    "Rate unit must be singular for 1 and plural for values greater than 1: " + expression);
        }
    }

    public static boolean isRateExpression(String expression) {
        return expression != null && RATE_PATTERN.matcher(expression.trim()).matches();
    }

    public static boolean isCronExpression(String expression) {
        return expression != null && CRON_PATTERN.matcher(expression.trim()).matches();
    }

    public static long parseRateToMillis(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Schedule expression cannot be null or blank");
        }

        Matcher rateMatcher = RATE_PATTERN.matcher(expression.trim());
        if (!rateMatcher.matches()) {
            throw new IllegalArgumentException("Not a valid rate expression: " + expression);
        }

        int value = Integer.parseInt(rateMatcher.group(1));
        if (value < 1) {
            throw new IllegalArgumentException("Rate value must be >= 1, got: " + value);
        }
        String unit = rateMatcher.group(2).toLowerCase();

        return switch (unit) {
            case "minute", "minutes" -> value * 60 * 1000L;
            case "hour", "hours" -> value * 60 * 60 * 1000L;
            case "day", "days" -> value * 24 * 60 * 60 * 1000L;
            case "week", "weeks" -> value * 7 * 24 * 60 * 60 * 1000L;
            default -> throw new IllegalArgumentException("Unknown rate unit: " + unit);
        };
    }

    public static ZonedDateTime getNextFireTime(String expression, ZonedDateTime from) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Schedule expression cannot be null or blank");
        }

        Matcher cronMatcher = CRON_PATTERN.matcher(expression.trim());
        if (!cronMatcher.matches()) {
            throw new IllegalArgumentException("Expected cron expression but got: " + expression);
        }

        String cronExpression = cronMatcher.group(1);
        String normalized = normalizeCronExpression(cronExpression);
        Cron cron = CRON_PARSER.parse(normalized);
        cron.validate();

        ExecutionTime executionTime = ExecutionTime.forCron(cron);
        return executionTime.nextExecution(from).orElse(null);
    }

    public static long millisUntilNextFire(String expression, ZonedDateTime from) {
        ZonedDateTime next = getNextFireTime(expression, from);
        if (next == null) {
            throw new IllegalStateException("No next fire time found for cron expression: " + expression);
        }
        long millis = ChronoUnit.MILLIS.between(from, next);
        return Math.max(millis, 1000);
    }

    private static String normalizeCronExpression(String cronExpression) {
        String[] fields = cronExpression.trim().split("\\s+");
        if (fields.length != 6) {
            throw new IllegalArgumentException(
                    "AWS EventBridge cron expressions require 6 fields (minute hour day-of-month month day-of-week year), got " + fields.length + ": " + cronExpression);
        }
        return "0 " + cronExpression;
    }
}
