package io.floci.aws.services.lambda.launcher;

/**
 * Thrown when an image function's working directory refuses the Lambda default user,
 * so init fails the way AWS reports it: {@code Runtime.InvalidEntrypoint}.
 */
public final class WorkdirNotAccessibleException extends IllegalStateException {

    private final String blockedDirectory;

    public WorkdirNotAccessibleException(String functionName, String blockedDirectory, int mode) {
        super("Lambda default user cannot enter working directory '" + blockedDirectory
                + "' (mode " + String.format("%04o", mode) + ") for function '" + functionName + "'. "
                + "AWS runs image functions as its own default non-root user, so init fails on AWS "
                + "with Runtime.InvalidEntrypoint when that user cannot enter the working directory.");
        this.blockedDirectory = blockedDirectory;
    }

    public String blockedDirectory() {
        return blockedDirectory;
    }
}
