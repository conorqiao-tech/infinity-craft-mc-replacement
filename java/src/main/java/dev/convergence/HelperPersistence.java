package dev.convergence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Helper ownership files. A replacement writes and fsyncs a temporary file,
 * then renames it atomically. Durability is PROVEN only when the directory is
 * also synced; where directory sync is unavailable the replacement still
 * happens but stays UNPROVEN, and callers must not let it authorize deletion.
 * A failure reports whether the target may have changed.
 */
final class HelperPersistence {
    /** Filesystem steps. Production uses NATIVE; tests may fail one exact real step. */
    interface Io {
        void copy(Path from, Path to) throws IOException;
        void writeDurably(Path path, byte[] bytes) throws IOException;
        void atomicMove(Path from, Path to) throws IOException;
        void delete(Path path) throws IOException;
        void syncFile(Path path) throws IOException;
        void syncDirectory(Path directory) throws IOException;
    }
    static final Io NATIVE = new Io() {
        @Override public void copy(Path from, Path to) throws IOException { Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING); }
        @Override public void writeDurably(Path path, byte[] bytes) throws IOException {
            try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
        }
        // No copy-and-delete fallback: a non-atomic move can leave a partial target.
        @Override public void atomicMove(Path from, Path to) throws IOException {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        @Override public void delete(Path path) throws IOException { Files.delete(path); }
        @Override public void syncFile(Path path) throws IOException {
            try (var channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
        }
        // Supported on Linux and macOS; Windows refuses to open a directory this way.
        @Override public void syncDirectory(Path directory) throws IOException {
            try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        }
    };

    enum Durability { PROVEN, UNPROVEN }
    /** {@code mayHaveChanged} is false only when the target path was never touched. */
    static final class Failure extends IOException {
        final boolean mayHaveChanged;
        Failure(String message, boolean mayHaveChanged, Throwable cause) { super(message, cause); this.mayHaveChanged = mayHaveChanged; }
    }

    final Io io;
    HelperPersistence(Io io) { this.io = io; }

    static Path previousFile(Path target) { return target.resolveSibling(target.getFileName() + ".previous"); }
    static Path temporaryFile(Path target) { return target.resolveSibling(target.getFileName() + ".tmp"); }
    /**
     * Present while a write that adds removal proof is in flight, and after any
     * failed or unproven ownership save. Never overwritten; removed only after
     * a guarded roster write is proven durable.
     */
    static Path recoveryMarker(Path roster) { return roster.resolveSibling(roster.getFileName() + ".recovery-required"); }
    static Path recoveryTemporary(Path roster) { return temporaryFile(recoveryMarker(roster)); }

    /** With {@code requireProof}, storage that cannot sync its directory refuses before the rename. */
    Durability replace(Path target, byte[] bytes, boolean keepPrevious, boolean requireProof) throws Failure {
        Path directory = target.toAbsolutePath().getParent();
        Path temporary = temporaryFile(target);
        try {
            Files.createDirectories(directory);
            if (keepPrevious && Files.exists(target)) io.copy(target, previousFile(target));
            io.writeDurably(temporary, bytes);
        } catch (IOException | RuntimeException e) {
            throw new Failure("Could not prepare " + target.getFileName() + "; it was not touched", false, e);
        }
        // Probe before touching the target, so an unsupported directory sync
        // is known before the rename rather than mistaken for a failed one.
        boolean provable;
        try { io.syncDirectory(directory); provable = true; }
        catch (IOException | RuntimeException e) {
            if (requireProof) throw new Failure("Directory sync is unavailable for " + target.getFileName() + "; it was not touched", false, e);
            provable = false;
        }
        try { io.atomicMove(temporary, target); }
        catch (AtomicMoveNotSupportedException e) {
            throw new Failure("Atomic rename is unavailable for " + target.getFileName() + "; it was not touched", false, e);
        } catch (IOException | RuntimeException e) {
            throw new Failure("Could not prove whether " + target.getFileName() + " was replaced", true, e);
        }
        if (!provable) return Durability.UNPROVEN;
        try { io.syncDirectory(directory); }
        catch (IOException | RuntimeException e) {
            throw new Failure(target.getFileName() + " was renamed but its directory could not be synced", true, e);
        }
        return Durability.PROVEN;
    }

    /** Makes an already present roster durable as read, or reports that it cannot. */
    boolean proveExisting(Path target) {
        try {
            if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) return true;
            io.syncFile(target); io.syncDirectory(target.toAbsolutePath().getParent());
            return true;
        } catch (IOException | RuntimeException e) { return false; }
    }

    /**
     * Why a roster that is not provably present must not be read as a fresh,
     * empty world, or null when it is present or nothing shows a prior roster.
     * Existence that cannot be determined counts as possibly present. Only
     * reads paths: never restores, renames or deletes anything.
     */
    static String absentRosterProblem(Path roster) {
        if (Files.exists(roster, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.notExists(roster, LinkOption.NOFOLLOW_LINKS))
            return "whether " + roster.getFileName() + " exists cannot be determined";
        for (Path evidence : new Path[]{previousFile(roster), recoveryMarker(roster), recoveryTemporary(roster), temporaryFile(roster)})
            if (!Files.notExists(evidence, LinkOption.NOFOLLOW_LINKS))
                return roster.getFileName() + " is missing but " + evidence.getFileName() + " may exist";
        return null;
    }

    /** An unfinished marker write is evidence too. Never overwrite or ignore it. */
    static boolean recoveryRequired(Path roster) {
        return !Files.notExists(recoveryMarker(roster), LinkOption.NOFOLLOW_LINKS)
            || !Files.notExists(recoveryTemporary(roster), LinkOption.NOFOLLOW_LINKS);
    }

    /** Best-effort note after a failure; never overwrites an existing marker. */
    void markRecoveryRequired(Path roster, String reason) throws Failure {
        if (recoveryRequired(roster)) return;
        replace(recoveryMarker(roster), (reason + "\n").getBytes(StandardCharsets.UTF_8), false, false);
    }

    /**
     * Establishes a provably durable marker before a write that adds removal
     * proof. A clean failure ({@code mayHaveChanged == false}) left no marker;
     * any other failure may have left one. An existing marker is never reused.
     */
    void beginGuard(Path roster, String reason) throws Failure {
        if (recoveryRequired(roster)) throw new Failure("Recovery evidence already exists beside " + roster, true, null);
        replace(recoveryMarker(roster), (reason + "\n").getBytes(StandardCharsets.UTF_8), false, true);
    }

    /**
     * Retires the marker; callers invoke this only after the guarded roster
     * write was proven durable. If the deletion or its sync fails, the marker
     * either remains (a restart stays frozen) or is gone after a proven roster.
     */
    void endGuard(Path roster) throws Failure {
        Path marker = recoveryMarker(roster), directory = marker.toAbsolutePath().getParent();
        try { io.delete(marker); io.syncDirectory(directory); }
        catch (IOException | RuntimeException e) { throw new Failure("Could not prove " + marker.getFileName() + " was removed", true, e); }
    }
}
