package io.floci.aws.services.lambda.launcher;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a Lambda image function's working directory is enterable by a
 * least-privilege user, the way AWS runs it.
 *
 * <p>AWS does not run a container image as the image's {@code USER}; it uses its own
 * default Linux user with least privilege. So an image whose working directory is
 * only enterable by the image user (for example {@code /home/nonroot} at mode
 * {@code 0700} owned by uid 65532) fails init on AWS with
 * {@code Runtime.InvalidEntrypoint}, while an emulator that keeps the image
 * {@code USER} runs it fine and the failure only shows up after deploy.
 *
 * <p>The check is deliberately independent of any one uid: AWS's default user is
 * neither the image user nor, in general, a member of the image user's group, so a
 * working directory is enterable by it exactly when every component of the path
 * grants execute permission to others. That is the condition this class tests,
 * which keeps it correct without hardcoding whatever uid AWS happens to use.
 * Symbolic links are followed to the directory they point at; a component that
 * cannot be read makes the check inconclusive (null) rather than failing the
 * launch, since a missing or unreadable component is Docker's to report at start.
 */
public final class LambdaImageWorkdirValidator {

    private static final int MAX_SYMLINK_HOPS = 8;

    /** One directory entry as the Docker archive API reports it. */
    public record DirEntry(int mode, boolean symbolicLink, String linkTarget) {
    }

    /** Resolves the entry for one absolute path; null when the path cannot be read. */
    public interface EntryLookup {
        DirEntry lookup(String absolutePath);
    }

    private LambdaImageWorkdirValidator() {
    }

    /**
     * Returns the first component of {@code workdir} that a least-privilege non-root
     * user cannot enter (its mode lacks the other-execute bit), or null when every
     * component is enterable, the workdir is blank or root, or the check is
     * inconclusive because a component could not be read or a link loop was found.
     */
    public static String firstBlockedComponent(String workdir, EntryLookup lookup) {
        if (workdir == null || workdir.isBlank()) {
            return null;
        }
        String current = normalizeAbsolute(workdir);
        Set<String> seenTargets = new HashSet<>();
        for (int hop = 0; hop <= MAX_SYMLINK_HOPS; hop++) {
            List<String> prefixes = pathPrefixes(current);
            boolean restarted = false;
            for (int i = 0; i < prefixes.size(); i++) {
                String prefix = prefixes.get(i);
                DirEntry entry = lookup.lookup(prefix);
                if (entry == null) {
                    return null;
                }
                if (entry.symbolicLink()) {
                    String target = resolveLink(prefix, entry.linkTarget());
                    if (target == null || !seenTargets.add(target)) {
                        return null;
                    }
                    String remainder = current.substring(prefix.length());
                    current = normalizeAbsolute(target + remainder);
                    restarted = true;
                    break;
                }
                if ((entry.mode() & 0001) == 0) {
                    return prefix;
                }
            }
            if (!restarted) {
                return null;
            }
        }
        return null;
    }

    /** The ancestor prefixes of an absolute workdir, root excluded. */
    static List<String> pathPrefixes(String workdir) {
        List<String> prefixes = new ArrayList<>();
        String normalized = normalizeAbsolute(workdir);
        String[] parts = normalized.split("/");
        StringBuilder prefix = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            prefix.append('/').append(part);
            prefixes.add(prefix.toString());
        }
        return prefixes;
    }

    /**
     * Normalizes a workdir to an absolute path: collapses repeated slashes, resolves
     * {@code .} and {@code ..} lexically, strips a trailing slash. A relative workdir
     * is read against the root.
     */
    static String normalizeAbsolute(String workdir) {
        String[] parts = workdir.split("/");
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!kept.isEmpty()) {
                    kept.remove(kept.size() - 1);
                }
                continue;
            }
            kept.add(part);
        }
        if (kept.isEmpty()) {
            return "/";
        }
        StringBuilder normalized = new StringBuilder();
        for (String part : kept) {
            normalized.append('/').append(part);
        }
        return normalized.toString();
    }

    /** Resolves a symlink target against the link's parent directory; null if unusable. */
    private static String resolveLink(String linkPath, String linkTarget) {
        if (linkTarget == null || linkTarget.isBlank()) {
            return null;
        }
        String target = linkTarget.trim();
        if (!target.startsWith("/")) {
            int slash = linkPath.lastIndexOf('/');
            String parent = slash <= 0 ? "/" : linkPath.substring(0, slash);
            target = parent + "/" + target;
        }
        return normalizeAbsolute(target);
    }
}
