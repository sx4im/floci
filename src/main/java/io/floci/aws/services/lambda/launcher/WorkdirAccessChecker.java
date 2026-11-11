package io.floci.aws.services.lambda.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Checks whether AWS Lambda's default non-root user could enter an image function's
 * working directory, before the container starts.
 *
 * <p>AWS runs a container image as its own default Linux user with least privilege, not
 * as the image's {@code USER}. A working directory the image's user can enter may still
 * refuse that user, for example a distroless nonroot image whose {@code /home/nonroot}
 * is mode 0700. On AWS init then fails with {@code Runtime.InvalidEntrypoint}, while
 * Floci used to start the function and only fail after deploy.
 *
 * <p>The check is deliberately independent of any particular uid: AWS's default user is
 * not the image's user and not root, so a directory is enterable by that user only when
 * it carries the other-execute bit (or the user owns it, which the check cannot know).
 * Requiring other-execute on every directory on the path is the conservative equivalent:
 * a directory with only owner or group execute still refuses a user outside its owner
 * and group, which is exactly the AWS default user.
 *
 * <p>Directory modes come from the caller through {@link DirEntryProvider}, normally the
 * Docker archive API (see {@code ContainerLauncher}), so the check works on minimal
 * images without shell utilities. A path the provider cannot see is treated as
 * enterable: Docker creates a missing working directory at container creation.
 */
public final class WorkdirAccessChecker {

    private WorkdirAccessChecker() {
    }

    /** Execute/search permission for users other than the owner and group. */
    private static final int OTHER_EXECUTE_BIT = 1;

    /** Symlink hops followed before a path is treated as unresolvable. */
    public static final int MAX_SYMLINK_HOPS = 8;

    /** One directory entry as read from the image. */
    public record DirEntry(int mode, int uid, int gid, boolean directory, boolean symlink,
                           String linkTarget) {
    }

    /** A directory the Lambda default user cannot enter, with its mode for diagnostics. */
    public record BlockedDir(String path, int mode) {
    }

    /** Reads one directory entry for an absolute path inside the image; empty when absent. */
    public interface DirEntryProvider {
        Optional<DirEntry> stat(String absolutePath);
    }

    /**
     * Returns the first directory on the path to {@code workdir} that the Lambda default
     * user cannot enter, or empty when every directory is enterable. A null or blank
     * workdir means Docker's default of {@code /}, which is always enterable. Symlinks on
     * the path are followed; a loop, or a component that is a file rather than a directory,
     * counts as blocked.
     */
    public static Optional<BlockedDir> firstBlockedDirectory(String workdir, DirEntryProvider provider) {
        if (workdir == null || workdir.isBlank()) {
            return Optional.empty();
        }
        List<String> components = componentsOf(normalize(workdir));
        int hops = 0;
        int index = 0;
        while (index < components.size()) {
            String prefix = prefixOf(components, index);
            Optional<DirEntry> found = provider.stat(prefix);
            if (found.isEmpty()) {
                index++;
                continue;
            }
            DirEntry entry = found.get();
            if (entry.symlink()) {
                if (++hops > MAX_SYMLINK_HOPS) {
                    return Optional.of(new BlockedDir(prefix, entry.mode()));
                }
                String target = entry.linkTarget();
                if (target == null || target.isBlank()) {
                    index++;
                    continue;
                }
                String resolved;
                if (target.startsWith("/")) {
                    resolved = target;
                } else {
                    String parent = prefix.substring(0, prefix.lastIndexOf('/'));
                    resolved = parent + "/" + target;
                }
                List<String> rebuilt = new ArrayList<>(componentsOf(normalize(resolved)));
                rebuilt.addAll(components.subList(index + 1, components.size()));
                components = rebuilt;
                index = 0;
                continue;
            }
            if (!entry.directory() || (entry.mode() & OTHER_EXECUTE_BIT) == 0) {
                return Optional.of(new BlockedDir(prefix, entry.mode()));
            }
            index++;
        }
        return Optional.empty();
    }

    private static List<String> componentsOf(String absolutePath) {
        List<String> components = new ArrayList<>();
        for (String part : absolutePath.split("/")) {
            if (!part.isEmpty()) {
                components.add(part);
            }
        }
        return components;
    }

    private static String prefixOf(List<String> components, int index) {
        return "/" + String.join("/", components.subList(0, index + 1));
    }

    /** Resolves {@code .} and {@code ..} lexically and forces a leading slash. */
    private static String normalize(String path) {
        List<String> stack = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
                continue;
            }
            stack.add(part);
        }
        return "/" + String.join("/", stack);
    }
}
