package io.floci.aws.services.lambda.launcher;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkdirAccessCheckerTest {

    private static WorkdirAccessChecker.DirEntry dir(int mode) {
        return new WorkdirAccessChecker.DirEntry(mode, 0, 0, true, false, null);
    }

    private static WorkdirAccessChecker.DirEntry file(int mode) {
        return new WorkdirAccessChecker.DirEntry(mode, 0, 0, false, false, null);
    }

    private static WorkdirAccessChecker.DirEntry link(String target) {
        return new WorkdirAccessChecker.DirEntry(0777, 0, 0, false, true, target);
    }

    private static WorkdirAccessChecker.DirEntryProvider provider(
            Map<String, WorkdirAccessChecker.DirEntry> entries) {
        return path -> Optional.ofNullable(entries.get(path));
    }

    @Test
    void nullWorkdirPasses() {
        assertEquals(Optional.empty(),
                WorkdirAccessChecker.firstBlockedDirectory(null, provider(Map.of())));
    }

    @Test
    void blankWorkdirPasses() {
        assertEquals(Optional.empty(),
                WorkdirAccessChecker.firstBlockedDirectory("  ", provider(Map.of())));
    }

    @Test
    void rootWorkdirPasses() {
        assertEquals(Optional.empty(),
                WorkdirAccessChecker.firstBlockedDirectory("/", provider(Map.of())));
    }

    @Test
    void worldExecutableWorkdirPasses() {
        Map<String, WorkdirAccessChecker.DirEntry> entries = Map.of(
                "/var", dir(0755),
                "/var/task", dir(0755));
        assertEquals(Optional.empty(),
                WorkdirAccessChecker.firstBlockedDirectory("/var/task", provider(entries)));
    }

    @Test
    void ownerOnlyWorkdirIsBlocked() {
        Map<String, WorkdirAccessChecker.DirEntry> entries = Map.of(
                "/home", dir(0755),
                "/home/nonroot", dir(0700));
        assertEquals(Optional.of(new WorkdirAccessChecker.BlockedDir("/home/nonroot", 0700)),
                WorkdirAccessChecker.firstBlockedDirectory("/home/nonroot", provider(entries)));
    }

    @Test
    void ownerOnlyAncestorIsBlocked() {
        Map<String, WorkdirAccessChecker.DirEntry> entries = Map.of(
                "/home", dir(0700),
                "/home/nonroot", dir(0755));
        assertEquals(Optional.of(new WorkdirAccessChecker.BlockedDir("/home", 0700)),
                WorkdirAccessChecker.firstBlockedDirectory("/home/nonroot", provider(entries)));
    }

    @Test
    void groupOnlyWorkdirIsBlocked() {
        Map<String, WorkdirAccessChecker.DirEntry> entries = Map.of("/app", dir(0750));
        assertEquals(Optional.of(new WorkdirAccessChecker.BlockedDir("/app", 0750)),
                WorkdirAccessChecker.firstBlockedDirectory("/app", provider(entries)));
    }

    @Test
    void otherExecuteOnlyWorkdirPasses() {
        Map<String, WorkdirAccessChecker.DirEntry> entries = Map.of("/app", dir(0711));
        assertEquals(Optional.empty(),
                WorkdirAccessChecker.firstBlockedDirectory("/app", provider(entries)));
    }

    @Test
    void missingWorkdirIsNotBlocked() {
        Map<String, WorkdirAccessChecker.DirEntry> entries = Map.of("/home", dir(0755));
        assertEquals(Optional.empty(),
                WorkdirAccessChecker.firstBlockedDirectory("/home/nonroot", provider(entries)));
    }

    @Test
    void missingAncestorIsNotBlocked() {
        assertEquals(Optional.empty(),
                WorkdirAccessChecker.firstBlockedDirectory("/home/nonroot", provider(Map.of())));
    }

    @Test
    void workdirThatIsAFileIsBlocked() {
        Map<String, WorkdirAccessChecker.DirEntry> entries = Map.of("/app", file(0755));
        assertEquals(Optional.of(new WorkdirAccessChecker.BlockedDir("/app", 0755)),
                WorkdirAccessChecker.firstBlockedDirectory("/app", provider(entries)));
    }

    @Test
    void symlinkToEnterableTargetPasses() {
        Map<String, WorkdirAccessChecker.DirEntry> entries = Map.of(
                "/home", link("/mnt/home"),
                "/mnt/home", dir(0755),
                "/mnt/home/nonroot", dir(0755));
        assertEquals(Optional.empty(),
                WorkdirAccessChecker.firstBlockedDirectory("/home/nonroot", provider(entries)));
    }

    @Test
    void symlinkToBlockedTargetIsBlocked() {
        Map<String, WorkdirAccessChecker.DirEntry> entries = Map.of(
                "/home", link("/mnt/home"),
                "/mnt/home", dir(0755),
                "/mnt/home/nonroot", dir(0700));
        assertEquals(Optional.of(new WorkdirAccessChecker.BlockedDir("/mnt/home/nonroot", 0700)),
                WorkdirAccessChecker.firstBlockedDirectory("/home/nonroot", provider(entries)));
    }

    @Test
    void relativeSymlinkResolvesAgainstParent() {
        Map<String, WorkdirAccessChecker.DirEntry> entries = Map.of(
                "/home", dir(0755),
                "/home/link", link("real"),
                "/home/real", dir(0700));
        assertEquals(Optional.of(new WorkdirAccessChecker.BlockedDir("/home/real", 0700)),
                WorkdirAccessChecker.firstBlockedDirectory("/home/link", provider(entries)));
    }

    @Test
    void symlinkLoopIsBlocked() {
        Map<String, WorkdirAccessChecker.DirEntry> entries = Map.of(
                "/a", link("/b"),
                "/b", link("/a"));
        assertTrue(WorkdirAccessChecker.firstBlockedDirectory("/a/x", provider(entries)).isPresent());
    }

    @Test
    void trailingSlashAndDotSegmentsAreNormalized() {
        Map<String, WorkdirAccessChecker.DirEntry> entries = Map.of(
                "/home", dir(0755),
                "/home/nonroot", dir(0700));
        assertEquals(Optional.of(new WorkdirAccessChecker.BlockedDir("/home/nonroot", 0700)),
                WorkdirAccessChecker.firstBlockedDirectory("/home/./nonroot/", provider(entries)));
        assertEquals(Optional.of(new WorkdirAccessChecker.BlockedDir("/home/nonroot", 0700)),
                WorkdirAccessChecker.firstBlockedDirectory("/home/user/../nonroot", provider(entries)));
    }

    @Test
    void relativeWorkdirResolvesAgainstRoot() {
        Map<String, WorkdirAccessChecker.DirEntry> entries = Map.of("/app", dir(0700));
        assertEquals(Optional.of(new WorkdirAccessChecker.BlockedDir("/app", 0700)),
                WorkdirAccessChecker.firstBlockedDirectory("app", provider(entries)));
    }
}
