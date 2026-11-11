package io.floci.aws.services.lambda.launcher;

/**
 * A Lambda init failure that must surface on Invoke with a specific AWS error type
 * (for example {@code Runtime.InvalidEntrypoint}) instead of the generic
 * {@code Lambda.InitError} every other launch failure reports.
 */
public class LambdaInitException extends RuntimeException {

    private final String errorType;

    public LambdaInitException(String errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public String errorType() {
        return errorType;
    }
}
