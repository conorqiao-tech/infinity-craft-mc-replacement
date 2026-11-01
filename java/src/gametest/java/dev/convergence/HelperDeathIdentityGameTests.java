package dev.convergence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** Dedicated native death/collision group: each scenario uses disposable roster and server entities. */
public class HelperDeathIdentityGameTests {
    private record ReentrantCreation(AgentCompanions helpers, UUID owner, net.minecraft.server.level.ServerLevel other,
                                     AtomicReference<IronGolem> existing, AtomicReference<IronGolem> incoming) {}
    private static final AtomicReference<ReentrantCreation> REENTRANT_CREATION=new AtomicReference<>();
    private record ReentrantRecall(AgentCompanions helpers, UUID owner, net.minecraft.server.level.ServerLevel source,
                                   net.minecraft.server.level.ServerLevel destination, AtomicReference<IronGolem> existing,
                                   AtomicReference<IronGolem> incoming) {}
    private static final AtomicReference<ReentrantRecall> REENTRANT_RECALL=new AtomicReference<>();
    static {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world)->{
            var recall=REENTRANT_RECALL.get();
            if(recall!=null && world==recall.destination && AgentCompanions.isAgent(entity)) {
                var record=recall.helpers.data.agents.get(entity.getStringUUID());
                if(record!=null && record.owner().equals(recall.owner.toString()) && record.name().equals("recall-collision")
                    && REENTRANT_RECALL.compareAndSet(recall,null)) {
                    var existing=EntityTypes.IRON_GOLEM.create(recall.source,EntitySpawnReason.COMMAND);
                    existing.setUUID(entity.getUUID());existing.addTag(AgentCompanions.TAG);
                    existing.setPos(Vec3.atBottomCenterOf(new BlockPos(0,100,0)));
                    if(!recall.source.addFreshEntity(existing))throw new IllegalStateException("Could not create same-UUID recall collision fixture");
                    recall.helpers.loaded.put(entity.getUUID(),existing);
                    recall.existing.set(existing);recall.incoming.set((IronGolem)entity);
                    recall.helpers.load(entity);
                }
            }
            var fault=REENTRANT_CREATION.get();
            if(fault==null || world.getServer()!=fault.helpers.server || !AgentCompanions.isAgent(entity)) return;
            var record=fault.helpers.data.agents.get(entity.getStringUUID());
            if(record==null || !record.owner().equals(fault.owner.toString()) || !record.name().equals("reentrant"))return;
            if(!REENTRANT_CREATION.compareAndSet(fault,null))return;
            var existing=EntityTypes.IRON_GOLEM.create(fault.other,EntitySpawnReason.COMMAND);
            existing.setUUID(entity.getUUID());existing.addTag(AgentCompanions.TAG);
            existing.setPos(Vec3.atBottomCenterOf(new BlockPos(0,100,0)));
            if(!fault.other.addFreshEntity(existing))throw new IllegalStateException("Could not create same-UUID reentrant fixture");
            // Simulate a different body registering during this native callback,
            // then deliver the original to production load before add returns.
            fault.helpers.loaded.put(entity.getUUID(),existing);
            fault.existing.set(existing);fault.incoming.set((IronGolem)entity);
            fault.helpers.load(entity);
        });
    }
    static final class Fixture implements AutoCloseable {
        final GameTestHelper c;
        final AgentCompanions previous, helpers;
        final NativePlayConnection wire;
        final ServerPlayer owner;
        final Path dir;
        Fixture(GameTestHelper c) throws Exception {
            this.c=c; var server=c.getLevel().getServer(); previous=AgentCompanions.get(server);
            c.assertTrue(previous.data.agents.isEmpty(), "Exclusive fixture starts with no unrelated helpers");
            dir=Files.createTempDirectory("infinity-death-identity-");
            helpers=new AgentCompanions(server,dir.resolve("agents.json")); AgentCompanions.INSTANCES.put(server,helpers);
            wire=new NativePlayConnection(c.getLevel().registryAccess());
            owner=new ModeGameTests().player(c,"death-identity-owner",wire.connection);
            OperatorGameTests.level(owner,LevelBasedPermissionSet.OWNER);
            var feet=c.absolutePos(new BlockPos(20,55,20));
            for(var at:BlockPos.betweenClosed(feet.offset(-8,-1,-8),feet.offset(8,3,8)))
                c.getLevel().setBlockAndUpdate(at,at.getY()==feet.getY()-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
            owner.setPos(Vec3.atBottomCenterOf(feet)); helpers.save(); wire.drain();
        }
        IronGolem spawn(String name) {
            c.assertValueEqual(helpers.spawn(owner,name),1,"Native helper is created");
            return helpers.loaded.get(UUID.fromString(helpers.owned(owner,name).getKey()));
        }
        boolean notice(String fragment) {
            return wire.drain().stream().filter(p->p instanceof ClientboundSystemChatPacket)
                .map(p->((ClientboundSystemChatPacket)p).content().getString()).anyMatch(s->s.contains(fragment));
        }
        @Override public void close() throws Exception {
            for(var body:new ArrayList<>(helpers.loaded.values())) if(!body.isRemoved())body.discard();
            helpers.loaded.clear(); owner.closeContainer(); OperatorGameTests.deop(owner);
            c.getLevel().getServer().getPlayerList().remove(owner); wire.channel.finishAndReleaseAll();
            if(AgentCompanions.INSTANCES.get(helpers.server)==helpers)AgentCompanions.INSTANCES.put(helpers.server,previous);
            try(var paths=Files.walk(dir)){for(var p:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
    static void pendingAndLegacy(GameTestHelper c) throws Exception {
        try(var f=new Fixture(c)) {
            var other=f.helpers.server.getLevel(Level.NETHER);other.setChunkForced(0,0,true);other.getChunkAt(new BlockPos(0,100,0));
            for(var at:BlockPos.betweenClosed(new BlockPos(-8,99,-8),new BlockPos(8,103,8)))
                other.setBlockAndUpdate(at,at.getY()==99?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
            var original=f.spawn("recall-collision");
            var transition=new TeleportTransition(other,new Vec3(.5,100,.5),Vec3.ZERO,0,0,TeleportTransition.DO_NOTHING);
            c.assertTrue(f.owner.teleport(transition)==f.owner,"Native owner transfer reaches the destination");
            f.owner.hasChangedDimension();f.owner.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
            var existing=new AtomicReference<IronGolem>();var incoming=new AtomicReference<IronGolem>();
            var fault=new ReentrantRecall(f.helpers,f.owner.getUUID(),c.getLevel(),other,existing,incoming);
            c.assertTrue(REENTRANT_RECALL.compareAndSet(null,fault),"The one-shot native recall collision is armed");
            try {
                int result=f.helpers.recall(f.owner,"recall-collision");
                c.assertTrue(existing.get()!=null&&incoming.get()!=null,"A native destination load callback reached the injection before recall returned");
                c.assertValueEqual(result,0,"Recall reports uncertainty rather than success");
                c.assertTrue(f.helpers.creationRecoveryRequired&&f.helpers.loaded.get(original.getUUID())==existing.get(),"Recall does not replace the collision handler's registration");
                c.assertTrue(!existing.get().isRemoved()&&!incoming.get().isRemoved(),"Both collision bodies survive the interrupted recall");
                c.assertTrue(f.helpers.data.pendingCreations.contains(original.getStringUUID())&&!f.helpers.data.dismissed.contains(original.getStringUUID()),"Recall collision retains the pending identity");
            } finally {
                REENTRANT_RECALL.compareAndSet(fault,null);
                if(incoming.get()!=null&&!incoming.get().isRemoved())incoming.get().discard();
                if(existing.get()!=null&&!existing.get().isRemoved())existing.get().discard();other.setChunkForced(0,0,false);
            }
        }
        try(var f=new Fixture(c)) {
            var other=f.helpers.server.getLevel(Level.NETHER);other.setChunkForced(0,0,true);other.getChunkAt(new BlockPos(0,100,0));
            var existing=new AtomicReference<IronGolem>();var incoming=new AtomicReference<IronGolem>();
            var fault=new ReentrantCreation(f.helpers,f.owner.getUUID(),other,existing,incoming);
            c.assertTrue(REENTRANT_CREATION.compareAndSet(null,fault),"The one-shot native insertion collision is armed");
            try {
                int created=f.helpers.spawn(f.owner,"reentrant");
                c.assertTrue(existing.get()!=null && incoming.get()!=null,"A real native load callback reached the injection before creation returned");
                c.assertValueEqual(created,0,"A collision during native insertion cannot report success");
                c.assertTrue(f.helpers.creationRecoveryRequired&&f.helpers.loaded.get(existing.get().getUUID())==existing.get(),"Creation does not overwrite the collision handler's registration");
                c.assertTrue(existing.get().isAlive()&&!existing.get().isRemoved()&&incoming.get().isAlive()&&!incoming.get().isRemoved(),"Both reentrant collision bodies remain alive");
                c.assertTrue(f.helpers.data.pendingCreations.contains(existing.get().getStringUUID())&&!f.helpers.data.dismissed.contains(existing.get().getStringUUID()),"Pending identity stays without tombstone");
            } finally {
                REENTRANT_CREATION.compareAndSet(fault,null);
                if(incoming.get()!=null&&!incoming.get().isRemoved())incoming.get().discard();
                if(existing.get()!=null&&!existing.get().isRemoved())existing.get().discard();other.setChunkForced(0,0,false);
            }
        }
        try(var f=new Fixture(c)) {
            var stale=f.spawn("stale-unloaded");String id=stale.getStringUUID();
            // Simulate an already-unregistered body delivering a late native
            // death event while its saved identity remains in the roster.
            f.helpers.loaded.remove(stale.getUUID(),stale);f.helpers.lastAttack.put(stale.getUUID(),77);
            byte[] before=Files.readAllBytes(f.helpers.file);f.wire.drain();
            c.assertTrue(stale.hurtServer(c.getLevel(),stale.damageSources().genericKill(),10000)&&!stale.isAlive(),"A late unregistered body delivers actual native death");
            c.assertTrue(f.helpers.creationRecoveryRequired&&Files.exists(HelperPersistence.recoveryMarker(f.helpers.file)),"The mismatched death itself freezes ownership");
            c.assertTrue(f.helpers.data.agents.containsKey(id)&&f.helpers.data.pendingCreations.contains(id)&&!f.helpers.data.dismissed.contains(id),"An unregistered death keeps its pending record without removal proof");
            c.assertTrue(Arrays.equals(before,Files.readAllBytes(f.helpers.file))&&f.helpers.lastAttack.get(stale.getUUID())==77,"Neither disk roster nor UUID bookkeeping is removed");
            c.assertTrue(f.notice("still uses a helper slot"),"The owner is told the retained identity occupies a slot");
        }
        try(var f=new Fixture(c)) {
            var pending=f.spawn("pending");var unrelated=f.spawn("other");
            unrelated.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.43);
            String id=pending.getStringUUID();byte[] before=Files.readAllBytes(f.helpers.file);f.wire.drain();
            c.assertTrue(pending.hurtServer(c.getLevel(),pending.damageSources().genericKill(),10000)&&!pending.isAlive(),"A real registered pending helper dies");
            c.assertFalse(f.helpers.creationRecoveryRequired,"A verified exact-body death does not freeze unrelated helpers");
            c.assertTrue(f.helpers.data.agents.containsKey(id)&&f.helpers.data.pendingCreations.contains(id)&&!f.helpers.data.dismissed.contains(id),"Pending identity stays reserved without removal proof");
            c.assertTrue(Arrays.equals(before,Files.readAllBytes(f.helpers.file)),"Pending death leaves the saved roster unchanged");
            c.assertValueEqual(f.helpers.count(f.owner),2,"Dead unverified identity still occupies a slot");
            c.assertTrue(f.notice("still uses a helper slot"),"Online owner receives the host-review and slot notice");
            c.assertTrue(unrelated.isAlive()&&unrelated.getAttributeBaseValue(Attributes.MOVEMENT_SPEED)==.43,"Unrelated helper and edited stat remain intact");
            c.assertValueEqual(f.helpers.mode(f.owner,"other",AgentCompanions.Mode.GUARD),1,"Unrelated helper controls remain writable");
            c.assertValueEqual(f.helpers.spawn(f.owner,"another"),1,"The owner can create another helper within remaining capacity");
            var restarted=new AgentCompanions(f.helpers.server,f.helpers.file);
            c.assertTrue(!restarted.creationRecoveryRequired&&restarted.data.pendingCreations.contains(id)&&!restarted.data.dismissed.contains(id),"Restart preserves the pending identity without freezing the entire roster");
        }
        try(var f=new Fixture(c)) {
            var pending=f.spawn("mismatch-marker-failure");String id=pending.getStringUUID();byte[] before=Files.readAllBytes(f.helpers.file);
            f.helpers.loaded.remove(pending.getUUID(),pending);
            var temporary=HelperPersistence.recoveryTemporary(f.helpers.file);Files.createDirectory(temporary);
            c.assertTrue(pending.hurtServer(c.getLevel(),pending.damageSources().genericKill(),10000)&&!pending.isAlive(),"Native mismatched death occurs while temporary recovery evidence exists");
            c.assertTrue(f.helpers.creationRecoveryRequired&&f.helpers.data.pendingCreations.contains(id)&&!f.helpers.data.dismissed.contains(id),"Current process fails closed without removal proof");
            c.assertTrue(Arrays.equals(before,Files.readAllBytes(f.helpers.file)),"Recovery evidence cannot rewrite the roster");
            var restarted=new AgentCompanions(f.helpers.server,f.helpers.file);
            c.assertTrue(restarted.data.pendingCreations.contains(id)&&!restarted.data.dismissed.contains(id),"The pending identity stays on disk");
            c.assertTrue(restarted.creationRecoveryRequired&&Files.isDirectory(temporary)&&Files.notExists(HelperPersistence.recoveryMarker(f.helpers.file)),
                "Temporary recovery evidence is kept and freezes the restarted runtime without a final marker");
        }
        try(var f=new Fixture(c)) {
            var legacy=f.spawn("legacy");String id=legacy.getStringUUID();
            c.assertTrue(f.helpers.data.pendingCreations.remove(id),"Simulate an old nonpending roster record");f.helpers.save();
            var record=f.helpers.data.agents.get(id);byte[] before=Files.readAllBytes(f.helpers.file);f.wire.drain();
            c.assertTrue(legacy.hurtServer(c.getLevel(),legacy.damageSources().genericKill(),10000)&&!legacy.isAlive(),"Real legacy helper death occurs");
            c.assertTrue(f.helpers.data.agents.get(id)==record&&!f.helpers.data.pendingCreations.contains(id)&&!f.helpers.data.dismissed.contains(id),
                "Nonpending death keeps the exact record, adds no pending entry and writes no removal proof");
            c.assertTrue(Arrays.equals(before,Files.readAllBytes(f.helpers.file))&&!f.helpers.creationRecoveryRequired,"Nonpending death performs no roster write and does not freeze");
            c.assertValueEqual(f.helpers.count(f.owner),1,"The dead legacy identity still occupies its slot");
            c.assertTrue(f.notice("still uses a helper slot"),"The online owner is told the identity remains reserved");
        }
        try(var f=new Fixture(c)) {
            var pending=f.spawn("owner-dismiss");String id=pending.getStringUUID();
            c.assertValueEqual(f.helpers.dismiss(f.owner,"owner-dismiss"),1,"Explicit owner dismissal behavior is unchanged");
            c.assertTrue(pending.isRemoved()&&!f.helpers.data.agents.containsKey(id)&&f.helpers.data.dismissed.contains(id),"Owner dismissal still records the explicit removal");
        }
    }
    @GameTest(structure="convergence_tests:combat_arena",maxTicks=300)
    public void duplicateNativeLoadAndExactDeathGuard(GameTestHelper c) throws Exception {
        var f=new Fixture(c);var server=c.getLevel().getServer();var other=server.getLevel(Level.NETHER);
        c.assertTrue(other!=null,"Disposable Nether exists");other.setChunkForced(0,0,true);other.getChunkAt(new BlockPos(0,100,0));
        other.setBlockAndUpdate(new BlockPos(0,99,0),Blocks.STONE.defaultBlockState());
        var original=f.spawn("collision");var unrelated=f.spawn("other");String id=original.getStringUUID();
        original.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.37);unrelated.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.41);
        original.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1500);original.setHealth(900);
        ItemStack gear=original.getMainHandItem().copy();
        var copy=EntityTypes.IRON_GOLEM.create(other,EntitySpawnReason.COMMAND);copy.setUUID(original.getUUID());copy.addTag(AgentCompanions.TAG);copy.setPos(Vec3.atBottomCenterOf(new BlockPos(0,100,0)));
        c.assertTrue(other.addFreshEntity(copy),"The second dimension accepts a same-UUID body");
        c.runAfterDelay(20,()->{
            try {
                c.assertTrue(f.helpers.creationRecoveryRequired&&Files.exists(HelperPersistence.recoveryMarker(f.helpers.file)),"Actual ENTITY_LOAD detects collision and freezes ownership");
                c.assertTrue(f.helpers.loaded.get(original.getUUID())==original&&original.isAlive()&&copy.isAlive(),"Original registration and both bodies survive actual load delivery");
                c.assertTrue(f.helpers.data.agents.containsKey(id)&&f.helpers.data.pendingCreations.contains(id)&&!f.helpers.data.dismissed.contains(id),"Collision does not erase pending identity or create proof");
                f.helpers.lastAttack.put(original.getUUID(),123);f.helpers.nextLeap.put(original.getUUID(),456);
                c.assertTrue(copy.hurtServer(other,copy.damageSources().genericKill(),10000)&&!copy.isAlive(),"The nonregistered body receives a real native death event");
                c.assertTrue(f.helpers.loaded.get(original.getUUID())==original&&original.isAlive(),"Unregistered death keeps exact registered body");
                c.assertTrue(f.helpers.lastAttack.get(original.getUUID())==123&&f.helpers.nextLeap.get(original.getUUID())==456,"Unregistered death cannot clear UUID-scoped bookkeeping");
                c.assertTrue(original.getAttributeBaseValue(Attributes.MOVEMENT_SPEED)==.37&&unrelated.getAttributeBaseValue(Attributes.MOVEMENT_SPEED)==.41,"Both original and unrelated edited stats remain unchanged");
                c.assertTrue(original.getHealth()==900&&original.getAttributeBaseValue(Attributes.MAX_HEALTH)==1500&&ItemStack.matches(original.getMainHandItem(),gear),"Surviving helper retains health, edited capacity, and equipment: health="+original.getHealth()+" max="+original.getAttributeBaseValue(Attributes.MAX_HEALTH)+" gear-match="+ItemStack.matches(original.getMainHandItem(),gear));
                c.assertTrue(f.helpers.data.pendingCreations.contains(id)&&!f.helpers.data.dismissed.contains(id),"Unregistered death never creates removal proof");
            } catch(Exception e){throw new RuntimeException(e);}
            finally {
                if(!copy.isRemoved())copy.discard();other.setChunkForced(0,0,false);
                try{f.close();}catch(Exception e){throw new RuntimeException(e);}
            }
            try { pendingAndLegacy(c); c.succeed(); }
            catch(Exception e) { throw new RuntimeException(e); }
        });
    }
}
