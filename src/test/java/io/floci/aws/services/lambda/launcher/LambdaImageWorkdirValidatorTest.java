package io.floci.aws.services.lambda.launcher;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LambdaImageWorkdirValidatorTest {

    @Test
    void rootWorkdirHasNothingToCheck() {
        assertNull(LambdaImageWorkdirValidator.firstBlockedComponent("/", modes(Map.of())));
        assertNull(LambdaImageWorkdirValidator.firstBlockedComponent("", modes(Map.of())));
        assertNull(LambdaImageWorkdirValidator.firstBlockedComponent(null, modes(Map.of())));
    }

    @Test
    void traversableWorkdirPasses() {
        Map<String, Integer> modes = Map.of(
                "/home", 0755,
                "/home/nonroot", 0755);

        assertNull(LambdaImageWorkdirValidator.firstBlockedComponent("/home/nonroot", modes(modes)));
    }

    @Test
    void leafWithoutOtherExecuteIsBlocked() {
        Map<String, Integer> modes = Map.of(
                "/home", 0755,
                "/home/nonroot", 0700);

        assertEquals("/home/nonroot",
                LambdaImageWorkdirValidator.firstBlockedComponent("/home/nonroot", modes(modes)));
    }

    @Test
    void parentWithoutOtherExecuteIsBlockedFirst() {
        Map<String, Integer> modes = Map.of(
                "/a", 0700,
                "/a/b", 0755,
                "/a/b/c", 0755);

        assertEquals("/a",
                LambdaImageWorkdirValidator.firstBlockedComponent("/a/b/c", modes(modes)));
    }

    @Test
    void executeOnlyLeafPasses() {
        Map<String, Integer> modes = Map.of(
                "/home", 0711,
                "/home/nonroot", 0711);

        assertNull(LambdaImageWorkdirValidator.firstBlockedComponent("/home/nonroot", modes(modes)));
    }

    @Test
    void unreadableComponentSkipsTheCheck() {
        Map<String, Integer> modes = Map.of("/home", 0755);

        assertNull(LambdaImageWorkdirValidator.firstBlockedComponent("/home/nonroot", modes(modes)));
    }

    @Test
    void trailingSlashAndRepeatedSlashesAreIgnored() {
        Map<String, Integer> modes = Map.of(
                "/home", 0755,
                "/home/nonroot", 0700);

        assertEquals("/home/nonroot",
                LambdaImageWorkdirValidator.firstBlockedComponent("//home//nonroot/", modes(modes)));
    }

    @Test
    void symlinkToBlockedDirectoryIsBlocked() {
        Map<String, Integer> modes = Map.of(
                "/home", 0755,
                "/data", 0700,
                "/data/app", 0755);

        LambdaImageWorkdirValidator.EntryLookup lookup = path -> {
            if ("/home/nonroot".equals(path)) {
                return new LambdaImageWorkdirValidator.DirEntry(0777, true, "/data/app");
            }
            Integer mode = modes.get(path);
            return mode == null ? null
                    : new LambdaImageWorkdirValidator.DirEntry(mode, false, null);
        };

        assertEquals("/data",
                LambdaImageWorkdirValidator.firstBlockedComponent("/home/nonroot", lookup));
    }

    @Test
    void symlinkLoopSkipsTheCheck() {
        LambdaImageWorkdirValidator.EntryLookup lookup = path -> {
            if ("/a".equals(path)) {
                return new LambdaImageWorkdirValidator.DirEntry(0777, true, "/b");
            }
            if ("/b".equals(path)) {
                return new LambdaImageWorkdirValidator.DirEntry(0777, true, "/a");
            }
            return null;
        };

        assertNull(LambdaImageWorkdirValidator.firstBlockedComponent("/a", lookup));
    }

    @Test
    void pathPrefixesListsAncestors() {
        assertEquals(List.of(), LambdaImageWorkdirValidator.pathPrefixes("/"));
        assertEquals(List.of("/a"), LambdaImageWorkdirValidator.pathPrefixes("/a"));
        assertEquals(List.of("/a", "/a/b"), LambdaImageWorkdirValidator.pathPrefixes("/a/b/"));
        assertEquals(List.of("/a", "/a/b"), LambdaImageWorkdirValidator.pathPrefixes("//a//b"));
    }

    private static LambdaImageWorkdirValidator.EntryLookup modes(Map<String, Integer> modes) {
        return path -> {
            Integer mode = modes.get(path);
            return mode == null ? null
                    : new LambdaImageWorkdirValidator.DirEntry(mode, false, null);
        };
    }
}
