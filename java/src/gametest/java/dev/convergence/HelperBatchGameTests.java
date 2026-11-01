package dev.convergence;

import dev.convergence.mixin.AgentGoalAccess;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Native creation transactions only; every case owns an isolated roster and real entity set. */
public class HelperBatchGameTests {
    private record InsertionFailure(AgentCompanions helpers, UUID owner, Path blocked, AtomicReference<UUID> inserted) {}
    private static final AtomicReference<InsertionFailure> INSERTION_FAILURE = new AtomicReference<>();
    private record InsertionObserver(AgentCompanions helpers, List<String> seen) {}
    private static final AtomicReference<InsertionObserver> INSERTION_OBSERVER = new AtomicReference<>();
    private record GuardFailure(AgentCompanions helpers, UUID owner, FaultyIo io, AtomicReference<UUID> inserted) {}
    private static final AtomicReference<GuardFailure> GUARD_FAILURE = new AtomicReference<>();
    private record CompetingNote(AgentCompanions helpers, UUID owner, AtomicReference<UUID> inserted) {}
    private static final AtomicReference<CompetingNote> COMPETING_NOTE = new AtomicReference<>();
    static {
        // Test-only, one-shot listener: unrelated worlds, owners and entities
        // never enter this fault. No production API or hook is introduced.
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            var fault = INSERTION_FAILURE.get();
            if (fault == null || fault.helpers.server != world.getServer() || !AgentCompanions.isAgent(entity)) return;
            var record = fault.helpers.data.agents.get(entity.getStringUUID());
            if (record == null || !record.owner().equals(fault.owner.toString()) || !record.name().startsWith("helper-")) return;
            if (!INSERTION_FAILURE.compareAndSet(fault, null)) return;
            fault.inserted.set(entity.getUUID());
            try { Files.createDirectory(fault.blocked); Files.writeString(fault.blocked.resolve("block-rollback"), "after ownership save"); }
            catch (java.io.IOException failure) { throw new IllegalStateException("Could not establish the private rollback fault", failure); }
            throw new IllegalStateException("Exact-owner one-shot native entity insertion fault");
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            var fault = GUARD_FAILURE.get();
            if (fault == null || fault.helpers.server != world.getServer() || !AgentCompanions.isAgent(entity)) return;
            var record = fault.helpers.data.agents.get(entity.getStringUUID());
            if (record == null || !record.owner().equals(fault.owner.toString()) || !record.name().equals("guarded")) return;
            if (!GUARD_FAILURE.compareAndSet(fault, null)) return;
            fault.inserted.set(entity.getUUID());
            fault.io.blockMarkerWrites = true;
            fault.io.blockRosterWrites = true;
            throw new IllegalStateException("Exact-owner partial insertion with subsequent storage writes refused");
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            var fault = COMPETING_NOTE.get();
            if (fault == null || fault.helpers.server != world.getServer() || !AgentCompanions.isAgent(entity)) return;
            var record = fault.helpers.data.agents.get(entity.getStringUUID());
            if (record == null || !record.owner().equals(fault.owner.toString()) || !record.name().equals("contested")) return;
            if (!COMPETING_NOTE.compareAndSet(fault, null)) return;
            fault.inserted.set(entity.getUUID());
            try { Files.writeString(HelperPersistence.recoveryTemporary(fault.helpers.file), "competing evidence"); }
            catch (IOException failure) { throw new IllegalStateException("Could not create test-only competing evidence", failure); }
        });
        // Test-only observer: what the disk proves at the moment each new
        // native helper enters the world.
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            var observer = INSERTION_OBSERVER.get();
            if (observer == null || observer.helpers.server != world.getServer() || !AgentCompanions.isAgent(entity)
                || !observer.helpers.data.agents.containsKey(entity.getStringUUID())) return;
            var disk = AgentCompanions.read(observer.helpers.file);
            boolean owned = disk.agents.containsKey(entity.getStringUUID()) && disk.pendingCreations.contains(entity.getStringUUID());
            observer.seen.add(owned && HelperPersistence.guardIntact(observer.helpers.file) ? "owned-pending-guarded" : "missing-or-unguarded");
        });
    }

    /**
     * Real filesystem I/O; an armed step fails once, either before or after its
     * real effect. {@code noDirectorySync} models storage, such as Windows, whose
     * directories cannot be synced: that step always fails without effect.
     */
    private static final class FaultyIo implements HelperPersistence.Io {
        private record Fault(boolean afterEffect, int skip) {}
        private final Map<String, Fault> armed = new HashMap<>();
        private String last = "";
        boolean noDirectorySync;
        boolean blockMarkerWrites, blockRosterWrites, replaceGuardAfterRosterSync;
        private interface Step { void run() throws IOException; }
        void arm(String step, boolean afterEffect) { armLater(step, afterEffect, 0); }
        /** Fails the step's occurrence after {@code skip} successful ones. */
        void armLater(String step, boolean afterEffect, int skip) { armed.put(step, new Fault(afterEffect, skip)); }
        private void step(String name, boolean sync, Step real) throws IOException {
            var fault = armed.get(name);
            if (fault != null && fault.skip > 0) { armed.put(name, new Fault(fault.afterEffect, fault.skip - 1)); fault = null; }
            else if (fault != null) armed.remove(name);
            Boolean after = fault == null ? null : fault.afterEffect;
            if (Boolean.FALSE.equals(after)) throw new IOException("Injected failure before " + name);
            real.run();
            // A sync names only the step it follows; a later probe then reads "sync-after:sync".
            last = sync ? "sync" : name;
            if (after != null) throw new IOException("Injected failure after " + name);
        }
        @Override public void copy(Path from, Path to) throws IOException { step("copy:" + to.getFileName(), false, () -> HelperPersistence.NATIVE.copy(from, to)); }
        @Override public void writeDurably(Path path, byte[] bytes) throws IOException {
            String name = path.getFileName().toString();
            if ((blockMarkerWrites && name.equals("agents.json.recovery-required.tmp"))
                || (blockRosterWrites && name.equals("agents.json.tmp"))) throw new IOException("Injected continuing write refusal");
            step("write:" + name, false, () -> HelperPersistence.NATIVE.writeDurably(path, bytes));
        }
        @Override public void atomicMove(Path from, Path to) throws IOException { step("move:" + to.getFileName(), false, () -> HelperPersistence.NATIVE.atomicMove(from, to)); }
        @Override public void delete(Path path) throws IOException { step("delete:" + path.getFileName(), false, () -> HelperPersistence.NATIVE.delete(path)); }
        @Override public void syncFile(Path path) throws IOException { step("fsync:" + path.getFileName(), true, () -> HelperPersistence.NATIVE.syncFile(path)); }
        @Override public void syncDirectory(Path directory) throws IOException {
            if (noDirectorySync) throw new IOException("Directory sync is unsupported on this storage");
            String previous = last;
            step("sync-after:" + last, true, () -> HelperPersistence.NATIVE.syncDirectory(directory));
            if (replaceGuardAfterRosterSync && previous.equals("move:agents.json")) {
                replaceGuardAfterRosterSync = false;
                Files.delete(directory.resolve("agents.json.recovery-required"));
                Files.writeString(directory.resolve("agents.json.recovery-required.tmp"), "competing evidence");
            }
        }
    }
    /** System chat text actually encoded for the player's native connection since the last drain. */
    private static List<String> chat(NativePlayConnection wire) {
        var messages = new ArrayList<String>();
        for (var packet : wire.drain()) {
            if (packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket chat) messages.add(chat.content().getString());
            else if (packet instanceof net.minecraft.network.protocol.game.ClientboundBundlePacket bundle)
                for (var inner : bundle.subPackets()) if (inner instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket chat) messages.add(chat.content().getString());
        }
        return messages;
    }
    /** An unresolved creation is reported as possibly present, never as created or as absent. */
    private static void assertUnresolvedNotice(GameTestHelper c, List<String> told, String label) {
        c.assertTrue(told.stream().anyMatch(text -> text.contains("may have appeared") && text.contains("host review")),
            "The player is told helpers may have appeared and ownership needs host review: " + label + " " + told);
        c.assertFalse(told.stream().anyMatch(text -> text.contains("Nothing spawned") || text.contains("No helper entity spawned")
            || text.contains("No helper appeared") || text.contains("appeared beside you") || text.contains("is following you")),
            "No message claims the creation succeeded or that nothing appeared: " + label + " " + told);
    }
    /** Roster-replacement faults: untouched, rename refused, rename done but reported failed, rename unsynced. */
    private static final List<Map.Entry<String, Boolean>> REPLACEMENT_FAULTS = List.of(
        Map.entry("write:agents.json.tmp", false), Map.entry("move:agents.json", false),
        Map.entry("move:agents.json", true), Map.entry("sync-after:move:agents.json", true));

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper c;
        final AgentCompanions previous, helpers;
        final ServerPlayer owner;
        final Path directory;
        final BlockPos feet;
        Fixture(GameTestHelper c) throws Exception { this(c, HelperPersistence.NATIVE); }
        Fixture(GameTestHelper c, HelperPersistence.Io io) throws Exception { this(c, io, null); }
        Fixture(GameTestHelper c, HelperPersistence.Io io, NativePlayConnection wire) throws Exception {
            this.c = c; var server = c.getLevel().getServer();
            previous = AgentCompanions.get(server);
            c.assertTrue(previous.data.agents.isEmpty(), "The exclusive batch fixture begins with no unrelated helper runtime");
            directory = Files.createTempDirectory("infinity-helper-batch-");
            helpers = new AgentCompanions(server, directory.resolve("agents.json"), java.time.Clock.systemUTC(), new HelperPersistence(io));
            AgentCompanions.INSTANCES.put(server, helpers);
            owner = wire == null ? new ModeGameTests().player(c, "batch-owner") : new ModeGameTests().player(c, "batch-owner", wire.connection);
            OperatorGameTests.level(owner, LevelBasedPermissionSet.OWNER);
            owner.closeContainer(); owner.setGameMode(GameType.SURVIVAL); owner.getInventory().clearContent();
            feet = c.absolutePos(new BlockPos(20, 55, 20));
            for (var at : BlockPos.betweenClosed(feet.offset(-17,-1,-17), feet.offset(17,3,17)))
                c.getLevel().setBlockAndUpdate(at, at.getY() == feet.getY()-1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            owner.setPos(Vec3.atBottomCenterOf(feet)); helpers.save();
        }
        void unloaded(UUID ownerId, int count, String prefix) {
            for (int i = 1; i <= count; i++) helpers.data.agents.put(UUID.randomUUID().toString(),
                new AgentCompanions.Agent(ownerId.toString(), prefix+i, AgentCompanions.Mode.STAY,
                    AgentCompanions.Profile.REGULAR, c.getLevel().dimension().identifier().toString(), feet.getX(), feet.getY(), feet.getZ()));
            helpers.save();
        }
        /** Simulates a legacy roster entry that predates creation-pending tracking. */
        void legacy(String id) {
            c.assertTrue(helpers.data.pendingCreations.remove(id), "A new test helper starts pending");
            helpers.save();
        }
        Path marker() { return HelperPersistence.recoveryMarker(helpers.file); }
        Path previousFile() { return HelperPersistence.previousFile(helpers.file); }
        /** A fresh runtime over the same files, as after a restart. Never registered as the live instance. */
        AgentCompanions restart() { return restart(HelperPersistence.NATIVE); }
        AgentCompanions restart(HelperPersistence.Io io) {
            return new AgentCompanions(helpers.server, helpers.file, java.time.Clock.systemUTC(), new HelperPersistence(io));
        }
        /** A tagged native copy with this identity, as a chunk would load it. Never inserted into the world. */
        IronGolem saved(UUID id) {
            var golem = EntityTypes.IRON_GOLEM.create(c.getLevel(), EntitySpawnReason.LOAD);
            golem.setUUID(id); golem.addTag(AgentCompanions.TAG); golem.setPos(Vec3.atBottomCenterOf(feet.offset(6, 0, 6)));
            return golem;
        }
        @Override public void close() throws Exception {
            HelperForms.ACTIVE.remove(owner.getUUID()); HelperForms.DELIVERIES.remove(owner.getUUID());
            for (var helper : new ArrayList<>(helpers.loaded.values())) { AgentAvatars.remove(helper); helper.discard(); }
            helpers.loaded.clear(); helpers.data.agents.clear();
            owner.closeContainer(); OperatorGameTests.deop(owner); c.getLevel().getServer().getPlayerList().remove(owner);
            if (AgentCompanions.INSTANCES.get(helpers.server) == helpers) AgentCompanions.INSTANCES.put(helpers.server, previous);
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void oneBatchFillsRemainingFiftyWithDistinctNativeHelpersAndKeepsExistingIdentity(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            c.assertValueEqual(f.helpers.spawn(f.owner, "my-existing"), 1, "The existing single-helper path still creates one helper");
            var old = f.helpers.owned(f.owner, "my-existing");
            var oldEntity = f.helpers.loaded.get(UUID.fromString(old.getKey()));
            oldEntity.setHealth(37); oldEntity.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.31);
            var oldPosition = oldEntity.position();
            var gift = new ItemStack(Items.DIAMOND, 7); gift.set(DataComponents.CUSTOM_NAME, Component.literal("Keep my items"));
            f.owner.getInventory().setItem(8, gift.copy());
            int proposals = AgentActions.get(f.helpers.server).data.proposals.size();
            c.assertValueEqual(f.helpers.remaining(f.owner), 49, "The existing helper consumes one saved slot");
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 49, AgentCompanions.Profile.ULTIMATE_FINALS), 49, "One call creates the entire exact requested batch");
            c.assertValueEqual(f.helpers.count(f.owner), 50, "The batch fills the owner's total to fifty, rather than adding fifty on top");
            c.assertValueEqual(f.helpers.loaded.size(), 50, "Every new roster entry has one real loaded helper");
            var ids = new HashSet<UUID>(); var names = new HashSet<String>();
            for (var entry : f.helpers.data.agents.entrySet()) {
                var entity = f.helpers.loaded.get(UUID.fromString(entry.getKey()));
                c.assertTrue(entity != null && c.getLevel().getEntity(entity.getUUID()) == entity && entity.isAlive(), "Every saved identity belongs to its actual native entity");
                c.assertTrue(ids.add(entity.getUUID()) && names.add(entry.getValue().name()), "No UUID or per-owner name is duplicated");
                if (!entry.getKey().equals(old.getKey())) {
                    c.assertValueEqual(entry.getValue().profile(), AgentCompanions.Profile.ULTIMATE_FINALS, "The selected profile is saved at creation");
                    c.assertValueEqual(entry.getValue().mode(), AgentCompanions.Mode.FOLLOW, "New helpers follow immediately");
                    c.assertTrue(entity.entityTags().contains("infinity_owner_"+f.owner.getStringUUID()), "Native tags retain exact ownership");
                }
            }
            var bodies = new ArrayList<>(f.helpers.loaded.values());
            for (int i = 0; i < bodies.size(); i++) for (int j = i+1; j < bodies.size(); j++)
                c.assertFalse(bodies.get(i).getBoundingBox().intersects(bodies.get(j).getBoundingBox()), "Preflight never places two helpers in overlapping bodies");
            c.assertTrue(f.helpers.data.agents.get(old.getKey()) == old.getValue() && f.helpers.loaded.get(oldEntity.getUUID()) == oldEntity,
                "The original saved record and loaded entity objects are unchanged");
            c.assertTrue(oldEntity.getHealth() == 37 && oldEntity.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue() == .31 && oldEntity.position().equals(oldPosition),
                "The batch preserves the old helper's health, edited attributes and position");
            c.assertTrue(ItemStack.isSameItemSameComponents(f.owner.getInventory().getItem(8), gift), "Creation never consumes or replaces player inventory");
            c.assertValueEqual(AgentActions.get(f.helpers.server).data.proposals.size(), proposals, "No AI command/player-target approval is created or supplied");
            c.assertTrue(f.helpers.playerTargets.isEmpty(), "Creating a combat squad never creates a player target");
            c.assertValueEqual(AgentCompanions.read(f.helpers.file).agents, f.helpers.data.agents, "Every created UUID/profile survives the actual roster file roundtrip");
            byte[] full = Files.readAllBytes(f.helpers.file);
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 1, AgentCompanions.Profile.REGULAR), 0, "A second activation cannot create helper fifty-one");
            c.assertTrue(Arrays.equals(full, Files.readAllBytes(f.helpers.file)) && f.helpers.loaded.size() == 50, "Capacity refusal changes neither saved roster nor native entities");
        }
        try (var f = new Fixture(c)) {
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 50, AgentCompanions.Profile.REGULAR), 50,
                "An empty owner can request the maximum fifty real helpers in a single activation");
            c.assertTrue(f.helpers.count(f.owner) == 50 && f.helpers.loaded.size() == 50 && f.helpers.remaining(f.owner) == 0,
                "The exact maximum creates fifty saved native entities and consumes all remaining capacity");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void unloadedAndOtherOwnersConsumeCapacityAndNeverGetReplacedByABatch(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            f.unloaded(f.owner.getUUID(), 17, "helper-"); f.unloaded(UUID.randomUUID(), 26, "other-");
            var original = Map.copyOf(f.helpers.data.agents); byte[] before = Files.readAllBytes(f.helpers.file);
            c.assertValueEqual(f.helpers.remaining(f.owner), 7, "All forty-three saved unloaded helpers reserve server slots, including another owner");
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 8, AgentCompanions.Profile.REGULAR), 0, "A stale requested quantity above remaining capacity is refused in full");
            c.assertTrue(Arrays.equals(before, Files.readAllBytes(f.helpers.file)) && f.helpers.loaded.isEmpty(), "Over-capacity request does not write or materialize any unloaded helper");
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 7, AgentCompanions.Profile.PRIMITIVE), 7, "A batch uses exactly the remaining server capacity");
            c.assertValueEqual(f.helpers.count(f.owner), 24, "Own unloaded entries plus the new batch form the owner's total");
            c.assertValueEqual(f.helpers.data.agents.size(), 50, "Other owners' unloaded entries still count globally");
            c.assertTrue(original.entrySet().stream().allMatch(e -> f.helpers.data.agents.get(e.getKey()) == e.getValue()), "No existing owner or other-owner record was rewritten or replaced");
            c.assertTrue(original.keySet().stream().noneMatch(id -> f.helpers.loaded.containsKey(UUID.fromString(id))), "The batch never recreates unloaded helpers");
            c.assertTrue(f.helpers.owned(f.owner, "helper-18") != null && f.helpers.owned(f.owner, "helper-24") != null, "Generated names skip saved unloaded names");
            c.assertValueEqual(f.helpers.remaining(f.owner), 0, "No owner can exceed the global cap");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void blockedOrWetPlacementRefusesTheEntireBatchWithoutPhantomOwnership(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            for (var at : BlockPos.betweenClosed(f.feet.offset(-17,0,-17), f.feet.offset(17,2,17)))
                if (at.getX() != f.feet.getX() || at.getZ() != f.feet.getZ()) c.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
            // One valid cell is insufficient for an exact request for two.
            var one = f.feet.offset(2,0,0); for (int y = 0; y < 3; y++) c.getLevel().setBlockAndUpdate(one.above(y), Blocks.AIR.defaultBlockState());
            byte[] before = Files.readAllBytes(f.helpers.file);
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 2, AgentCompanions.Profile.REGULAR), 0, "An incomplete placement plan cannot create a smaller partial squad");
            c.assertTrue(f.helpers.data.agents.isEmpty() && f.helpers.loaded.isEmpty() && Arrays.equals(before, Files.readAllBytes(f.helpers.file)), "Failed planning leaves no saved identity, native entity or disk write");
            c.getLevel().setBlockAndUpdate(one, Blocks.WATER.defaultBlockState());
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 1, AgentCompanions.Profile.REGULAR), 0, "Water is not a safe native helper placement");
            c.assertTrue(f.helpers.data.agents.isEmpty() && f.helpers.loaded.isEmpty(), "Wet placement cannot create phantom helpers");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void actualAtomicOwnershipSaveFailureSpawnsNothingAndRetryDoesNotDuplicate(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            f.helpers.spawn(f.owner, "kept"); var old = f.helpers.owned(f.owner, "kept");
            var entity = f.helpers.loaded.get(UUID.fromString(old.getKey())); entity.setHealth(29);
            var before = Map.copyOf(f.helpers.data.agents); byte[] bytes = Files.readAllBytes(f.helpers.file);
            Path blocked = f.helpers.file.resolveSibling(f.helpers.file.getFileName()+".tmp");
            Files.createDirectory(blocked); Files.writeString(blocked.resolve("block-write"), "real filesystem failure");
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 3, AgentCompanions.Profile.ULTIMATE_FINALS), 0, "The real native ownership writer fails before any entity is added");
            c.assertTrue(f.helpers.data.agents.equals(before) && f.helpers.loaded.size() == 1 && f.helpers.loaded.get(entity.getUUID()) == entity && entity.getHealth() == 29,
                "Failed save rolls back only staged identities and preserves the existing helper");
            c.assertTrue(Arrays.equals(bytes, Files.readAllBytes(f.helpers.file)), "Atomic writer failure retains the original ownership file byte for byte");
            Files.delete(blocked.resolve("block-write")); Files.delete(blocked);
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 3, AgentCompanions.Profile.ULTIMATE_FINALS), 3, "An explicit retry after storage repair creates exactly three helpers");
            c.assertValueEqual(f.helpers.count(f.owner), 4, "The failed attempt reserved no phantom duplicate names or slots");
            c.assertTrue(f.helpers.owned(f.owner, "helper-1") != null && f.helpers.owned(f.owner, "helper-3") != null && f.helpers.owned(f.owner, "helper-4") == null,
                "Retry starts from the original free names without missing or duplicate identities");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void singleSpawnOwnershipSaveFailureLeavesNoGhostForLaterSaves(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            f.unloaded(f.owner.getUUID(), 24, "own-"); f.unloaded(UUID.randomUUID(), 24, "other-");
            f.helpers.spawn(f.owner, "kept"); var kept = f.helpers.owned(f.owner, "kept");
            var entity = f.helpers.loaded.get(UUID.fromString(kept.getKey())); entity.setHealth(33);
            var before = Map.copyOf(f.helpers.data.agents); byte[] bytes = Files.readAllBytes(f.helpers.file);
            Path previous = f.helpers.file.resolveSibling(f.helpers.file.getFileName()+".previous");
            c.assertValueEqual(f.helpers.remaining(f.owner), 1, "Exactly one server slot remains before the failing create");
            Path blocked = f.helpers.file.resolveSibling(f.helpers.file.getFileName()+".tmp");
            Files.createDirectory(blocked); Files.writeString(blocked.resolve("block-write"), "real filesystem failure");
            c.assertValueEqual(f.helpers.spawn(f.owner, "retry"), 0, "The real atomic ownership writer fails and the single create reports no success");
            c.assertTrue(f.helpers.data.agents.equals(before) && f.helpers.owned(f.owner, "retry") == null, "No ghost identity remains in the in-memory roster");
            c.assertTrue(before.entrySet().stream().allMatch(e -> f.helpers.data.agents.get(e.getKey()) == e.getValue()), "Every existing record object, loaded or unloaded, is kept");
            c.assertTrue(f.helpers.loaded.size() == 1 && f.helpers.loaded.get(entity.getUUID()) == entity && entity.isAlive() && entity.getHealth() == 33,
                "No native entity is added and the existing helper is untouched");
            c.assertTrue(Arrays.equals(bytes, Files.readAllBytes(f.helpers.file)) && Arrays.equals(bytes, Files.readAllBytes(previous)),
                "The roster file is unchanged and .previous holds the last good roster, never the failed identity");
            c.assertValueEqual(f.helpers.remaining(f.owner), 1, "The failed attempt reserves no server slot");
            c.assertFalse(f.helpers.creationRecoveryRequired, "A clean synchronous failure leaves nothing on disk that needs manual recovery");
            Files.delete(blocked.resolve("block-write")); Files.delete(blocked);
            c.assertValueEqual(f.helpers.profile(f.owner, "kept", AgentCompanions.Profile.DEBUG), 1, "An unrelated roster change saves after storage repair");
            c.assertValueEqual(AgentCompanions.read(f.helpers.file).agents.keySet(), before.keySet(), "A later successful save persists only the existing identities");
            OperatorGameTests.level(f.owner, LevelBasedPermissionSet.ADMIN);
            f.helpers.spawn(f.owner, "retry");
            c.assertTrue(f.helpers.owned(f.owner, "retry") == null && f.helpers.data.agents.keySet().equals(before.keySet()), "OP3 still cannot create a helper after the failure");
            OperatorGameTests.level(f.owner, LevelBasedPermissionSet.OWNER);
            c.assertValueEqual(f.helpers.spawn(f.owner, "retry"), 1, "Retry reuses the failed name and the last server slot");
            var retry = f.helpers.owned(f.owner, "retry"); var retried = f.helpers.loaded.get(UUID.fromString(retry.getKey()));
            c.assertTrue(retried != null && c.getLevel().getEntity(retried.getUUID()) == retried, "The retried identity belongs to a real native entity");
            c.assertTrue(f.helpers.data.agents.size() == 50 && f.helpers.remaining(f.owner) == 0, "The server reaches exactly fifty without a phantom entry");
            var saved = AgentCompanions.read(f.helpers.file).agents;
            c.assertValueEqual(saved, f.helpers.data.agents, "The saved roster matches memory after the successful retry");
            c.assertTrue(saved.keySet().stream().filter(id -> !before.containsKey(id)).toList().equals(java.util.List.of(retry.getKey())),
                "The only new saved identity is the retried helper");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void failedNativeInsertionAndOwnershipRollbackLatchAllFurtherCreationUntilManualRecovery(GameTestHelper c) throws Exception {
        var wire = new NativePlayConnection(c.getLevel().registryAccess());
        try (var f = new Fixture(c, HelperPersistence.NATIVE, wire)) {
            f.helpers.spawn(f.owner, "kept-recovery"); var kept = f.helpers.owned(f.owner, "kept-recovery");
            var entity = f.helpers.loaded.get(UUID.fromString(kept.getKey())); entity.setHealth(31);
            var before = Map.copyOf(f.helpers.data.agents); byte[] bytes = Files.readAllBytes(f.helpers.file);
            var pendingForm = new AtomicReference<org.geysermc.cumulus.form.Form>(); int[] formActions = {0};
            HelperForms.show(f.owner,"Review helper profile","A pre-failure native helper screen",
                java.util.List.of(new HelperForms.Choice("Change profile",()->formActions[0]++)),()->true,
                form->{pendingForm.set(form);return true;});
            var inserted = new AtomicReference<UUID>(); Path blocked = f.helpers.file.resolveSibling(f.helpers.file.getFileName()+".tmp");
            var fault = new InsertionFailure(f.helpers, f.owner.getUUID(), blocked, inserted);
            c.assertTrue(INSERTION_FAILURE.compareAndSet(null, fault), "The private exact-owner fault is armed once");
            int created;
            wire.drain();
            try { created = f.helpers.spawnBatch(f.owner, 3, AgentCompanions.Profile.PRIMITIVE); }
            finally { INSERTION_FAILURE.compareAndSet(fault, null); }
            c.assertTrue(created == 0 && inserted.get() != null,
                "The real native entity callback failed after the ownership write, the real rollback writer then failed, and the call returned no success instead of throwing");
            assertUnresolvedNotice(c, chat(wire), "batch, rollback write blocked");
            c.assertTrue(f.helpers.creationRecoveryRequired && f.helpers.remaining(f.owner) == 0, "The double failure latches creation closed for the whole runtime");
            var staged = f.helpers.data.agents.keySet().stream().filter(id -> !before.containsKey(id)).toList();
            c.assertTrue(before.entrySet().stream().allMatch(e -> f.helpers.data.agents.get(e.getKey()) == e.getValue())
                && staged.size() == 3 && staged.contains(inserted.get().toString()),
                "A rollback whose removal is not proven keeps every staged record, as the creation save did, and every existing record object");
            c.assertTrue(f.helpers.loaded.get(entity.getUUID()) == entity && entity.getHealth() == 31, "The existing helper object and health are preserved");
            var partial = c.getLevel().getEntity(inserted.get());
            c.assertTrue(partial == null || (!partial.isRemoved() && f.helpers.loaded.get(inserted.get()) == partial),
                "A new helper that reached the world is kept registered and paused, never deleted by the unproven rollback");
            c.assertTrue(Files.exists(f.marker()) && f.restart().creationRecoveryRequired, "The failure leaves a marker, so a restart stays frozen");
            var frozenAgents = Map.copyOf(f.helpers.data.agents); int frozenLoaded = f.helpers.loaded.size();
            Path previous = f.helpers.file.resolveSibling(f.helpers.file.getFileName()+".previous");
            c.assertTrue(Arrays.equals(bytes, Files.readAllBytes(previous)), "The original recovery copy is never replaced by the failed staged batch");
            byte[] unresolved = Files.readAllBytes(f.helpers.file);
            c.assertFalse(Arrays.equals(bytes, unresolved), "The unresolved staged ownership file is retained as recovery evidence rather than hidden");
            Files.delete(blocked.resolve("block-rollback")); Files.delete(blocked);
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 1, AgentCompanions.Profile.REGULAR), 0, "Repairing storage alone does not silently clear the recovery latch");
            f.helpers.spawn(f.owner, "must-not-appear");
            c.assertTrue(f.helpers.owned(f.owner,"must-not-appear") == null && f.helpers.data.agents.equals(frozenAgents) && f.helpers.loaded.size() == frozenLoaded,
                "The original single-helper path is blocked too, without modifying existing ownership");
            c.assertTrue(Arrays.equals(unresolved, Files.readAllBytes(f.helpers.file)) && Arrays.equals(bytes, Files.readAllBytes(previous)),
                "Subsequent requests cannot overwrite either the unresolved roster or its original recovery copy");
            var pending = pendingForm.get();
            org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(pending).handleFormResponse(pending,"0");
            c.assertValueEqual(formActions[0],0,"A real encoded pre-failure touch response is consumed without applying or claiming a helper menu action");
            c.assertValueEqual(f.helpers.profile(f.owner,"kept-recovery",AgentCompanions.Profile.DEBUG),0,"Profile changes are refused before mutation while ownership is frozen");
            c.assertValueEqual(f.helpers.mode(f.owner,"kept-recovery",AgentCompanions.Mode.STAY),0,"Movement changes are refused before mutation");
            c.assertFalse(f.helpers.enableCombos(f.owner,"kept-recovery"),"Combo activation cannot rewrite a frozen record");
            c.assertValueEqual(f.helpers.squad(f.owner,AgentCompanions.Mode.STAY),0,"Squad movement cannot rewrite frozen records");
            c.assertValueEqual(f.helpers.squadProfile(f.owner,AgentCompanions.Profile.ULTIMATE_FINALS),0,"Squad profiles cannot rewrite frozen records");
            c.assertValueEqual(f.helpers.recall(f.owner,"kept-recovery"),0,"Recall cannot move or rewrite a frozen helper");
            c.assertValueEqual(f.helpers.recallSquad(f.owner),0,"The squad recall wrapper cannot claim a completed blocked move");
            c.assertValueEqual(f.helpers.dismiss(f.owner,"kept-recovery"),0,"Dismissal cannot remove a frozen ownership record or entity");
            c.assertTrue(f.helpers.data.agents.get(kept.getKey()) == kept.getValue() && entity.isAlive() && entity.getHealth() == 31,
                "Every refused existing-helper operation preserves the exact record and native entity");
            entity.setDeltaMovement(.3,0,.2); f.helpers.control(entity,kept.getValue(),f.helpers.server.getTickCount());
            c.assertTrue(entity.getDeltaMovement().lengthSqr() == 0 && entity.getTarget() == null && !entity.isUsingItem() && !entity.isFallFlying(),
                "The native controller safely pauses movement, attacks, item use and gliding during recovery");
            AgentActions.get(f.helpers.server).ceasefire(f.owner);
            c.assertTrue(f.helpers.playerTargets.isEmpty(),"Direct ceasefire remains usable without writing helper ownership");
            boolean saveRefused = false;
            try { f.helpers.save(); } catch (IllegalStateException expected) { saveRefused = true; }
            c.assertTrue(saveRefused && Arrays.equals(unresolved, Files.readAllBytes(f.helpers.file)) && Arrays.equals(bytes, Files.readAllBytes(previous)),
                "A defensive save call reports failure and cannot rotate recovery evidence even after the filesystem is writable");
            c.assertTrue(entity.hurtServer(c.getLevel(),entity.damageSources().genericKill(),10000),"An actual native lethal hit reaches the existing helper");
            c.assertFalse(entity.isAlive(),"The native helper death callback runs");
            c.assertTrue(f.helpers.data.agents.get(kept.getKey()) == kept.getValue() && !f.helpers.loaded.containsKey(entity.getUUID()),
                "Automatic death clears loaded state while retaining the frozen ownership record for manual recovery");
            c.assertTrue(Arrays.equals(unresolved, Files.readAllBytes(f.helpers.file)) && Arrays.equals(bytes, Files.readAllBytes(previous)),
                "Actual native death neither throws nor destroys the unresolved roster or its last-good recovery file");
        } finally { wire.channel.finishAndReleaseAll(); }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void batchCreationRechecksNativeOwnerPermissionsLifeAndBounds(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            byte[] before = Files.readAllBytes(f.helpers.file);
            for (int n : new int[]{-1,0,51,Integer.MAX_VALUE}) c.assertValueEqual(f.helpers.spawnBatch(f.owner,n,AgentCompanions.Profile.REGULAR),0,"Invalid quantity is refused: "+n);
            c.assertValueEqual(f.helpers.spawnBatch(f.owner,1,null),0,"An invalid profile is refused");
            OperatorGameTests.level(f.owner,LevelBasedPermissionSet.ADMIN);
            c.assertValueEqual(f.helpers.spawnBatch(f.owner,1,AgentCompanions.Profile.REGULAR),0,"OP3 cannot use batch creation");
            OperatorGameTests.level(f.owner,LevelBasedPermissionSet.OWNER);f.owner.setGameMode(GameType.SPECTATOR);
            c.assertValueEqual(f.helpers.spawnBatch(f.owner,1,AgentCompanions.Profile.REGULAR),0,"Spectators cannot create helpers");
            f.owner.setGameMode(GameType.SURVIVAL);f.owner.setHealth(0);
            c.assertValueEqual(f.helpers.spawnBatch(f.owner,1,AgentCompanions.Profile.REGULAR),0,"Dead owners cannot create helpers");
            f.owner.setHealth(20);
            c.assertTrue(f.helpers.data.agents.isEmpty()&&f.helpers.loaded.isEmpty()&&Arrays.equals(before,Files.readAllBytes(f.helpers.file)),"Denied requests never write or create entities");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void everyNewHelperIsOwnedOnDiskBeforeItEntersTheWorldAndNoJournalIsLeft(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            var observer = new InsertionObserver(f.helpers, new ArrayList<>());
            c.assertTrue(INSERTION_OBSERVER.compareAndSet(null, observer), "The private insertion observer is armed once");
            try {
                c.assertValueEqual(f.helpers.spawn(f.owner, "single"), 1, "The single-helper path still creates its helper");
                c.assertValueEqual(f.helpers.spawnBatch(f.owner, 3, AgentCompanions.Profile.PRIMITIVE), 3, "The batch path still creates its full batch");
            } finally { INSERTION_OBSERVER.compareAndSet(observer, null); }
            c.assertValueEqual(observer.seen, List.of("owned-pending-guarded", "owned-pending-guarded", "owned-pending-guarded", "owned-pending-guarded"),
                "As each helper entered the world, its saved roster owned it and the creation guard was present");
            try (var files = Files.list(f.directory)) {
                c.assertValueEqual(files.map(p -> p.getFileName().toString()).sorted().toList(), List.of("agents.json", "agents.json.previous"),
                    "Ordinary creation leaves no journal, marker or temporary file that could suggest recovery state");
            }
            c.assertFalse(f.helpers.creationRecoveryRequired || f.restart().creationRecoveryRequired, "Ordinary creation never freezes, now or after restart");
        }
        c.succeed();
    }

    /** Drives actual serialized Cumulus form responses through the registered callback. */
    private static final class Touch implements java.util.function.Predicate<org.geysermc.cumulus.form.Form> {
        final List<org.geysermc.cumulus.form.SimpleForm> forms = new ArrayList<>();
        @Override public boolean test(org.geysermc.cumulus.form.Form form) {
            String encoded = org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).codec().jsonData(form);
            if (!com.google.gson.JsonParser.parseString(encoded).getAsJsonObject().get("type").getAsString().equals("form"))
                throw new IllegalStateException("Expected a native touch form");
            forms.add((org.geysermc.cumulus.form.SimpleForm) form); return true;
        }
        org.geysermc.cumulus.form.SimpleForm last() { return forms.getLast(); }
        void click(String prefix) {
            var form = last();
            for (int i = 0; i < form.buttons().size(); i++) if (form.buttons().get(i).text().startsWith(prefix)) {
                try { org.geysermc.cumulus.form.impl.FormDefinitions.instance().definitionFor(form).handleFormResponse(form, Integer.toString(i)); return; }
                catch (Exception e) { throw new IllegalStateException("Native touch response failed", e); }
            }
            throw new IllegalStateException("No touch button " + prefix + " in " + form.title());
        }
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=240)
    public void bedrockFormsDoNotClaimFailedHelperMutationsSucceeded(GameTestHelper c) throws Exception {
        var io = new FaultyIo();
        try (var f = new Fixture(c, io)) {
            c.assertValueEqual(f.helpers.spawn(f.owner, "near"), 1, "A real helper is created first");
            var entry = f.helpers.owned(f.owner, "near"); String id = entry.getKey(); var original = entry.getValue();
            var entity = f.helpers.loaded.get(UUID.fromString(id)); var touch = new Touch();
            c.assertValueEqual(BedrockAgentMenu.open(f.owner, touch), 1, "The native roster opens");
            touch.click("near •");
            io.arm("write:agents.json.tmp", false); touch.click("Stay / pause");
            c.assertTrue(touch.last().content().contains("Movement change was not confirmed") && f.helpers.data.agents.get(id) == original,
                "A refused mode save is reported on the form, not as a successful update");
            touch.click("Choose profile"); io.arm("write:agents.json.tmp", false); touch.click("Choose: Debug");
            c.assertTrue(touch.last().content().contains("Profile change was not confirmed") && f.helpers.data.agents.get(id) == original,
                "A refused profile save is reported without a false success");
            touch.click("Back to your squad"); touch.click("Squad controls");
            io.arm("write:agents.json.tmp", false); touch.click("Squad: stay");
            c.assertTrue(touch.last().content().contains("Squad movement change was not confirmed") && f.helpers.data.agents.get(id) == original,
                "A failed squad movement save is not labeled successful");
            io.arm("write:agents.json.tmp", false); touch.click("Squad profile: Ultimate Finals");
            c.assertTrue(touch.last().content().contains("Squad profile change was not confirmed") && f.helpers.data.agents.get(id) == original,
                "A failed squad profile save is not labeled successful");
            touch.click("Back to your squad"); touch.click("near •"); touch.click("Dismiss helper");
            io.arm("write:agents.json.recovery-required.tmp", false); touch.click("Confirm dismiss");
            c.assertTrue(touch.last().content().contains("Dismissal was not confirmed") && !touch.last().content().contains("near was dismissed")
                && f.helpers.data.agents.get(id) == original && !entity.isRemoved(), "A refused dismissal remains visibly unconfirmed and preserves the helper");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=240)
    public void bedrockCreateReportsActualProfileWhenProfileSaveFails(GameTestHelper c) throws Exception {
        var io = new FaultyIo(); var wire = new NativePlayConnection(c.getLevel().registryAccess());
        try (var f = new Fixture(c, io, wire)) {
            var touch = new Touch(); BedrockAgentMenu.open(f.owner, touch); touch.click("Create your first helper");
            wire.drain(); io.armLater("write:agents.json.tmp", false, 1); touch.click("Create: Debug");
            var entry = f.helpers.owned(f.owner, "helper-1");
            c.assertTrue(entry != null && entry.getValue().profile() == AgentCompanions.Profile.REGULAR,
                "The helper exists with its actual default profile when its second save fails");
            var messages = chat(wire);
            c.assertTrue(messages.stream().anyMatch(text -> text.contains("Regular; selected profile was not confirmed")),
                "The native outbound message identifies the actual profile and states that the requested one was not confirmed: " + messages);
            c.assertFalse(messages.stream().anyMatch(text -> text.contains("appeared beside you • Debug")),
                "The client never receives the false requested-profile success");
        } finally { wire.channel.finishAndReleaseAll(); }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void pendingCreationSurvivesRestartWithoutDeletingRealUnloadedHelpers(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            f.unloaded(f.owner.getUUID(), 1, "older-");
            var older = f.helpers.owned(f.owner, "older-1");
            c.assertValueEqual(f.helpers.spawn(f.owner, "single"), 1, "A single helper can be created");
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 2, AgentCompanions.Profile.REGULAR), 2, "A batch can be created");
            var disk = AgentCompanions.read(f.helpers.file);
            c.assertValueEqual(disk.pendingCreations.size(), 3, "All new identities remain explicitly pending on disk");
            c.assertFalse(disk.pendingCreations.contains(older.getKey()), "A pre-existing unloaded helper is not marked as a new creation");
            var restarted = f.restart();
            c.assertFalse(restarted.creationRecoveryRequired, "A pending creation is not silently treated as a corrupt roster");
            for (String id : disk.pendingCreations) {
                c.assertTrue(restarted.unresolvedCreation(id), "Pending identity is visible for owner review after restart");
                c.assertTrue(restarted.creationReview(id).contains("may still exist"), "Status does not claim that an unloaded helper is missing");
                var saved = f.saved(UUID.fromString(id)); restarted.load(saved);
                c.assertTrue(!saved.isRemoved() && restarted.loaded.get(saved.getUUID()) == saved,
                    "A matching saved helper is preserved rather than replaced or deleted");
                c.assertTrue(restarted.unresolvedCreation(id), "A load event alone does not prove physical storage durability");
                c.assertTrue(restarted.creationReview(id).contains("may still exist"),
                    "A detached object in the loaded map is not reported as a current world entity");
                saved.discard();
            }
            var existing = f.saved(UUID.fromString(older.getKey())); restarted.load(existing);
            c.assertTrue(!existing.isRemoved() && !restarted.unresolvedCreation(older.getKey()), "Existing unloaded helper stays registered and unchanged");
            existing.discard();
            c.assertValueEqual(AgentCompanions.read(f.helpers.file).pendingCreations, disk.pendingCreations,
                "Reading and loading never retire uncertain creation evidence");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void pendingStatusDistinguishesLoadedHelperWithoutClaimingDurability(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            c.assertValueEqual(f.helpers.spawn(f.owner, "visible"), 1, "A native helper is created");
            String id = f.helpers.owned(f.owner, "visible").getKey();
            var live = f.helpers.loaded.get(UUID.fromString(id));
            byte[] before = Files.readAllBytes(f.helpers.file);
            var restarted = f.restart();
            c.assertTrue(restarted.creationReview(id).contains("may still exist"), "An unloaded identity stays uncertain");
            restarted.load(live);
            c.assertTrue(restarted.creationReview(id).contains("currently loaded"), "An actual registered live entity is reported as loaded");
            c.assertTrue(restarted.creationReview(id).contains("durability remains unverified"), "A load does not claim a durable disk write");
            c.assertTrue(restarted.unresolvedCreation(id), "The pending identity is not cleared by a load");
            AgentCompanions.INSTANCES.put(restarted.server, restarted);
            try {
                String shown = AgentMenu.helperInfo(f.owner, id, restarted.data.agents.get(id));
                c.assertTrue(shown.contains("currently loaded") && shown.contains("durability remains unverified"),
                    "Shared Java and Bedrock status reports the same bounded observation");
            } finally { AgentCompanions.INSTANCES.put(f.helpers.server, f.helpers); }
            c.assertTrue(Arrays.equals(before, Files.readAllBytes(f.helpers.file)), "Status and loading do not change the saved roster");
            restarted.loaded.clear();
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void unprovableCreationSaveNeverInsertsAnEntity(GameTestHelper c) throws Exception {
        var io = new FaultyIo();
        try (var f = new Fixture(c, io)) {
            f.unloaded(f.owner.getUUID(), 1, "older-");
            byte[] before = Files.readAllBytes(f.helpers.file);
            var agents = new java.util.TreeMap<>(f.helpers.data.agents);
            io.noDirectorySync = true;
            c.assertValueEqual(f.helpers.spawn(f.owner, "single"), 0, "Single creation is refused before an unproven rename");
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 2, AgentCompanions.Profile.REGULAR), 0, "Batch creation is refused before an unproven rename");
            c.assertTrue(Arrays.equals(before, Files.readAllBytes(f.helpers.file)) && f.helpers.data.agents.equals(agents)
                && f.helpers.data.pendingCreations.isEmpty() && f.helpers.loaded.isEmpty(),
                "No roster, pending identity, existing unloaded helper, or world entity changed");
            c.assertTrue(f.helpers.creationRecoveryRequired && Files.exists(HelperPersistence.recoveryTemporary(f.helpers.file))
                && f.restart().creationRecoveryRequired, "The unfinished recovery note freezes now and on restart");
            io.noDirectorySync = false;
            f.helpers.spawn(f.owner, "single");
            c.assertTrue(f.helpers.owned(f.owner, "single") == null && f.helpers.creationRecoveryRequired
                && Files.exists(HelperPersistence.recoveryTemporary(f.helpers.file)),
                "Repairing storage alone does not discard unfinished recovery evidence or create a helper");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void creationRefusesBeforeInsertingWhenRecoveryGuardCannotBeWritten(GameTestHelper c) throws Exception {
        for (boolean batch : new boolean[]{false, true}) {
            var io = new FaultyIo();
            try (var f = new Fixture(c, io)) {
                f.unloaded(f.owner.getUUID(), 1, "older-");
                var before = Map.copyOf(f.helpers.data.agents); byte[] roster = Files.readAllBytes(f.helpers.file);
                io.arm("write:agents.json.recovery-required.tmp", false);
                int result = batch ? f.helpers.spawnBatch(f.owner, 2, AgentCompanions.Profile.REGULAR)
                                   : f.helpers.spawn(f.owner, "guarded");
                c.assertValueEqual(result, 0, "Creation refuses when its recovery guard cannot be prepared: " + batch);
                c.assertTrue(f.helpers.data.agents.equals(before) && f.helpers.data.pendingCreations.isEmpty()
                    && f.helpers.loaded.isEmpty() && Arrays.equals(roster, Files.readAllBytes(f.helpers.file)),
                    "No existing or new ownership, pending identity, or native helper changes: " + batch);
                c.assertTrue(Files.notExists(f.marker()) && !f.helpers.creationRecoveryRequired,
                    "A failure before creating a note leaves no uncertainty: " + batch);
                var restarted = f.restart();
                c.assertTrue(!restarted.creationRecoveryRequired && restarted.data.agents.equals(before),
                    "Restart sees the unchanged roster and its unloaded helper: " + batch);
                int retry = batch ? f.helpers.spawnBatch(f.owner, 2, AgentCompanions.Profile.REGULAR)
                                  : f.helpers.spawn(f.owner, "guarded");
                c.assertValueEqual(retry, batch ? 2 : 1, "A clean pre-write failure permits a successful retry: " + batch);
            }
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void partialCreationKeepsPreexistingGuardWhenLaterStorageWritesFail(GameTestHelper c) throws Exception {
        var io = new FaultyIo();
        try (var f = new Fixture(c, io)) {
            f.unloaded(f.owner.getUUID(), 1, "older-"); var older = f.helpers.owned(f.owner, "older-1");
            var inserted = new AtomicReference<UUID>();
            c.assertTrue(GUARD_FAILURE.compareAndSet(null, new GuardFailure(f.helpers, f.owner.getUUID(), io, inserted)),
                "The exact-owner insertion fault is armed once");
            int result;
            try { result = f.helpers.spawn(f.owner, "guarded"); }
            finally { GUARD_FAILURE.set(null); }
            c.assertTrue(result == 0 && inserted.get() != null && f.helpers.creationRecoveryRequired,
                "The partial insertion is reported unresolved and freezes ownership");
            c.assertTrue(Files.exists(f.marker()) && f.restart().creationRecoveryRequired,
                "The guard established before insertion remains when all later marker and roster writes fail");
            var saved = AgentCompanions.read(f.helpers.file);
            c.assertTrue(saved.agents.containsKey(older.getKey()) && saved.agents.containsKey(inserted.get().toString())
                && saved.pendingCreations.contains(inserted.get().toString()) && saved.dismissed.isEmpty(),
                "The old unloaded helper and uncertain new identity remain in the durable roster");
            var copy = f.saved(UUID.fromString(older.getKey())); f.restart().load(copy);
            c.assertFalse(copy.isRemoved(), "A saved copy of the old unloaded helper is never retired");
            copy.discard();
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void successfulInsertionWithUnretiredCreationGuardIsUnresolved(GameTestHelper c) throws Exception {
        var io = new FaultyIo(); var wire = new NativePlayConnection(c.getLevel().registryAccess());
        try (var f = new Fixture(c, io, wire)) {
            io.arm("delete:agents.json.recovery-required", false); wire.drain();
            c.assertValueEqual(f.helpers.spawn(f.owner, "guarded"), 0,
                "Creation cannot report full success when its guard cannot be retired");
            var record = f.helpers.owned(f.owner, "guarded");
            c.assertTrue(record != null && f.helpers.unresolvedCreation(record.getKey()) && f.helpers.creationUnresolved
                && f.helpers.creationRecoveryRequired && Files.exists(f.marker()) && f.restart().creationRecoveryRequired,
                "The live and saved identity remains pending and the durable guard freezes restart");
            var entity = f.helpers.loaded.get(UUID.fromString(record.getKey()));
            c.assertTrue(entity != null && entity.isAlive() && !entity.isRemoved(), "The inserted helper is kept");
            assertUnresolvedNotice(c, chat(wire), "guard retirement refused after successful insertion");
        } finally { wire.channel.finishAndReleaseAll(); }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void competingRecoveryNotePreventsGuardRetirementAfterInsertion(GameTestHelper c) throws Exception {
        var wire = new NativePlayConnection(c.getLevel().registryAccess());
        try (var f = new Fixture(c, HelperPersistence.NATIVE, wire)) {
            var inserted = new AtomicReference<UUID>();
            c.assertTrue(COMPETING_NOTE.compareAndSet(null, new CompetingNote(f.helpers, f.owner.getUUID(), inserted)),
                "The exact-owner competing-note fixture is armed once");
            wire.drain(); int result;
            try { result = f.helpers.spawn(f.owner, "contested"); }
            finally { COMPETING_NOTE.set(null); }
            var record = f.helpers.owned(f.owner, "contested");
            var temporary = HelperPersistence.recoveryTemporary(f.helpers.file);
            c.assertTrue(result == 0 && inserted.get() != null && record != null && f.helpers.creationUnresolved
                && f.helpers.creationRecoveryRequired && Files.exists(f.marker())
                && Files.readString(temporary).equals("competing evidence"),
                "The competing note prevents marker removal, freezes ownership and preserves both notes");
            var restarted = f.restart();
            c.assertTrue(restarted.creationRecoveryRequired && restarted.unresolvedCreation(record.getKey()),
                "Restart retains the pending identity and remains frozen");
            var entity = f.helpers.loaded.get(inserted.get());
            c.assertTrue(entity != null && entity.isAlive() && !entity.isRemoved(), "The inserted body is retained");
            assertUnresolvedNotice(c, chat(wire), "competing recovery note");
        } finally { wire.channel.finishAndReleaseAll(); }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void creationKeepsPendingIdentityWhenGuardDeletionAndFollowupNoteFail(GameTestHelper c) throws Exception {
        var io = new FaultyIo(); var wire = new NativePlayConnection(c.getLevel().registryAccess());
        try (var f = new Fixture(c, io, wire)) {
            f.unloaded(f.owner.getUUID(), 1, "older-"); var older = f.helpers.owned(f.owner, "older-1");
            io.arm("sync-after:delete:agents.json.recovery-required", true);
            io.armLater("write:agents.json.recovery-required.tmp", false, 1); wire.drain();
            c.assertValueEqual(f.helpers.spawn(f.owner, "guarded"), 0,
                "An unproven guard retirement reports creation as unresolved");
            var record = f.helpers.owned(f.owner, "guarded");
            c.assertTrue(record != null && f.helpers.creationUnresolved && f.helpers.creationRecoveryRequired
                && Files.notExists(f.marker()) && Files.notExists(HelperPersistence.recoveryTemporary(f.helpers.file)),
                "This runtime freezes even when neither recovery note survives");
            assertUnresolvedNotice(c, chat(wire), "guard deletion and followup note failed");
            var restarted = f.restart();
            c.assertTrue(!restarted.creationRecoveryRequired && restarted.unresolvedCreation(record.getKey())
                && restarted.data.agents.containsKey(older.getKey()),
                "Known limit: restart need not freeze without a note, but both the uncertain and older identities remain reserved");
            var copy = f.saved(UUID.fromString(older.getKey())); restarted.load(copy);
            c.assertFalse(copy.isRemoved(), "The older unloaded helper is retained"); copy.discard();
        } finally { wire.channel.finishAndReleaseAll(); }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void ambiguousCreationRosterWriteLeavesPendingEvidenceAndNoInsertedEntity(GameTestHelper c) throws Exception {
        var io = new FaultyIo();
        try (var f = new Fixture(c, io)) {
            f.unloaded(f.owner.getUUID(), 1, "older-");
            var older = f.helpers.owned(f.owner, "older-1");
            io.arm("sync-after:move:agents.json", true);
            c.assertValueEqual(f.helpers.spawn(f.owner, "uncertain"), 0, "An uncertain roster save never reports creation success");
            c.assertTrue(f.helpers.loaded.isEmpty() && f.helpers.data.agents.get(older.getKey()) == older.getValue(),
                "No new native helper is inserted and the existing unloaded record is retained");
            var restarted = f.restart();
            var pending = restarted.owned(f.owner, "uncertain");
            c.assertTrue(restarted.creationRecoveryRequired && pending != null && restarted.unresolvedCreation(pending.getKey()),
                "After the interrupted write, the durable pending identity is visible and ownership stays frozen");
            var prior = f.saved(UUID.fromString(older.getKey())); restarted.load(prior);
            c.assertTrue(!prior.isRemoved(), "A genuine older unloaded helper is not deleted during recovery");
            prior.discard();
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=240)
    public void failedOrdinarySaveRestoresExactRecordsAndFreezesOnlyWhenDiskMayHaveChanged(GameTestHelper c) throws Exception {
        for (var fault : REPLACEMENT_FAULTS) {
            var io = new FaultyIo(); String label = fault.toString();
            try (var f = new Fixture(c, io)) {
                f.helpers.spawn(f.owner, "kept"); var entry = f.helpers.owned(f.owner, "kept"); String id = entry.getKey(); var record = entry.getValue();
                var entity = f.helpers.loaded.get(UUID.fromString(id));
                byte[] roster = Files.readAllBytes(f.helpers.file);
                io.arm(fault.getKey(), fault.getValue());
                c.assertValueEqual(f.helpers.mode(f.owner, "kept", AgentCompanions.Mode.STAY), 0, "A failed save never reports success: " + label);
                c.assertTrue(f.helpers.data.agents.get(id) == record && entity.entityTags().contains("infinity_mode_follow"),
                    "Memory and the native helper keep the exact unsaved-over record: " + label);
                boolean untouched = fault.getKey().startsWith("write");
                c.assertValueEqual(f.helpers.creationRecoveryRequired, !untouched, "Only a save that may have changed disk freezes: " + label);
                c.assertValueEqual(Files.exists(f.marker()), !untouched, "Only a freeze leaves a recovery marker: " + label);
                c.assertValueEqual(AgentCompanions.read(f.helpers.file).agents.get(id).mode() == AgentCompanions.Mode.STAY, fault.getValue(),
                    "Disk holds the new roster exactly when the rename happened: " + label);
                if (untouched) {
                    c.assertTrue(Arrays.equals(roster, Files.readAllBytes(f.helpers.file)), "An untouched roster is byte-for-byte unchanged");
                    c.assertValueEqual(f.helpers.mode(f.owner, "kept", AgentCompanions.Mode.STAY), 1, "A retry saves normally");
                } else {
                    byte[] unresolved = Files.readAllBytes(f.helpers.file);
                    boolean refused = false;
                    try { f.helpers.save(); } catch (IllegalStateException expected) { refused = true; }
                    c.assertTrue(refused && Arrays.equals(unresolved, Files.readAllBytes(f.helpers.file)), "A frozen save reports failure and writes nothing: " + label);
                    c.assertTrue(f.restart().creationRecoveryRequired, "The marker keeps a restart frozen: " + label);
                }
            }
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=400)
    public void failedDismissalSaveNeverDeletesALoadedOrUnloadedHelperNowOrAfterRestart(GameTestHelper c) throws Exception {
        for (var fault : REPLACEMENT_FAULTS) for (boolean loadedTarget : new boolean[]{true, false}) {
            var io = new FaultyIo();
            try (var f = new Fixture(c, io)) {
                f.unloaded(f.owner.getUUID(), 1, "away-");
                f.helpers.spawn(f.owner, "near");
                String name = loadedTarget ? "near" : "away-1", label = name + " " + fault;
                var entry = f.helpers.owned(f.owner, name); String id = entry.getKey(); var record = entry.getValue();
                var live = f.helpers.loaded.get(UUID.fromString(id));
                c.assertTrue(loadedTarget == (live != null), "The target is loaded exactly when intended: " + label);
                io.arm(fault.getKey(), fault.getValue());
                c.assertValueEqual(f.helpers.dismiss(f.owner, name), 0, "A dismissal whose save fails never reports success: " + label);
                c.assertTrue(f.helpers.creationRecoveryRequired && Files.exists(f.marker()), "Every failed removal save freezes and leaves a marker: " + label);
                c.assertTrue(f.helpers.data.agents.get(id) == record && !f.helpers.data.dismissed.contains(id)
                    && !f.helpers.data.guardedRemovals.contains(id) && !f.helpers.provenRemoved.contains(id),
                    "Memory keeps the exact record and restores both removal lists after failure: " + label);
                if (live != null) c.assertTrue(f.helpers.loaded.get(live.getUUID()) == live && live.isAlive() && c.getLevel().getEntity(live.getUUID()) == live,
                    "The loaded helper is kept in the world and registered: " + label);
                f.helpers.controlLoaded();
                if (live != null) c.assertTrue(!live.isRemoved() && f.helpers.loaded.get(live.getUUID()) == live, "The next tick pass keeps it: " + label);
                var copy = f.saved(UUID.fromString(id)); f.helpers.load(copy);
                c.assertFalse(copy.isRemoved(), "A chunk load of its saved copy keeps it: " + label);
                if (live != null) f.helpers.loaded.put(live.getUUID(), live); else f.helpers.loaded.remove(copy.getUUID(), copy);
                copy.discard();
                boolean renamed = fault.getValue();
                var disk = AgentCompanions.read(f.helpers.file);
                c.assertValueEqual(disk.agents.containsKey(id), !renamed,
                    "Disk holds exactly one of the two complete rosters, the new one only if the rename happened: " + label);
                c.assertTrue(disk.dismissed.contains(id) == renamed && disk.guardedRemovals.contains(id) == renamed,
                    "Both disk removal lists agree with the exact roster version: " + label);
                var restarted = f.restart(); var later = f.saved(UUID.fromString(id)); restarted.load(later);
                c.assertTrue(restarted.creationRecoveryRequired && !restarted.provenRemoved.contains(id) && !later.isRemoved(),
                    "Restart stays frozen by the marker and keeps the saved copy without trusting the proof: " + label);
                later.discard();
                // Removing the marker is the host's explicit decision; the durable roster then decides alone.
                Files.delete(f.marker());
                var resolved = f.restart(); var resolvedCopy = f.saved(UUID.fromString(id)); resolved.load(resolvedCopy);
                c.assertValueEqual(resolved.provenRemoved.contains(id), renamed, "Only the verified on-disk version supplies a removal proof: " + label);
                c.assertValueEqual(resolvedCopy.isRemoved(), renamed,
                    "After the host clears the marker, a copy is retired only if the dismissal had durably reached disk: " + label);
                resolvedCopy.discard();
            }
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=240)
    public void nativeDeathWritesNothingAndLeavesAnArmedRosterFaultForTheNextLegitimateWrite(GameTestHelper c) throws Exception {
        // Natural death performs no roster write, so a replacement fault armed
        // before it must survive untouched and fire on the next real change.
        for (var fault : REPLACEMENT_FAULTS) {
            var io = new FaultyIo(); String label = fault.toString();
            try (var f = new Fixture(c, io)) {
                f.helpers.spawn(f.owner, "doomed"); var entry = f.helpers.owned(f.owner, "doomed"); String id = entry.getKey(); var record = entry.getValue();
                f.helpers.spawn(f.owner, "other"); var other = f.helpers.loaded.get(UUID.fromString(f.helpers.owned(f.owner, "other").getKey()));
                var entity = f.helpers.loaded.get(UUID.fromString(id));
                f.legacy(id);
                byte[] roster = Files.readAllBytes(f.helpers.file), previous = Files.readAllBytes(f.previousFile());
                io.arm(fault.getKey(), fault.getValue());
                c.assertTrue(entity.hurtServer(c.getLevel(), entity.damageSources().genericKill(), 10000) && !entity.isAlive(),
                    "A real native death occurs and its callback does not throw: " + label);
                c.assertTrue(!f.helpers.creationRecoveryRequired && Files.notExists(f.marker()), "Death neither freezes nor writes a marker: " + label);
                c.assertTrue(f.helpers.data.agents.get(id) == record && !f.helpers.data.dismissed.contains(id) && !f.helpers.data.pendingCreations.contains(id),
                    "The exact nonpending record is kept with no removal proof: " + label);
                c.assertTrue(Arrays.equals(roster, Files.readAllBytes(f.helpers.file)) && Arrays.equals(previous, Files.readAllBytes(f.previousFile())),
                    "Neither the roster nor its .previous copy is rewritten: " + label);
                var copy = f.saved(UUID.fromString(id)); f.helpers.load(copy);
                c.assertTrue(!copy.isRemoved() && f.helpers.loaded.get(copy.getUUID()) == copy, "A later saved copy keeps its record and registers: " + label);
                f.helpers.loaded.remove(copy.getUUID(), copy); copy.discard();
                boolean untouched = fault.getKey().startsWith("write");
                c.assertValueEqual(f.helpers.mode(f.owner, "other", AgentCompanions.Mode.STAY), 0, "The armed fault fires on the next legitimate write: " + label);
                c.assertValueEqual(f.helpers.creationRecoveryRequired, !untouched, "That write freezes only if disk may have changed: " + label);
                c.assertTrue(other.isAlive() && f.helpers.loaded.get(other.getUUID()) == other, "The unrelated helper is kept: " + label);
                var restarted = f.restart(); var later = f.saved(UUID.fromString(id)); restarted.load(later);
                c.assertTrue(restarted.data.agents.containsKey(id) && !restarted.data.dismissed.contains(id) && !later.isRemoved(),
                    "After restart the dead helper's identity and a saved copy are kept: " + label);
                restarted.loaded.remove(later.getUUID(), later); later.discard();
            }
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=240)
    public void storageWithoutDirectorySyncKeepsOrdinarySavesAndRefusesEveryDestructiveRemoval(GameTestHelper c) throws Exception {
        var io = new FaultyIo();
        try (var f = new Fixture(c, io)) {
            c.assertValueEqual(f.helpers.spawn(f.owner, "near"), 1, "Single creation is saved durably");
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 2, AgentCompanions.Profile.REGULAR), 2, "Batch creation is saved durably");
            f.legacy(f.helpers.owned(f.owner, "helper-1").getKey());
            f.unloaded(f.owner.getUUID(), 1, "away-"); var awayEntry = f.helpers.owned(f.owner, "away-1"); String away = awayEntry.getKey();
            io.noDirectorySync = true;
            c.assertValueEqual(f.helpers.mode(f.owner, "near", AgentCompanions.Mode.GUARD), 1, "Movement changes save");
            c.assertValueEqual(f.helpers.profile(f.owner, "near", AgentCompanions.Profile.DEBUG), 1, "Profile changes save");
            c.assertFalse(f.helpers.creationRecoveryRequired, "Ordinary unproven saves never freeze");
            c.assertValueEqual(AgentCompanions.read(f.helpers.file).agents, f.helpers.data.agents, "Every ordinary change reached the roster file");
            var near = f.helpers.owned(f.owner, "near"); var nearEntity = f.helpers.loaded.get(UUID.fromString(near.getKey()));
            byte[] roster = Files.readAllBytes(f.helpers.file);
            c.assertValueEqual(f.helpers.dismiss(f.owner, "near"), 0, "Dismissing a loaded helper is refused where no durable marker can be made");
            c.assertTrue(f.helpers.data.agents.get(near.getKey()) == near.getValue() && nearEntity.isAlive() && !nearEntity.isRemoved()
                && Arrays.equals(roster, Files.readAllBytes(f.helpers.file)) && f.helpers.creationRecoveryRequired
                && Files.notExists(f.marker()) && Files.exists(HelperPersistence.recoveryTemporary(f.helpers.file)),
                "The refusal keeps the records and freezes while the temporary note remains");
            roster = Files.readAllBytes(f.helpers.file);
            c.assertValueEqual(f.helpers.dismiss(f.owner, "away-1"), 0, "Dismissing an unloaded helper is refused too");
            c.assertTrue(f.helpers.data.agents.get(away) == awayEntry.getValue() && Arrays.equals(roster, Files.readAllBytes(f.helpers.file)),
                "The unloaded record and the saved roster are unchanged");
            var batch = f.helpers.owned(f.owner, "helper-1"); String batchId = batch.getKey();
            var batchEntity = f.helpers.loaded.get(UUID.fromString(batchId));
            roster = Files.readAllBytes(f.helpers.file);
            c.assertTrue(batchEntity.hurtServer(c.getLevel(), batchEntity.damageSources().genericKill(), 10000) && !batchEntity.isAlive(), "A real native death occurs");
            var saved = AgentCompanions.read(f.helpers.file);
            c.assertTrue(saved.agents.get(batchId).equals(batch.getValue()) && saved.dismissed.isEmpty() && f.helpers.creationRecoveryRequired
                && Arrays.equals(roster, Files.readAllBytes(f.helpers.file)),
                "Death keeps the saved record and does not add removal proof while frozen");
            for (var restart : List.<java.util.function.Supplier<AgentCompanions>>of(() -> f.helpers, () -> f.restart(io), f::restart)) {
                var helpers = restart.get(); var copy = f.saved(UUID.fromString(batchId)); helpers.load(copy);
                c.assertTrue(helpers.creationRecoveryRequired && !copy.isRemoved() && helpers.loaded.get(copy.getUUID()) == copy,
                    "The temporary note freezes before and after restart and the later saved copy keeps its record");
                helpers.loaded.remove(copy.getUUID(), copy); copy.discard();
            }
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=240)
    public void recoveryMarkerFreezesEveryRestartUntilTheHostRemovesItAndIsNeverOverwritten(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            f.unloaded(f.owner.getUUID(), 1, "away-"); String away = f.helpers.owned(f.owner, "away-1").getKey();
            f.helpers.spawn(f.owner, "kept");
            c.assertValueEqual(f.helpers.dismiss(f.owner, "away-1"), 1, "A proven dismissal leaves durable removal proof");
            byte[] roster = Files.readAllBytes(f.helpers.file), previous = Files.readAllBytes(f.previousFile());
            var cases = new LinkedHashMap<String, byte[]>();
            cases.put("empty", new byte[0]);
            cases.put("note", "host note\n".getBytes(StandardCharsets.UTF_8));
            cases.put("binary", new byte[]{(byte)0xff, 0, (byte)0xfe});
            for (var entry : cases.entrySet()) {
                Files.write(f.marker(), entry.getValue());
                var restarted = f.restart();
                c.assertTrue(restarted.creationRecoveryRequired && restarted.remaining(f.owner) == 0, "A " + entry.getKey() + " marker freezes ownership");
                c.assertValueEqual(restarted.dismiss(f.owner, "kept"), 0, "A frozen restart refuses dismissal: " + entry.getKey());
                boolean refused = false;
                try { restarted.save(); } catch (IllegalStateException expected) { refused = true; }
                c.assertTrue(refused, "A frozen save reports failure rather than silently succeeding: " + entry.getKey());
                var copy = f.saved(UUID.fromString(away)); restarted.load(copy);
                c.assertFalse(copy.isRemoved(), "While frozen, even a proven dismissal never deletes its saved copy: " + entry.getKey());
                copy.discard();
                new HelperPersistence(HelperPersistence.NATIVE).markRecoveryRequired(f.helpers.file, "a later failure");
                c.assertTrue(Arrays.equals(roster, Files.readAllBytes(f.helpers.file)) && Arrays.equals(previous, Files.readAllBytes(f.previousFile()))
                    && Arrays.equals(entry.getValue(), Files.readAllBytes(f.marker())), "No roster, .previous or existing marker is overwritten: " + entry.getKey());
            }
            Files.delete(f.marker()); Files.createDirectory(f.marker());
            c.assertTrue(f.restart().creationRecoveryRequired, "A directory at the marker path also freezes");
            Files.delete(f.marker());
            Path temporary = HelperPersistence.recoveryTemporary(f.helpers.file);
            Files.writeString(temporary, "never renamed");
            var resolved = f.restart(); var copy = f.saved(UUID.fromString(away)); resolved.load(copy);
            c.assertTrue(resolved.creationRecoveryRequired && !copy.isRemoved() && Files.readString(temporary).equals("never renamed"),
                "A leftover unrenamed temporary note freezes restart and keeps both evidence and the saved helper");
            new HelperPersistence(HelperPersistence.NATIVE).markRecoveryRequired(f.helpers.file, "must not overwrite");
            c.assertTrue(Files.notExists(f.marker()) && Files.readString(temporary).equals("never renamed"),
                "A temporary recovery note is not overwritten or replaced by a later note");
            copy.discard();
        }
        var io = new FaultyIo();
        try (var f = new Fixture(c, io)) {
            f.helpers.spawn(f.owner, "kept");
            io.arm("move:agents.json", true); io.arm("write:agents.json.recovery-required.tmp", false);
            c.assertValueEqual(f.helpers.mode(f.owner, "kept", AgentCompanions.Mode.STAY), 0, "The ambiguous save reports failure");
            c.assertTrue(f.helpers.creationRecoveryRequired && Files.notExists(f.marker()), "This runtime is frozen even though its after-the-fact note could not be written");
            c.assertTrue(AgentCompanions.read(f.helpers.file).dismissed.isEmpty(), "An ordinary save never carries new removal proof");
            var restarted = f.restart(); var copy = f.saved(UUID.fromString(f.helpers.owned(f.owner, "kept").getKey())); restarted.load(copy);
            c.assertTrue(!restarted.creationRecoveryRequired && !copy.isRemoved(),
                "Known limit: without the note a restart is not frozen, but with no removal proof it can only keep the helper");
            restarted.loaded.remove(copy.getUUID(), copy); copy.discard();
        }
        io = new FaultyIo();
        try (var f = new Fixture(c, io)) {
            f.helpers.spawn(f.owner, "kept"); String id = f.helpers.owned(f.owner, "kept").getKey();
            io.arm("move:agents.json", true); io.arm("write:agents.json.recovery-required.tmp", true);
            c.assertValueEqual(f.helpers.mode(f.owner, "kept", AgentCompanions.Mode.STAY), 0,
                "An ambiguous roster write followed by an interrupted note does not report success");
            Path temporary = HelperPersistence.recoveryTemporary(f.helpers.file);
            c.assertTrue(f.helpers.creationRecoveryRequired && Files.notExists(f.marker()) && Files.exists(temporary),
                "The interrupted note remains as recovery evidence without a final marker");
            byte[] evidence = Files.readAllBytes(temporary), savedRoster = Files.readAllBytes(f.helpers.file);
            var restarted = f.restart(); var copy = f.saved(UUID.fromString(id)); restarted.load(copy);
            c.assertTrue(restarted.creationRecoveryRequired && !copy.isRemoved() && Arrays.equals(evidence, Files.readAllBytes(temporary))
                && Arrays.equals(savedRoster, Files.readAllBytes(f.helpers.file)),
                "A restart with an interrupted note freezes and preserves the saved helper, roster and note");
            restarted.loaded.remove(copy.getUUID(), copy); copy.discard();
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void onlyProvenDurableRemovalRetiresAStaleTaggedHelperOnLoadOrTick(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            f.unloaded(f.owner.getUUID(), 2, "away-");
            String away = f.helpers.owned(f.owner, "away-1").getKey(), stay = f.helpers.owned(f.owner, "away-2").getKey();
            f.helpers.spawn(f.owner, "near"); String near = f.helpers.owned(f.owner, "near").getKey();
            var nearEntity = f.helpers.loaded.get(UUID.fromString(near));
            f.legacy(near);
            c.assertValueEqual(f.helpers.dismiss(f.owner, "away-1"), 1, "An unloaded helper can still be dismissed");
            c.assertTrue(nearEntity.hurtServer(c.getLevel(), nearEntity.damageSources().genericKill(), 10000) && !nearEntity.isAlive(), "A real native death occurs");
            var saved = AgentCompanions.read(f.helpers.file);
            c.assertTrue(saved.dismissed.contains(away) && !saved.agents.containsKey(away),
                "An explicit dismissal durably replaces the record with removal proof");
            c.assertTrue(saved.agents.containsKey(near) && !saved.dismissed.contains(near) && !f.helpers.creationRecoveryRequired,
                "A natural death keeps its record and never becomes removal proof");
            var kept = f.saved(UUID.fromString(stay)); f.helpers.load(kept);
            c.assertTrue(!kept.isRemoved() && f.helpers.loaded.get(kept.getUUID()) == kept, "A pre-existing unloaded helper with a record loads and registers normally");
            f.helpers.loaded.remove(kept.getUUID(), kept); kept.discard();
            var orphan = f.saved(UUID.randomUUID());
            var dismissedCopy = f.saved(UUID.fromString(away));
            f.helpers.loaded.put(orphan.getUUID(), orphan); f.helpers.loaded.put(dismissedCopy.getUUID(), dismissedCopy);
            f.helpers.controlLoaded();
            c.assertTrue(!orphan.isRemoved() && !f.helpers.loaded.containsKey(orphan.getUUID()), "The tick pass unregisters a recordless helper without proof but keeps it");
            c.assertTrue(dismissedCopy.isRemoved(), "The tick pass retires only a copy whose removal was proven durable");
            orphan.discard();
            var restarted = f.restart();
            var staleAway = f.saved(UUID.fromString(away));
            var staleNear = f.saved(UUID.fromString(near));
            var unknown = f.saved(UUID.randomUUID());
            restarted.load(staleAway); restarted.load(staleNear); restarted.load(unknown);
            c.assertTrue(staleAway.isRemoved(), "After restart, a saved copy with durable dismissal proof is retired on load");
            c.assertTrue(!staleNear.isRemoved() && restarted.loaded.get(staleNear.getUUID()) == staleNear,
                "After restart, a saved copy of a helper that died naturally survives and keeps its record");
            restarted.loaded.remove(staleNear.getUUID(), staleNear); staleNear.discard();
            c.assertTrue(!unknown.isRemoved() && !restarted.loaded.containsKey(unknown.getUUID())
                && ((AgentGoalAccess) unknown).infinity$getTargetSelector().getAvailableGoals().isEmpty(),
                "An unknown tagged helper is kept, unregistered and without native targeting");
            c.assertFalse(AgentCompanions.allowDamage(f.owner, unknown.damageSources().mobAttack(unknown)), "An unregistered tagged helper cannot deal damage");
            unknown.discard();
            Path legacy = f.directory.resolve("legacy.json");
            Files.writeString(legacy, "{\"format\":2,\"agents\":{}}");
            var legacyCopy = f.saved(UUID.fromString(away)); new AgentCompanions(f.helpers.server, legacy).load(legacyCopy);
            c.assertFalse(legacyCopy.isRemoved(), "A roster written before removal proof existed never discards an absent helper");
            legacyCopy.discard();
            Files.writeString(f.marker(), "host is inspecting");
            var frozenCopy = f.saved(UUID.fromString(away)); var frozen = f.restart(); frozen.load(frozenCopy);
            c.assertTrue(frozen.creationRecoveryRequired && !frozenCopy.isRemoved(), "While recovery is required, even proven removals are retained rather than discarded");
            frozenCopy.discard();
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=240)
    public void legacyTombstonesRemainAmbiguousAcrossSaveAndRestart(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            f.unloaded(f.owner.getUUID(), 1, "kept-");
            String existing = f.helpers.owned(f.owner, "kept-1").getKey();
            String ambiguous = UUID.randomUUID().toString();
            var old = com.google.gson.JsonParser.parseString(CommunityServer.GSON.toJson(f.helpers.data)).getAsJsonObject();
            old.addProperty("format", 2);
            old.remove("guardedRemovals");
            old.getAsJsonArray("dismissed").add(ambiguous);
            Path path = f.directory.resolve("old-roster.json");
            Files.writeString(path, old.toString());
            var runtime = new AgentCompanions(f.helpers.server, path);
            c.assertTrue(runtime.data.agents.containsKey(existing) && runtime.data.dismissed.contains(ambiguous)
                && runtime.data.guardedRemovals.isEmpty() && !runtime.provenRemoved.contains(ambiguous),
                "A legacy tombstone is reserved but never treated as proof of dismissal");
            var first = f.saved(UUID.fromString(ambiguous));
            first.setHealth(37); first.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.31);
            runtime.load(first);
            c.assertTrue(!first.isRemoved() && !runtime.loaded.containsKey(first.getUUID())
                && ((AgentGoalAccess) first).infinity$getTargetSelector().getAvailableGoals().isEmpty()
                && first.getHealth() == 37 && first.getAttributeBaseValue(Attributes.MOVEMENT_SPEED) == .31,
                "An old ambiguous saved helper and its edited stats remain inert instead of being deleted");
            first.discard();
            runtime.save();
            var saved = AgentCompanions.read(path);
            c.assertTrue(saved.format == 3 && saved.agents.containsKey(existing) && saved.dismissed.contains(ambiguous) && saved.guardedRemovals.isEmpty(),
                "An ordinary save keeps the existing record and does not promote old tombstones");
            var restarted = new AgentCompanions(f.helpers.server, path);
            var second = f.saved(UUID.fromString(ambiguous)); restarted.load(second);
            c.assertTrue(!restarted.creationRecoveryRequired && !restarted.provenRemoved.contains(ambiguous)
                && !second.isRemoved() && !restarted.loaded.containsKey(second.getUUID()),
                "A later runtime still retains the ambiguous body without registering or deleting it");
            second.discard();
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=240)
    public void guardedRemovalProvenanceRoundTripsAndRejectsMalformedRecords(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            c.assertValueEqual(f.helpers.spawn(f.owner, "dismissed"), 1, "A new helper exists");
            String id = f.helpers.owned(f.owner, "dismissed").getKey();
            c.assertValueEqual(f.helpers.dismiss(f.owner, "dismissed"), 1, "Owner explicitly dismisses this helper");
            var saved = AgentCompanions.read(f.helpers.file);
            c.assertTrue(saved.dismissed.contains(id) && saved.guardedRemovals.contains(id),
                "The proven guarded removal records both its reserved identity and its provenance");
            var restarted = f.restart(); var copy = f.saved(UUID.fromString(id)); restarted.load(copy);
            c.assertTrue(restarted.provenRemoved.contains(id) && copy.isRemoved(), "The explicit guarded dismissal survives a fresh runtime");
            var json = com.google.gson.JsonParser.parseString(CommunityServer.GSON.toJson(saved)).getAsJsonObject();
            Path invalid = f.directory.resolve("invalid-provenance.json");
            json.remove("guardedRemovals"); Files.writeString(invalid, json.toString());
            boolean missing = false; try { AgentCompanions.read(invalid); } catch (IllegalStateException expected) { missing = true; }
            c.assertTrue(missing, "The new format refuses a missing provenance field");
            for (String field : List.of("agents", "dismissed", "pendingCreations")) {
                var incomplete = com.google.gson.JsonParser.parseString(CommunityServer.GSON.toJson(saved)).getAsJsonObject();
                incomplete.remove(field); Files.writeString(invalid, incomplete.toString());
                boolean rejected = false; try { AgentCompanions.read(invalid); } catch (IllegalStateException expected) { rejected = true; }
                c.assertTrue(rejected, "The new format refuses a missing " + field + " field");
            }
            json.addProperty("format", 2); json.add("guardedRemovals", com.google.gson.JsonParser.parseString("[\"" + id + "\"]"));
            Files.writeString(invalid, json.toString());
            boolean oldWithProvenance = false; try { AgentCompanions.read(invalid); } catch (IllegalStateException expected) { oldWithProvenance = true; }
            c.assertTrue(oldWithProvenance, "An old-format file cannot claim newer removal provenance");
            json.addProperty("format", 3);
            String canonical = "abcdef01-2222-4333-8444-555555555555";
            var malformed = new AgentCompanions.Data();
            malformed.agents.put(canonical.toUpperCase(java.util.Locale.ROOT), new AgentCompanions.Agent(f.owner.getStringUUID(), "case", AgentCompanions.Mode.STAY,
                "minecraft:overworld", 0, 64, 0));
            malformed.dismissed.add(canonical); malformed.guardedRemovals.add(canonical);
            byte[] ambiguousBytes = CommunityServer.GSON.toJson(malformed).getBytes(StandardCharsets.UTF_8); Files.write(invalid, ambiguousBytes);
            boolean collisionRejected = false; try { AgentCompanions.read(invalid); } catch (IllegalStateException expected) {
                collisionRejected = expected.getMessage().contains("matching whole-world backup") && !expected.getMessage().contains("Restore its .previous");
            }
            c.assertTrue(collisionRejected && Arrays.equals(ambiguousBytes, Files.readAllBytes(invalid)),
                "A noncanonical owner key cannot coexist with a canonical removal proof for the same UUID");
            malformed.agents.clear(); malformed.dismissed.clear(); malformed.guardedRemovals.clear();
            malformed.agents.put("1-1-1-1-1", new AgentCompanions.Agent(f.owner.getStringUUID(), "short", AgentCompanions.Mode.STAY,
                "minecraft:overworld", 0, 64, 0)); Files.writeString(invalid, CommunityServer.GSON.toJson(malformed));
            boolean shortRejected = false; try { AgentCompanions.read(invalid); } catch (IllegalStateException expected) { shortRejected = true; }
            c.assertTrue(shortRejected, "A shortened agent UUID is rejected");
            malformed.agents.clear();
            malformed.agents.put(canonical, new AgentCompanions.Agent("ABCDEF01-2222-4333-8444-555555555555", "owner-case", AgentCompanions.Mode.STAY,
                "minecraft:overworld", 0, 64, 0)); Files.writeString(invalid, CommunityServer.GSON.toJson(malformed));
            boolean ownerRejected = false; try { AgentCompanions.read(invalid); } catch (IllegalStateException expected) { ownerRejected = true; }
            c.assertTrue(ownerRejected, "A noncanonical owner UUID is rejected");
            for (String text : List.of(
                "{\"format\":3,\"format\":3,\"agents\":{},\"dismissed\":[],\"guardedRemovals\":[],\"pendingCreations\":[]}",
                "{\"format\":3,\"agents\":{},\"agents\":{},\"dismissed\":[],\"guardedRemovals\":[],\"pendingCreations\":[]}",
                "{format:3,\"agents\":{},\"dismissed\":[],\"guardedRemovals\":[],\"pendingCreations\":[]}",
                "{/*comment*/\"format\":3,\"agents\":{},\"dismissed\":[],\"guardedRemovals\":[],\"pendingCreations\":[]}",
                "{\"format\":3.0,\"agents\":{},\"dismissed\":[],\"guardedRemovals\":[],\"pendingCreations\":[]}",
                "{\"format\":3e0,\"agents\":{},\"dismissed\":[],\"guardedRemovals\":[],\"pendingCreations\":[]}",
                "{\"format\":\"3\",\"agents\":{},\"dismissed\":[],\"guardedRemovals\":[],\"pendingCreations\":[]}",
                "{\"format\":4,\"agents\":{},\"dismissed\":[],\"guardedRemovals\":[],\"pendingCreations\":[]}")) {
                byte[] badBytes = text.getBytes(StandardCharsets.UTF_8); Files.write(invalid, badBytes);
                boolean rejected = false; try { AgentCompanions.read(invalid); } catch (IllegalStateException expected) { rejected = true; }
                c.assertTrue(rejected && Arrays.equals(badBytes, Files.readAllBytes(invalid)), "Duplicate or lenient JSON is rejected without rewriting it");
            }
            String validAgent = CommunityServer.GSON.toJson(new AgentCompanions.Agent(f.owner.getStringUUID(), "duplicate", AgentCompanions.Mode.STAY,
                "minecraft:overworld", 0, 64, 0));
            String duplicatedOwner = "{\"owner\":\"" + f.owner.getStringUUID() + "\"," + validAgent.substring(1);
            String nestedDuplicate = "{\"format\":3,\"agents\":{\"" + canonical + "\":" + duplicatedOwner
                + "},\"dismissed\":[],\"guardedRemovals\":[],\"pendingCreations\":[]}";
            Files.writeString(invalid, nestedDuplicate); boolean nestedRejected = false;
            try { AgentCompanions.read(invalid); } catch (IllegalStateException expected) { nestedRejected = true; }
            c.assertTrue(nestedRejected && Files.readString(invalid).equals(nestedDuplicate), "A duplicated nested owner field is rejected without rewriting it");
            String nested = "{\"x\":".repeat(66) + "null" + "}".repeat(66);
            Files.writeString(invalid, nested); boolean deepRejected = false;
            try { AgentCompanions.read(invalid); } catch (IllegalStateException expected) { deepRejected = true; }
            c.assertTrue(deepRejected && Files.readString(invalid).equals(nested), "Excessive nesting is rejected without rewriting it");
            byte[] invalidUtf8 = new byte[]{'{', (byte) 0xc3, (byte) 0x28, '}'}; Files.write(invalid, invalidUtf8);
            boolean encodingRejected = false; try { AgentCompanions.read(invalid); } catch (IllegalStateException expected) { encodingRejected = true; }
            c.assertTrue(encodingRejected && Arrays.equals(invalidUtf8, Files.readAllBytes(invalid)), "Malformed UTF-8 is rejected without rewriting it");
            for (String bad : List.of("null", "[\"" + id + "\",\"" + id + "\"]", "[\"" + UUID.randomUUID() + "\"]", "[null]")) {
                json.add("guardedRemovals", com.google.gson.JsonParser.parseString(bad)); Files.writeString(invalid, json.toString());
                boolean rejected = false; try { AgentCompanions.read(invalid); } catch (IllegalStateException expected) { rejected = true; }
                c.assertTrue(rejected, "Malformed, duplicate or unrelated removal provenance fails closed: " + bad);
            }
            var oldest = UUID.randomUUID().toString(); var firstGuarded = UUID.randomUUID().toString();
            f.helpers.data.dismissed.clear(); f.helpers.data.guardedRemovals.clear();
            f.helpers.data.dismissed.add(oldest); f.helpers.rememberRemoved(firstGuarded);
            for (int n=1; n<AgentCompanions.DISMISSED_LIMIT; n++) f.helpers.rememberRemoved(UUID.randomUUID().toString());
            c.assertTrue(!f.helpers.data.dismissed.contains(oldest) && f.helpers.data.guardedRemovals.contains(firstGuarded),
                "Evicting an older ambiguous tombstone does not evict an unrelated guarded removal");
            f.helpers.rememberRemoved(UUID.randomUUID().toString());
            c.assertTrue(!f.helpers.data.dismissed.contains(firstGuarded) && !f.helpers.data.guardedRemovals.contains(firstGuarded)
                && f.helpers.data.dismissed.containsAll(f.helpers.data.guardedRemovals), "Evicting a guarded identity also drops its provenance");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=400)
    public void dismissalWithoutAnEstablishedMarkerChangesNothingEvenWhenTheRosterWriteWouldBeAmbiguous(GameTestHelper c) throws Exception {
        // The reported data-loss sequence: rename succeeds, directory sync fails,
        // and the marker cannot be written. The marker now comes first, so the
        // roster write is never reached.
        for (boolean loadedTarget : new boolean[]{true, false}) {
            var io = new FaultyIo();
            try (var f = new Fixture(c, io)) {
                f.unloaded(f.owner.getUUID(), 1, "away-"); f.helpers.spawn(f.owner, "near");
                String name = loadedTarget ? "near" : "away-1";
                var entry = f.helpers.owned(f.owner, name); String id = entry.getKey(); var live = f.helpers.loaded.get(UUID.fromString(id));
                byte[] roster = Files.readAllBytes(f.helpers.file), previous = Files.readAllBytes(f.previousFile());
                io.arm("sync-after:move:agents.json", true); io.arm("write:agents.json.recovery-required.tmp", false);
                c.assertValueEqual(f.helpers.dismiss(f.owner, name), 0, "Without an established marker the dismissal is refused: " + name);
                c.assertTrue(Arrays.equals(roster, Files.readAllBytes(f.helpers.file)) && Arrays.equals(previous, Files.readAllBytes(f.previousFile())),
                    "The roster and .previous were never touched: " + name);
                c.assertTrue(f.helpers.data.agents.get(id) == entry.getValue() && !f.helpers.creationRecoveryRequired && Files.notExists(f.marker()),
                    "The exact record is kept and nothing is frozen, since nothing is uncertain: " + name);
                f.helpers.controlLoaded();
                if (live != null) c.assertTrue(live.isAlive() && !live.isRemoved() && f.helpers.loaded.get(live.getUUID()) == live, "The loaded helper is kept through a tick pass");
                var restarted = f.restart(); var copy = f.saved(UUID.fromString(id)); restarted.load(copy);
                c.assertTrue(!restarted.creationRecoveryRequired && !copy.isRemoved() && restarted.loaded.get(copy.getUUID()) == copy,
                    "After restart the saved copy still has its record and registers normally: " + name);
                copy.discard();
            }
        }
        for (var markerFault : List.of(Map.entry("move:agents.json.recovery-required", true),
                Map.entry("sync-after:move:agents.json.recovery-required", true), Map.entry("move:agents.json.recovery-required", false))) {
            var io = new FaultyIo(); String label = markerFault.toString();
            try (var f = new Fixture(c, io)) {
                f.helpers.spawn(f.owner, "near"); var entry = f.helpers.owned(f.owner, "near"); var live = f.helpers.loaded.get(UUID.fromString(entry.getKey()));
                byte[] roster = Files.readAllBytes(f.helpers.file);
                io.arm(markerFault.getKey(), markerFault.getValue());
                c.assertValueEqual(f.helpers.dismiss(f.owner, "near"), 0, "An uncertain marker write refuses the dismissal: " + label);
                c.assertTrue(Arrays.equals(roster, Files.readAllBytes(f.helpers.file)) && f.helpers.data.agents.get(entry.getKey()) == entry.getValue()
                    && live.isAlive() && !live.isRemoved(), "The roster, record and helper are unchanged: " + label);
                c.assertTrue(f.helpers.creationRecoveryRequired && HelperPersistence.recoveryRequired(f.helpers.file)
                    && f.restart().creationRecoveryRequired,
                    "A final or temporary note freezes now and after restart: " + label);
                if (markerFault.getKey().startsWith("move:") && !markerFault.getValue())
                    c.assertTrue(Files.notExists(f.marker()) && Files.exists(HelperPersistence.recoveryTemporary(f.helpers.file)),
                        "A failed rename preserves its temporary note: " + label);
            }
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=400)
    public void failedMarkerRetirementAfterAProvenDismissalIsSafeOnRestart(GameTestHelper c) throws Exception {
        record Case(String name, boolean secondNoteFails, boolean frozenAfterRestart) {}
        for (var test : List.of(new Case("delete refused", false, true), new Case("delete unsynced, note rewritten", false, true),
                new Case("delete unsynced, note lost", true, false))) {
            var io = new FaultyIo();
            try (var f = new Fixture(c, io)) {
                f.helpers.spawn(f.owner, "near"); String id = f.helpers.owned(f.owner, "near").getKey(); var live = f.helpers.loaded.get(UUID.fromString(id));
                if (test.name.startsWith("delete refused")) io.arm("delete:agents.json.recovery-required", false);
                else io.arm("sync-after:delete:agents.json.recovery-required", true);
                // Skip the guard's own marker write; fail only the note freeze() tries to rewrite.
                if (test.secondNoteFails) io.armLater("write:agents.json.recovery-required.tmp", false, 1);
                c.assertValueEqual(f.helpers.dismiss(f.owner, "near"), 1, "The dismissal itself was proven durable, so it is reported: " + test.name);
                var saved = AgentCompanions.read(f.helpers.file);
                c.assertTrue(!saved.agents.containsKey(id) && saved.dismissed.contains(id), "The saved roster durably records the dismissal: " + test.name);
                c.assertTrue(f.helpers.creationRecoveryRequired, "An unretired marker freezes this runtime: " + test.name);
                c.assertTrue(!live.isRemoved() && !f.helpers.loaded.containsKey(live.getUUID()), "While frozen the body is kept inert rather than deleted: " + test.name);
                f.helpers.controlLoaded();
                c.assertFalse(live.isRemoved(), "A tick pass does not delete it: " + test.name);
                var restarted = f.restart(); var copy = f.saved(UUID.fromString(id)); restarted.load(copy);
                c.assertValueEqual(restarted.creationRecoveryRequired, test.frozenAfterRestart, "Restart is frozen exactly while a marker exists: " + test.name);
                c.assertValueEqual(copy.isRemoved(), !test.frozenAfterRestart,
                    "A copy is retired only when unfrozen, and then only by the proven dismissal the player was told about: " + test.name);
                copy.discard(); live.discard();
            }
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=240)
    public void nativeDeathAttemptsNoMarkerWriteAndLeavesAnArmedMarkerFaultForTheNextDismissal(GameTestHelper c) throws Exception {
        // Death no longer guards a removal, so it must never touch the marker.
        // The armed marker fault then belongs to the next intentional dismissal.
        for (boolean ambiguous : new boolean[]{false, true}) {
            var io = new FaultyIo(); String label = ambiguous ? "marker rename may have happened" : "marker write refused";
            try (var f = new Fixture(c, io)) {
                f.helpers.spawn(f.owner, "doomed"); var entry = f.helpers.owned(f.owner, "doomed"); String id = entry.getKey();
                var entity = f.helpers.loaded.get(UUID.fromString(id));
                f.helpers.spawn(f.owner, "kept"); var kept = f.helpers.owned(f.owner, "kept");
                f.legacy(id);
                byte[] roster = Files.readAllBytes(f.helpers.file);
                if (ambiguous) io.arm("move:agents.json.recovery-required", true); else io.arm("write:agents.json.recovery-required.tmp", false);
                c.assertTrue(entity.hurtServer(c.getLevel(), entity.damageSources().genericKill(), 10000) && !entity.isAlive(), "A real native death occurs: " + label);
                c.assertTrue(!f.helpers.creationRecoveryRequired && Files.notExists(f.marker()) && Arrays.equals(roster, Files.readAllBytes(f.helpers.file))
                    && f.helpers.data.agents.get(id) == entry.getValue() && !f.helpers.data.dismissed.contains(id),
                    "Death writes no marker or roster, keeps the record and does not freeze: " + label);
                for (var helpers : List.of(f.helpers, f.restart())) {
                    var copy = f.saved(UUID.fromString(id)); helpers.load(copy);
                    c.assertTrue(!copy.isRemoved() && helpers.loaded.get(copy.getUUID()) == copy, "A later saved copy survives and keeps its record: " + label);
                    helpers.loaded.remove(copy.getUUID(), copy); copy.discard();
                }
                c.assertValueEqual(f.helpers.dismiss(f.owner, "kept"), 0, "The still-armed marker fault refuses the next dismissal: " + label);
                c.assertTrue(f.helpers.data.agents.get(kept.getKey()) == kept.getValue() && Arrays.equals(roster, Files.readAllBytes(f.helpers.file)),
                    "The refused dismissal keeps the helper and the roster: " + label);
                c.assertValueEqual(f.helpers.creationRecoveryRequired, ambiguous, "Only a marker that may exist freezes: " + label);
                if (ambiguous) c.assertTrue(f.restart().creationRecoveryRequired, "The marker that may exist keeps a restart frozen");
            }
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=240)
    public void preExistingNonpendingHelperDeathPreservesExactRosterAndSlotUntilExplicitDismissal(GameTestHelper c) throws Exception {
        var wire = new NativePlayConnection(c.getLevel().registryAccess());
        try (var f = new Fixture(c, HelperPersistence.NATIVE, wire)) {
            // A roster record written before pending-creation tracking, with a real body loaded natively.
            f.unloaded(f.owner.getUUID(), 1, "veteran-"); var entry = f.helpers.owned(f.owner, "veteran-1"); String id = entry.getKey(); var record = entry.getValue();
            c.assertValueEqual(f.helpers.spawn(f.owner, "other"), 1, "An unrelated helper is created");
            var other = f.helpers.loaded.get(UUID.fromString(f.helpers.owned(f.owner, "other").getKey()));
            other.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.43);
            var body = f.saved(UUID.fromString(id));
            c.assertTrue(c.getLevel().addFreshEntity(body) && f.helpers.loaded.get(body.getUUID()) == body, "Native ENTITY_LOAD registers the pre-existing helper");
            c.assertFalse(f.helpers.unresolvedCreation(id), "The pre-existing helper is not a pending creation");
            body.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1500); body.setHealth(900); body.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.31);
            // What an unseen saved chunk copy of this helper could hold.
            var stale = persisted(body);
            byte[] roster = Files.readAllBytes(f.helpers.file), previous = Files.readAllBytes(f.previousFile());
            var disk = AgentCompanions.read(f.helpers.file);
            int count = f.helpers.count(f.owner), remaining = f.helpers.remaining(f.owner);
            wire.drain();
            c.assertTrue(body.hurtServer(c.getLevel(), body.damageSources().genericKill(), 10000) && !body.isAlive(), "A real native death occurs");
            c.assertTrue(!f.helpers.creationRecoveryRequired && Files.notExists(f.marker()), "Death does not freeze or leave a marker");
            c.assertTrue(Arrays.equals(roster, Files.readAllBytes(f.helpers.file)) && Arrays.equals(previous, Files.readAllBytes(f.previousFile())),
                "The exact roster and .previous bytes are unchanged");
            var after = AgentCompanions.read(f.helpers.file);
            c.assertTrue(after.agents.equals(disk.agents) && after.pendingCreations.equals(disk.pendingCreations) && after.dismissed.equals(disk.dismissed)
                && !after.dismissed.contains(id) && !after.pendingCreations.contains(id), "On-disk records, pending and dismissed lists are exactly preserved with no tombstone");
            c.assertTrue(f.helpers.data.agents.get(id) == record && !f.helpers.loaded.containsKey(body.getUUID())
                && f.helpers.count(f.owner) == count && f.helpers.remaining(f.owner) == remaining, "The exact record object and slot stay reserved");
            c.assertTrue(chat(wire).stream().anyMatch(text -> text.contains("veteran-1") && text.contains("still uses a helper slot")),
                "The online owner is told the identity remains reserved pending host review");
            c.assertValueEqual(f.helpers.mode(f.owner, "other", AgentCompanions.Mode.GUARD), 1, "Unrelated helper controls stay writable");
            c.assertTrue(other.isAlive() && other.getAttributeBaseValue(Attributes.MOVEMENT_SPEED) == .43, "The unrelated helper and its edited stat are intact");

            // The dead body finishes its native removal; then the saved copy loads.
            body.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
            var copy = EntityTypes.IRON_GOLEM.create(c.getLevel(), EntitySpawnReason.LOAD);
            copy.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, copy.registryAccess(), stale));
            c.assertTrue(copy.getUUID().equals(body.getUUID()) && c.getLevel().addFreshEntity(copy), "A tagged saved copy enters the world by native load");
            c.assertTrue(!copy.isRemoved() && f.helpers.loaded.get(copy.getUUID()) == copy && !f.helpers.creationRecoveryRequired,
                "The copy survives and registers under the preserved record");
            c.assertTrue(copy.getAttributeBaseValue(Attributes.MAX_HEALTH) == 1500 && copy.getHealth() == 900 && copy.getAttributeBaseValue(Attributes.MOVEMENT_SPEED) == .31,
                "Its edited stats are reachable again");
            var restarted = f.restart(); var later = f.saved(UUID.fromString(id)); restarted.load(later);
            c.assertTrue(!restarted.creationRecoveryRequired && restarted.data.agents.equals(f.helpers.data.agents) && !restarted.unresolvedCreation(id)
                && !later.isRemoved() && restarted.loaded.get(later.getUUID()) == later, "After restart the identity and a saved copy are still kept");
            restarted.loaded.remove(later.getUUID(), later); later.discard();

            c.assertValueEqual(f.helpers.dismiss(f.owner, "veteran-1"), 1, "Explicit owner dismissal still applies to the selected identity");
            c.assertTrue(copy.isRemoved() && !f.helpers.data.agents.containsKey(id) && AgentCompanions.read(f.helpers.file).dismissed.contains(id)
                && f.helpers.count(f.owner) == count - 1, "Dismissal removes the body and record, records proof and frees the slot");
            var dismissedCopy = f.saved(UUID.fromString(id)); f.restart().load(dismissedCopy);
            c.assertTrue(dismissedCopy.isRemoved(), "A later copy of a dismissed identity is retired, as the owner chose");
        } finally { wire.channel.finishAndReleaseAll(); }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=240)
    public void insertionRollbackDeletesOnlyAfterAProvenGuardedRemovalAndKeepsHelpersOtherwise(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            f.helpers.spawn(f.owner, "kept"); var before = Map.copyOf(f.helpers.data.agents);
            var inserted = new AtomicReference<UUID>();
            var fault = new InsertionFailure(f.helpers, f.owner.getUUID(), f.directory.resolve("unrelated-fault"), inserted);
            c.assertTrue(INSERTION_FAILURE.compareAndSet(null, fault), "The private exact-owner fault is armed once");
            int created;
            try { created = f.helpers.spawnBatch(f.owner, 3, AgentCompanions.Profile.REGULAR); }
            finally { INSERTION_FAILURE.compareAndSet(fault, null); }
            c.assertValueEqual(created, 0, "A refused insertion reports no success");
            c.assertTrue(f.helpers.data.agents.equals(before) && !f.helpers.creationRecoveryRequired && Files.notExists(f.marker()),
                "A proven guarded rollback restores the roster, retires its marker and stays writable");
            c.assertTrue(inserted.get() != null && c.getLevel().getEntity(inserted.get()) == null, "Only after the proven rollback is the new helper removed");
            var copy = f.saved(inserted.get()); f.restart().load(copy);
            c.assertTrue(copy.isRemoved(), "Its durable removal proof retires a stale copy after restart");
        }
        var wire = new NativePlayConnection(c.getLevel().registryAccess());
        try (var f = new Fixture(c, HelperPersistence.NATIVE, wire)) {
            f.helpers.spawn(f.owner, "kept"); var before = Map.copyOf(f.helpers.data.agents);
            var inserted = new AtomicReference<UUID>();
            Path markerTemporary = f.marker().resolveSibling(f.marker().getFileName() + ".tmp");
            var fault = new InsertionFailure(f.helpers, f.owner.getUUID(), markerTemporary, inserted);
            c.assertTrue(INSERTION_FAILURE.compareAndSet(null, fault), "The private exact-owner fault is armed once");
            int created;
            wire.drain();
            try { created = f.helpers.spawnBatch(f.owner, 3, AgentCompanions.Profile.REGULAR); }
            finally { INSERTION_FAILURE.compareAndSet(fault, null); }
            c.assertTrue(created == 0 && f.helpers.creationRecoveryRequired,
                "Without a marker the rollback is not attempted; the call returns no success without throwing and this runtime freezes");
            assertUnresolvedNotice(c, chat(wire), "batch, marker refused");
            var saved = AgentCompanions.read(f.helpers.file);
            c.assertTrue(saved.agents.keySet().containsAll(before.keySet()) && saved.agents.size() == before.size() + 3 && saved.dismissed.isEmpty(),
                "The saved roster still owns every staged helper and holds no removal proof");
            var partial = c.getLevel().getEntity(inserted.get());
            c.assertTrue(partial == null || (!partial.isRemoved() && f.helpers.loaded.get(inserted.get()) == partial), "A new helper that reached the world is kept");
            var restarted = f.restart(); var copy = f.saved(inserted.get()); restarted.load(copy);
            c.assertTrue(restarted.creationRecoveryRequired && Files.isDirectory(markerTemporary)
                && !copy.isRemoved() && restarted.loaded.get(copy.getUUID()) == copy,
                "The temporary recovery evidence freezes restart and the saved copy keeps its record");
            copy.discard();
        } finally { wire.channel.finishAndReleaseAll(); }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=240)
    public void unresolvedCreationNeverThrowsAndEveryCallingPathReportsPossiblePresence(GameTestHelper c) throws Exception {
        // Single command API, Java chest, Bedrock form and the batch request shared by Java and Bedrock.
        for (String path : List.of("single", "java menu", "bedrock form", "batch request")) {
            var wire = new NativePlayConnection(c.getLevel().registryAccess());
            try (var f = new Fixture(c, HelperPersistence.NATIVE, wire)) {
                f.unloaded(f.owner.getUUID(), 1, "older-"); var older = f.helpers.owned(f.owner, "older-1");
                // The creation save copies this roster to .previous; the rollback must not replace that copy.
                byte[] preCreation = Files.readAllBytes(f.helpers.file);
                var touch = new Touch();
                if (path.equals("bedrock form")) { BedrockAgentMenu.open(f.owner, touch); touch.click("Create another helper"); }
                if (path.equals("java menu")) c.assertValueEqual(AgentMenu.open(f.owner), 1, "The Java helper chest opens");
                var request = path.equals("batch request") ? new HelperBatchMenu.Request(f.owner) : null;
                var inserted = new AtomicReference<UUID>(); Path blocked = HelperPersistence.temporaryFile(f.helpers.file);
                var fault = new InsertionFailure(f.helpers, f.owner.getUUID(), blocked, inserted);
                c.assertTrue(INSERTION_FAILURE.compareAndSet(null, fault), "The private exact-owner fault is armed once: " + path);
                int result = -1;
                wire.drain();
                try {
                    switch (path) {
                        case "single" -> result = f.helpers.spawn(f.owner, "helper-solo");
                        case "java menu" -> f.owner.containerMenu.clicked(AgentMenu.helperSlot(1), 0, net.minecraft.world.inventory.ContainerInput.PICKUP, f.owner);
                        case "bedrock form" -> touch.click("Create: Regular");
                        default -> result = request.spawn(f.owner, 2, AgentCompanions.Profile.REGULAR);
                    }
                } finally { INSERTION_FAILURE.compareAndSet(fault, null); }
                c.assertTrue(inserted.get() != null, "The real insertion failed after the ownership save: " + path);
                if (result != -1) c.assertValueEqual(result, 0, "The API reports no success, without throwing: " + path);
                assertUnresolvedNotice(c, chat(wire), path);
                if (path.equals("bedrock form"))
                    c.assertTrue(touch.last().content().contains("may have appeared") && !touch.last().content().contains("No helper appeared"),
                        "The Bedrock form shows the unresolved outcome rather than an absent helper");
                c.assertTrue(f.helpers.creationUnresolved && f.helpers.creationRecoveryRequired && Files.exists(f.marker()) && f.restart().creationRecoveryRequired,
                    "The outcome is recorded as unresolved and ownership fails closed now and after restart: " + path);
                var staged = new ArrayList<>(f.helpers.data.agents.keySet()); staged.remove(older.getKey());
                c.assertTrue(f.helpers.data.agents.get(older.getKey()) == older.getValue()
                    && staged.size() == (path.equals("batch request") ? 2 : 1) && staged.contains(inserted.get().toString()),
                    "The older unloaded record and every ambiguous staged record are kept: " + path);
                var saved = AgentCompanions.read(f.helpers.file);
                c.assertTrue(saved.agents.keySet().containsAll(staged) && saved.pendingCreations.containsAll(staged) && saved.dismissed.isEmpty(),
                    "Disk still owns the ambiguous helpers as pending and holds no removal proof: " + path);
                var partial = c.getLevel().getEntity(inserted.get());
                c.assertTrue(partial == null || (!partial.isRemoved() && f.helpers.loaded.get(inserted.get()) == partial),
                    "A helper that reached the world is kept and registered, never deleted: " + path);
                c.assertTrue(Arrays.equals(preCreation, Files.readAllBytes(f.previousFile())), "The pre-creation roster stays in .previous: " + path);
                Files.delete(blocked.resolve("block-rollback")); Files.delete(blocked);
            } finally { wire.channel.finishAndReleaseAll(); }
        }
        // A proven rollback whose marker cannot be retired: helpers are kept inert and shielded, not discarded.
        var io = new FaultyIo(); var wire = new NativePlayConnection(c.getLevel().registryAccess());
        try (var f = new Fixture(c, io, wire)) {
            var inserted = new AtomicReference<UUID>();
            var fault = new InsertionFailure(f.helpers, f.owner.getUUID(), f.directory.resolve("unrelated-fault"), inserted);
            io.arm("delete:agents.json.recovery-required", false);
            c.assertTrue(INSERTION_FAILURE.compareAndSet(null, fault), "The private exact-owner fault is armed once");
            int created;
            wire.drain();
            try { created = f.helpers.spawnBatch(f.owner, 2, AgentCompanions.Profile.REGULAR); }
            finally { INSERTION_FAILURE.compareAndSet(fault, null); }
            c.assertValueEqual(created, 0, "A stuck marker after a proven rollback still reports no success, without throwing");
            assertUnresolvedNotice(c, chat(wire), "batch, marker retirement refused");
            c.assertTrue(f.helpers.creationUnresolved && f.helpers.creationRecoveryRequired && Files.exists(f.marker()),
                "The outcome is unresolved even though memory no longer lists the helpers, and the kept marker freezes ownership");
            var partial = c.getLevel().getEntity(inserted.get());
            try {
                c.assertTrue(partial == null || (!partial.isRemoved() && !f.helpers.loaded.containsKey(inserted.get())
                    && AgentCompanions.retainedHelperShielded((IronGolem) partial, ((IronGolem) partial).damageSources().playerAttack(f.owner))),
                    "A helper that reached the world is unregistered, kept inert and shielded rather than discarded");
            } finally { if (partial != null) partial.discard(); }
        } finally { wire.channel.finishAndReleaseAll(); }
        c.succeed();
    }

    private static List<Map.Entry<String, net.minecraft.world.damagesource.DamageSource>> ordinaryDamage(IronGolem golem, ServerPlayer player, net.minecraft.world.entity.LivingEntity mob) {
        var sources = golem.damageSources();
        return List.of(Map.entry("player", sources.playerAttack(player)), Map.entry("mob", sources.mobAttack(mob)),
            Map.entry("lava", sources.lava()), Map.entry("fire", sources.inFire()), Map.entry("cactus", sources.cactus()), Map.entry("generic", sources.generic()));
    }
    /**
     * Each hit on an entity is ten more than its previous one. During the native
     * damage cooldown only a larger hit applies (by the difference), so every
     * test hit would really land unless something else refuses it.
     */
    private static final Map<net.minecraft.world.entity.LivingEntity, Float> LAST_HIT = new java.util.WeakHashMap<>();
    private static float nextHit(net.minecraft.world.entity.LivingEntity entity) { return LAST_HIT.merge(entity, 10f, Float::sum); }
    private static void assertShielded(GameTestHelper c, IronGolem golem, List<Map.Entry<String, net.minecraft.world.damagesource.DamageSource>> sources, String label) {
        for (var source : sources) {
            float health = golem.getHealth();
            c.assertTrue(AgentCompanions.retainedHelperShielded(golem, source.getValue()), "The runtime shield applies to " + source.getKey() + " damage: " + label);
            c.assertFalse(golem.hurtServer(c.getLevel(), source.getValue(), nextHit(golem)), "Native " + source.getKey() + " damage is refused: " + label);
            c.assertTrue(golem.isAlive() && golem.getHealth() == health, "Health is unchanged by " + source.getKey() + " damage: " + label);
        }
        c.assertFalse(golem.isInvulnerable(), "No persistent invulnerability flag is set: " + label);
        c.assertFalse(AgentCompanions.retainedHelperShielded(golem, golem.damageSources().genericKill()),
            "Known limit: an operator /kill is not shielded: " + label);
        c.assertFalse(golem.getY() >= c.getLevel().getMinY() - 64 && AgentCompanions.retainedHelperShielded(golem, golem.damageSources().fellOutOfWorld()),
            "Out-of-world damage above the native void threshold is not treated as natural: " + label);
    }
    private static void assertVulnerable(GameTestHelper c, IronGolem golem, List<Map.Entry<String, net.minecraft.world.damagesource.DamageSource>> sources, String label) {
        for (var source : sources) {
            float health = golem.getHealth();
            c.assertFalse(AgentCompanions.retainedHelperShielded(golem, source.getValue()), "No shield for " + source.getKey() + " damage: " + label);
            c.assertTrue(golem.hurtServer(c.getLevel(), source.getValue(), nextHit(golem)) && golem.getHealth() < health,
                "Native " + source.getKey() + " damage still reaches a normal registered helper: " + label);
        }
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=240)
    public void retainedAndFrozenHelpersIgnoreOrdinaryDamageWhileNormalHelpersStayVulnerable(GameTestHelper c) throws Exception {
        var io = new FaultyIo();
        try (var f = new Fixture(c, io)) {
            var zombie = EntityTypes.ZOMBIE.create(c.getLevel(), EntitySpawnReason.COMMAND);
            var retained = f.saved(UUID.randomUUID());
            try {
                c.assertValueEqual(f.helpers.spawn(f.owner, "normal"), 1, "A normal registered helper is created");
                var normal = f.helpers.loaded.get(UUID.fromString(f.helpers.owned(f.owner, "normal").getKey()));
                // An unknown tagged helper enters the world by the real native load callback.
                c.assertTrue(c.getLevel().addFreshEntity(retained) && !retained.isRemoved() && !f.helpers.loaded.containsKey(retained.getUUID()),
                    "An unknown tagged helper is kept inert and unregistered for inspection");
                double retainedMax = retained.getAttributeBaseValue(Attributes.MAX_HEALTH);
                assertShielded(c, retained, ordinaryDamage(retained, f.owner, zombie), "unknown retained helper");
                assertVulnerable(c, normal, ordinaryDamage(normal, f.owner, zombie), "writable runtime");
                f.helpers.unload(retained); f.helpers.load(retained);
                assertShielded(c, retained, ordinaryDamage(retained, f.owner, zombie), "after an unload and reload");
                var restarted = f.restart();
                AgentCompanions.INSTANCES.put(f.helpers.server, restarted);
                try {
                    restarted.load(retained); restarted.load(normal);
                    assertShielded(c, retained, ordinaryDamage(retained, f.owner, zombie), "after a restart");
                    assertVulnerable(c, normal, ordinaryDamage(normal, f.owner, zombie), "registered again after a restart");
                } finally { AgentCompanions.INSTANCES.put(f.helpers.server, f.helpers); }
                c.assertFalse(AgentCompanions.allowDamage(f.owner, retained.damageSources().mobAttack(retained)), "A retained helper still cannot deal damage");
                normal.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1500);
                io.arm("move:agents.json", true);
                c.assertValueEqual(f.helpers.mode(f.owner, "normal", AgentCompanions.Mode.STAY), 0, "An ambiguous save freezes ownership");
                c.assertTrue(f.helpers.creationRecoveryRequired && f.helpers.loaded.get(normal.getUUID()) == normal, "The helper stays registered while frozen");
                assertShielded(c, normal, ordinaryDamage(normal, f.owner, zombie), "registered helper while frozen");
                assertShielded(c, retained, ordinaryDamage(retained, f.owner, zombie), "retained helper while frozen");
                c.assertTrue(normal.getAttributeBaseValue(Attributes.MAX_HEALTH) == 1500 && retained.getAttributeBaseValue(Attributes.MAX_HEALTH) == retainedMax,
                    "The shield never overwrites edited or default stats");
            } finally { retained.discard(); zombie.discard(); }
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=240)
    public void firstBootIsWritableButAMissingRosterBesidePriorEvidenceFreezesAndKeepsTheBackup(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            var server = f.helpers.server;
            Path fresh = f.directory.resolve("fresh").resolve("agents.json"); Files.createDirectories(fresh.getParent());
            var first = new AgentCompanions(server, fresh);
            c.assertTrue(!first.creationRecoveryRequired && first.data.agents.isEmpty() && first.remaining(f.owner) == AgentCompanions.LIMIT,
                "An empty world's first boot is writable with an empty roster");
            first.save();
            c.assertTrue(Files.exists(fresh) && Files.notExists(HelperPersistence.recoveryMarker(fresh)) && !first.creationRecoveryRequired,
                "Its first save creates the roster without a recovery note");

            c.assertValueEqual(f.helpers.spawn(f.owner, "kept"), 1, "A real helper is created");
            String id = f.helpers.owned(f.owner, "kept").getKey();
            c.assertValueEqual(f.helpers.mode(f.owner, "kept", AgentCompanions.Mode.STAY), 1, "A later save rotates the owning roster into .previous");
            c.assertTrue(AgentCompanions.read(f.previousFile()).agents.containsKey(id), ".previous owns the helper");
            byte[] backup = Files.readAllBytes(f.previousFile());
            Files.delete(f.helpers.file);
            var lost = f.restart();
            c.assertTrue(lost.creationRecoveryRequired && lost.data.agents.isEmpty() && lost.remaining(f.owner) == 0,
                "A missing roster beside its backup is frozen, not a fresh world");
            lost.spawn(f.owner, "replacement");
            c.assertValueEqual(lost.spawnBatch(f.owner, 1, AgentCompanions.Profile.REGULAR), 0, "Batch creation is refused");
            boolean refused = false;
            try { lost.save(); } catch (IllegalStateException expected) { refused = true; }
            c.assertTrue(refused && lost.owned(f.owner, "replacement") == null && Files.notExists(f.helpers.file) && Files.notExists(f.marker())
                && Arrays.equals(backup, Files.readAllBytes(f.previousFile())),
                "No creation or save writes a new roster, replaces the backup bytes or adds a note");
            var copy = f.saved(UUID.fromString(id)); lost.load(copy);
            c.assertTrue(!copy.isRemoved() && !lost.loaded.containsKey(copy.getUUID()), "A helper owned only by the backup is kept inert, never deleted");
            copy.discard();
            c.assertTrue(f.restart().creationRecoveryRequired && Arrays.equals(backup, Files.readAllBytes(f.previousFile())),
                "Every restart stays frozen; nothing is restored or deleted automatically");

            for (String suffix : List.of(".recovery-required", ".recovery-required.tmp", ".tmp")) {
                Path roster = f.directory.resolve("evidence" + suffix).resolve("agents.json"); Files.createDirectories(roster.getParent());
                Path left = roster.resolveSibling("agents.json" + suffix); Files.writeString(left, "left by an earlier interrupted save");
                var helpers = new AgentCompanions(server, roster);
                helpers.spawn(f.owner, "replacement");
                c.assertTrue(helpers.creationRecoveryRequired && helpers.owned(f.owner, "replacement") == null && Files.notExists(roster)
                    && Files.readString(left).equals("left by an earlier interrupted save"),
                    "A missing roster beside " + suffix + " is frozen and nothing is written over that evidence");
            }

            Path locked = f.directory.resolve("locked"), hidden = locked.resolve("agents.json"); Files.createDirectories(locked);
            try {
                Files.setPosixFilePermissions(locked, java.util.Set.of());
                // Skipped where permissions cannot hide existence (for example, as root).
                if (!Files.exists(hidden, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.notExists(hidden, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    c.assertTrue(new AgentCompanions(server, hidden).creationRecoveryRequired, "An undeterminable roster existence is not a fresh world");
            } catch (UnsupportedOperationException unsupported) {
                // Storage without POSIX permissions cannot model this case.
            } finally {
                try { Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
                catch (UnsupportedOperationException unsupported) {}
            }

            Path linked = f.directory.resolve("linked").resolve("agents.json"); Files.createDirectories(linked.getParent());
            boolean link;
            try { Files.createSymbolicLink(linked, linked.resolveSibling("missing-target.json")); link = true; }
            catch (UnsupportedOperationException | IOException | SecurityException unsupported) { link = false; }
            if (link) {
                boolean failed = false;
                try { new AgentCompanions(server, linked); } catch (IllegalStateException expected) { failed = true; }
                c.assertTrue(failed, "A dangling roster link fails closed like an unreadable roster rather than reading as empty");
            }
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void sameSessionPendingCreationShowsUnverifiedDiskDurabilityThroughUnloadWithoutClaimingARestart(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            f.unloaded(f.owner.getUUID(), 1, "older-"); String older = f.helpers.owned(f.owner, "older-1").getKey();
            c.assertValueEqual(f.helpers.spawn(f.owner, "fresh"), 1, "A single helper is created");
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 1, AgentCompanions.Profile.REGULAR), 1, "A batch helper is created");
            var ids = List.of(f.helpers.owned(f.owner, "fresh").getKey(), f.helpers.owned(f.owner, "helper-1").getKey());
            for (String id : ids) {
                var live = f.helpers.loaded.get(UUID.fromString(id));
                c.assertTrue(f.helpers.unresolvedCreation(id), "A same-session creation is shown as unresolved");
                String now = f.helpers.creationReview(id);
                c.assertTrue(now.contains("this server session") && now.contains("currently loaded") && now.contains("durability remains unverified")
                    && !now.contains("restart"), "The live entity is distinguished from unproven disk durability, without implying a restart: " + now);
                c.assertTrue(AgentMenu.helperInfo(f.owner, id, f.helpers.data.agents.get(id)).contains(now), "Shared Java and Bedrock status shows it");
                live.remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK); f.helpers.unload(live);
                String away = f.helpers.creationReview(id);
                c.assertTrue(away.contains("this server session") && away.contains("not loaded now") && away.contains("may still exist")
                    && away.contains("durability remains unverified") && !away.contains("currently loaded") && !away.contains("restart"),
                    "After an unload the same session reports uncertainty, not absence or a restart: " + away);
                var copy = f.saved(UUID.fromString(id)); f.helpers.load(copy);
                c.assertTrue(f.helpers.loaded.get(copy.getUUID()) == copy && f.helpers.creationReview(id).contains("not loaded now") && f.helpers.unresolvedCreation(id),
                    "A load callback for a detached copy neither counts as the live entity nor resolves the creation");
                f.helpers.unload(copy); copy.discard();
            }
            c.assertFalse(f.helpers.unresolvedCreation(older), "A pre-existing helper is not a pending creation");
            f.helpers.save();
            var restarted = f.restart();
            for (String id : ids) {
                c.assertTrue(f.helpers.unresolvedCreation(id) && AgentCompanions.read(f.helpers.file).pendingCreations.contains(id),
                    "A flush or save never clears an unresolved pending identity");
                c.assertTrue(restarted.unresolvedCreation(id) && restarted.creationReview(id).contains("after restart"),
                    "Only a runtime started from the saved pending record mentions a restart");
            }
        }
        c.succeed();
    }

    /** What a chunk save would persist for this entity. */
    private static net.minecraft.nbt.CompoundTag persisted(net.minecraft.world.entity.Entity entity) {
        var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, entity.registryAccess());
        entity.saveWithoutId(output); return output.buildResult();
    }
    /** Real native ticks run Entity.checkBelowWorld, its void damage, and movement. */
    private static void assertHeldInVoid(GameTestHelper c, IronGolem golem, double y, int ticks, String label) {
        float health = golem.getHealth();
        for (int tick = 0; tick < ticks; tick++) golem.tick();
        c.assertTrue(golem.isAlive() && !golem.isRemoved() && golem.getHealth() == health, "Native void ticks never damage it: " + label);
        c.assertTrue(Math.abs(golem.getY() - y) < .01 && golem.getDeltaMovement().lengthSqr() < .01,
            "It stays where the void check found it instead of falling further: " + label);
        var saved = persisted(golem);
        c.assertTrue(!golem.isNoGravity() && !golem.isInvulnerable() && !saved.getBooleanOr("NoGravity", false) && !saved.getBooleanOr("Invulnerable", false),
            "Nothing persistent is set: no gravity or invulnerability flag reaches its saved data: " + label);
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=240)
    public void naturalVoidHoldsShieldedHelpersWhileOperatorKillsAndNormalHelpersKeepNativeOutcomes(GameTestHelper c) throws Exception {
        var io = new FaultyIo();
        try (var f = new Fixture(c, io)) {
            var level = c.getLevel(); double below = level.getMinY() - 70;
            var types = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE);
            var retained = f.saved(UUID.randomUUID()); var doomed = f.saved(UUID.randomUUID());
            try {
                c.assertValueEqual(f.helpers.spawn(f.owner, "normal"), 1, "A normal registered helper is created");
                var normal = f.helpers.loaded.get(UUID.fromString(f.helpers.owned(f.owner, "normal").getKey()));
                float normalHealth = normal.getHealth();
                c.assertFalse(AgentCompanions.retainedHelperShielded(normal, normal.damageSources().fellOutOfWorld()), "A normal helper has no void shield");
                c.assertTrue(normal.hurtServer(level, normal.damageSources().fellOutOfWorld(), 4) && normal.getHealth() < normalHealth,
                    "Out-of-world damage still reaches a normal registered helper in a writable runtime");

                retained.setPos(retained.getX(), below, retained.getZ());
                c.assertTrue(level.addFreshEntity(retained) && !f.helpers.loaded.containsKey(retained.getUUID()),
                    "An unknown tagged helper loads below the world, unregistered and inert");
                retained.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.31);
                var natural = retained.damageSources().fellOutOfWorld();
                c.assertTrue(AgentCompanions.naturalVoid(retained, natural) && AgentCompanions.retainedHelperShielded(retained, natural),
                    "Unattributed out-of-world damage below the native threshold is natural void damage and is shielded");
                var attributed = new net.minecraft.world.damagesource.DamageSource(types.getOrThrow(net.minecraft.world.damagesource.DamageTypes.FELL_OUT_OF_WORLD), f.owner);
                c.assertFalse(AgentCompanions.naturalVoid(retained, attributed) || AgentCompanions.retainedHelperShielded(retained, attributed),
                    "Out-of-world damage attributed to a player is an intentional act, not natural void damage");
                c.assertFalse(AgentCompanions.retainedHelperShielded(retained, retained.damageSources().genericKill()), "An operator /kill is never shielded");
                retained.setDeltaMovement(0, -3, 0);
                assertHeldInVoid(c, retained, below, 40, "unknown retained helper");
                f.helpers.unload(retained); f.helpers.load(retained);
                assertHeldInVoid(c, retained, below, 20, "after an unload and reload");
                c.assertTrue(retained.getAttributeBaseValue(Attributes.MOVEMENT_SPEED) == .31, "The edited stat is kept");

                c.assertTrue(level.addFreshEntity(doomed), "A second retained helper loads at ordinary height");
                c.assertFalse(AgentCompanions.naturalVoid(doomed, doomed.damageSources().fellOutOfWorld()), "Above the native threshold there is no natural void hit");
                c.assertTrue(doomed.hurtServer(level, doomed.damageSources().genericKill(), Float.MAX_VALUE) && !doomed.isAlive(),
                    "Known limit: an intentional operator /kill still removes a retained helper");

                normal.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1500);
                io.arm("move:agents.json", true);
                c.assertValueEqual(f.helpers.mode(f.owner, "normal", AgentCompanions.Mode.STAY), 0, "An ambiguous save freezes ownership");
                normal.setPos(normal.getX(), below, normal.getZ()); normal.setDeltaMovement(0, -1, 0);
                assertHeldInVoid(c, normal, below, 30, "registered helper while frozen");
                c.assertTrue(f.helpers.loaded.get(normal.getUUID()) == normal && normal.getAttributeBaseValue(Attributes.MAX_HEALTH) == 1500,
                    "The frozen helper stays registered with its edited stat");
            } finally { retained.discard(); doomed.discard(); }
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=240)
    public void bedrockCreateNeverAsksForBetterGroundAfterAStorageOrFrozenOwnershipFailure(GameTestHelper c) throws Exception {
        // Untouched and interrupted guard writes, then untouched and ambiguous roster saves.
        for (var fault : List.of(Map.entry("write:agents.json.recovery-required.tmp", false),
                Map.entry("write:agents.json.recovery-required.tmp", true), Map.entry("write:agents.json.tmp", false),
                Map.entry("sync-after:move:agents.json", true))) {
            var io = new FaultyIo(); var wire = new NativePlayConnection(c.getLevel().registryAccess());
            try (var f = new Fixture(c, io, wire)) {
                var touch = new Touch(); BedrockAgentMenu.open(f.owner, touch); touch.click("Create your first helper");
                io.arm(fault.getKey(), fault.getValue()); wire.drain();
                touch.click("Create: Regular");
                String shown = touch.last().content(); var told = chat(wire); boolean frozen = fault.getValue();
                c.assertValueEqual(f.helpers.creationRecoveryRequired, frozen, "Only the ambiguous save freezes: " + fault);
                c.assertTrue(f.helpers.data.agents.isEmpty() && f.helpers.loaded.isEmpty(), "No helper is registered or inserted: " + fault);
                c.assertFalse(shown.contains("No helper appeared") || shown.contains("Move onto clear solid ground") || shown.contains("try again")
                    || shown.contains("capacity is full"), "The form does not blame placement, invite an immediate retry or claim full capacity: " + shown);
                if (frozen) c.assertFalse(shown.contains("clear solid ground") || shown.contains("Create one"),
                    "A frozen roster does not suggest creating a helper anywhere: " + shown);
                c.assertTrue(frozen ? shown.contains("No helper entity spawned") && shown.contains("frozen") : shown.contains("check server storage"),
                    "The form names the actual storage or frozen-ownership outcome: " + shown);
                c.assertFalse(shown.contains(f.directory.toString()) || told.stream().anyMatch(text -> text.contains(f.directory.toString())),
                    "No server file path is shown to the player");
                c.assertFalse(told.stream().anyMatch(text -> text.contains("appeared beside you")), "No success is announced: " + told);
            } finally { wire.channel.finishAndReleaseAll(); }
        }
        var wire = new NativePlayConnection(c.getLevel().registryAccess());
        try (var f = new Fixture(c, HelperPersistence.NATIVE, wire)) {
            var touch = new Touch(); BedrockAgentMenu.open(f.owner, touch); touch.click("Create your first helper");
            wire.drain(); touch.click("Create: Regular");
            c.assertTrue(f.helpers.owned(f.owner, "helper-1") != null && chat(wire).stream().anyMatch(text -> text.contains("helper-1 appeared beside you • Regular")),
                "Normal Bedrock creation still succeeds and says so");
        } finally { wire.channel.finishAndReleaseAll(); }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=180)
    public void bedrockCreationReportsFrozenGuardWhenPendingRosterExists(GameTestHelper c) throws Exception {
        var io = new FaultyIo(); var wire = new NativePlayConnection(c.getLevel().registryAccess());
        try (var f = new Fixture(c, io, wire)) {
            var touch = new Touch(); BedrockAgentMenu.open(f.owner, touch); touch.click("Create your first helper");
            io.replaceGuardAfterRosterSync = true; wire.drain(); touch.click("Create: Regular");
            var entry = f.helpers.owned(f.owner, "helper-1"); var shown = touch.last().content(); var told = chat(wire);
            c.assertTrue(entry != null && f.helpers.unresolvedCreation(entry.getKey()) && f.helpers.loaded.isEmpty()
                && f.helpers.creationRecoveryRequired && f.restart().creationRecoveryRequired,
                "A changed guard leaves pending ownership frozen before any native insertion");
            c.assertTrue(shown.contains("No helper entity spawned") && shown.contains("frozen")
                && !shown.contains("Move onto clear solid ground") && !shown.contains("appeared beside you")
                && told.stream().noneMatch(text -> text.contains("appeared beside you")),
                "The touch form reports frozen ownership rather than placement or success");
        } finally { wire.channel.finishAndReleaseAll(); }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=240)
    public void aRosterLostAfterStartupIsNeverRecreatedOverItsBackup(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c)) {
            c.assertValueEqual(f.helpers.spawn(f.owner, "kept"), 1, "A real helper is created");
            var entry = f.helpers.owned(f.owner, "kept"); var live = f.helpers.loaded.get(UUID.fromString(entry.getKey()));
            live.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.31);
            f.unloaded(f.owner.getUUID(), 1, "away-"); var away = f.helpers.owned(f.owner, "away-1");
            byte[] backup = Files.readAllBytes(f.previousFile());
            Files.delete(f.helpers.file);
            c.assertValueEqual(f.helpers.mode(f.owner, "kept", AgentCompanions.Mode.STAY), 0, "A change after the roster vanished is refused");
            c.assertTrue(f.helpers.creationRecoveryRequired && Files.notExists(f.helpers.file) && Arrays.equals(backup, Files.readAllBytes(f.previousFile())),
                "No fresh roster is written and the backup bytes are kept");
            c.assertTrue(Files.exists(f.marker()), "A recovery note keeps restarts frozen");
            c.assertValueEqual(f.helpers.spawnBatch(f.owner, 1, AgentCompanions.Profile.REGULAR), 0, "Creation is refused");
            boolean refused = false;
            try { f.helpers.save(); } catch (IllegalStateException expected) { refused = true; }
            c.assertTrue(refused && Files.notExists(f.helpers.file) && Arrays.equals(backup, Files.readAllBytes(f.previousFile())), "A defensive save writes nothing");
            c.assertTrue(f.helpers.data.agents.get(entry.getKey()) == entry.getValue() && f.helpers.data.agents.get(away.getKey()) == away.getValue()
                && f.helpers.loaded.get(live.getUUID()) == live && live.isAlive() && live.getAttributeBaseValue(Attributes.MOVEMENT_SPEED) == .31,
                "Loaded and unloaded records, the live helper and its edited stat are kept");
            c.assertTrue(f.restart().creationRecoveryRequired && Arrays.equals(backup, Files.readAllBytes(f.previousFile())),
                "A restart stays frozen; nothing is restored or deleted automatically");

            Path fresh = f.directory.resolve("first").resolve("agents.json"); Files.createDirectories(fresh.getParent());
            var first = new AgentCompanions(f.helpers.server, fresh);
            c.assertFalse(first.rosterEstablished || first.creationRecoveryRequired, "A genuine first boot has no established roster to lose");
            first.save();
            c.assertTrue(first.rosterEstablished && Files.exists(fresh) && !first.creationRecoveryRequired, "Its first write succeeds and establishes the roster");
        }
        c.succeed();
    }
}
