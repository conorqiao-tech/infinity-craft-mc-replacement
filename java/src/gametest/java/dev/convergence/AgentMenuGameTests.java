package dev.convergence;

import java.util.ArrayList;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Exercise actual handled screens and packets, rather than just their icon layout. */
public class AgentMenuGameTests {
    private ServerPlayer player(GameTestHelper c, String name) {
        var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), NativeSessionFixture.nativeProfileName(name));
        var data = net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
        var player = new ServerPlayer(c.getLevel().getServer(), c.getLevel(), profile, data.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        c.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, data);
        player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        operator(player, LevelBasedPermissionSet.OWNER);
        BlockPos feet = c.absolutePos(new BlockPos(3, 20, 3));
        for (BlockPos at : BlockPos.betweenClosed(feet.offset(-8, -1, -8), feet.offset(8, 4, 8)))
            c.getLevel().setBlockAndUpdate(at, at.getY() == feet.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        player.setPos(Vec3.atBottomCenterOf(feet));
        player.setNoGravity(true);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private void operator(ServerPlayer player, LevelBasedPermissionSet level) {
        var manager = player.level().getServer().getPlayerList();
        manager.deop(new net.minecraft.server.players.NameAndId(player.getGameProfile()));
        manager.op(new net.minecraft.server.players.NameAndId(player.getGameProfile()),
            java.util.Optional.of(level), java.util.Optional.of(false));
    }

    private void cleanup(AgentCompanions helpers, ServerPlayer player) {
        try {
            player.containerMenu.setCarried(ItemStack.EMPTY);
            player.closeContainer();
            // Restore permission before cleanup, so direct mutators remain gated too.
            operator(player, LevelBasedPermissionSet.OWNER);
            var names = helpers.data.agents.values().stream().filter(a -> a.owner().equals(player.getStringUUID()))
                .map(AgentCompanions.Agent::name).toList();
            for (String name : names) helpers.dismiss(player, name);
            helpers.server.getPlayerList().deop(new net.minecraft.server.players.NameAndId(player.getGameProfile()));
            helpers.server.getPlayerList().remove(player);
        } catch (RuntimeException | Error failure) {
            System.err.println("[Infinity helper-menu test] cleanup failed for " + player.getName().getString());
            failure.printStackTrace();
            throw failure;
        }
    }

    private AgentMenu.Handler menu(ServerPlayer player) { return (AgentMenu.Handler) player.containerMenu; }
    private void click(ServerPlayer player, int slot) {
        player.containerMenu.clicked(slot, 0, ContainerInput.PICKUP, player);
    }
    private void detail(ServerPlayer player, String name) {
        var handler = menu(player);
        var helpers = AgentCompanions.get(player.level().getServer());
        String id = helpers.owned(player, name).getKey();
        int slot = handler.choices.entrySet().stream().filter(entry -> entry.getValue().equals(id))
            .mapToInt(java.util.Map.Entry::getKey).findFirst().orElseThrow();
        click(player, slot);
    }

    @GameTest public void emptyJavaSquadShowsCreateFirstInsteadOfSilentMovementControls(GameTestHelper c) {
        var owner = player(c, "menu-empty-help");
        var helpers = AgentCompanions.get(c.getLevel().getServer());
        var queue = AgentActions.get(helpers.server);
        int proposals = queue.data.proposals.size();
        try {
            for (int slot : new int[]{AgentMenu.FOLLOW, AgentMenu.GUARD, AgentMenu.STAY, AgentMenu.RECALL,
                    AgentMenu.FORMATION, AgentMenu.SQUAD_PRIMITIVE, AgentMenu.SQUAD_REGULAR, AgentMenu.SQUAD_ULTIMATE}) {
                AgentMenu.open(owner);
                c.assertValueEqual(menu(owner).view.getItem(slot).getHoverName().getString(),
                    "Create a helper first", "Empty-roster control has an explicit requirement");
                click(owner, slot);
                c.assertTrue(owner.containerMenu == owner.inventoryMenu, "Requirement feedback is revealed rather than hidden beneath a chest");
                c.assertValueEqual(helpers.count(owner), 0, "A disabled squad control never creates a helper");
            }
            c.assertValueEqual(queue.data.proposals.size(), proposals, "Empty controls never queue or approve a command");
        } finally { cleanup(helpers, owner); }
        c.succeed();
    }

    @GameTest public void helperMenuRequiresCurrentOwnerAndRechecksOpFour(GameTestHelper c) {
        var owner = player(c, "menu-owner");
        var other = player(c, "menu-other");
        var helpers = AgentCompanions.get(c.getLevel().getServer());
        try {
            operator(owner, LevelBasedPermissionSet.ADMIN);
            c.assertValueEqual(AgentMenu.open(owner), 0, "OP3 cannot open helper controls");
            c.assertTrue(owner.containerMenu == owner.inventoryMenu, "Rejected open leaves the current inventory alone");
            operator(owner, LevelBasedPermissionSet.OWNER);
            c.assertValueEqual(AgentMenu.open(owner), 1, "OP4 can open a vanilla helper chest");
            var screen = menu(owner);
            AbstractContainerMenu vanillaScreen = screen;
            c.assertTrue(vanillaScreen.stillValid(owner), "Living OP4 owner can use its screen");
            c.assertFalse(vanillaScreen.stillValid(other), "Another OP4 UUID cannot use the screen");
            vanillaScreen.clicked(AgentMenu.helperSlot(0), 0, ContainerInput.PICKUP, other);
            c.assertValueEqual(helpers.count(owner), 0, "A foreign click cannot create a helper for the owner");
            c.assertValueEqual(helpers.count(other), 0, "A foreign click cannot create a helper for the sender");
            operator(owner, LevelBasedPermissionSet.ADMIN);
            c.assertFalse(vanillaScreen.stillValid(owner), "Permission is checked again after the menu opens");
            vanillaScreen.clicked(AgentMenu.helperSlot(0), 0, ContainerInput.PICKUP, owner);
            c.assertValueEqual(helpers.count(owner), 0, "Losing OP4 blocks an already-open spawn button");
            c.assertTrue(owner.containerMenu == owner.inventoryMenu, "A revoked menu closes safely");
        } catch (RuntimeException | Error failure) {
            System.err.println("[Infinity helper-menu test] permission recheck failed");
            failure.printStackTrace();
            throw failure;
        } finally { cleanup(helpers, owner); cleanup(helpers, other); }
        c.succeed();
    }

    @GameTest public void helperMenuCreatesMultipleHelpersAndControlsProfilesAndSquads(GameTestHelper c) {
        var owner = player(c, "menu-squad");
        var helpers = AgentCompanions.get(c.getLevel().getServer());
        int proposals = AgentActions.get(helpers.server).data.proposals.size();
        try {
            AgentMenu.open(owner);
            click(owner, AgentMenu.helperSlot(0));
            AgentMenu.open(owner);
            click(owner, AgentMenu.helperSlot(1));
            c.assertValueEqual(helpers.count(owner), 2, "Two empty slots create two distinct helpers");
            c.assertTrue(helpers.owned(owner, "helper-1") != null && helpers.owned(owner, "helper-2") != null,
                "Numbered names stay unique as the screen refreshes");
            AgentMenu.open(owner);
            detail(owner, "helper-1");
            AbstractContainerMenu detailScreen = owner.containerMenu;
            for (var profile : AgentCompanions.Profile.values()) {
                click(owner, AgentMenu.profileSlot(profile.ordinal()));
                c.assertTrue(owner.containerMenu == detailScreen, "Profile selection updates the current container without closing it");
                c.assertValueEqual(helpers.owned(owner, "helper-1").getValue().profile(), profile,
                    "A real menu click selects " + profile.label());
                c.assertTrue(menu(owner).view.getItem(AgentMenu.profileSlot(profile.ordinal())).getHoverName().getString().startsWith("Selected:"),
                    "In-place refresh marks the newly selected profile");
                c.assertValueEqual(helpers.owned(owner, "helper-2").getValue().profile(), AgentCompanions.Profile.REGULAR,
                    "Selecting one profile leaves the other helper unchanged");
            }
            click(owner, AgentMenu.GUARD);
            c.assertTrue(owner.containerMenu == detailScreen, "Detail movement keeps the same container and sync ID");
            c.assertValueEqual(helpers.owned(owner, "helper-1").getValue().mode(), AgentCompanions.Mode.GUARD,
                "Detail movement controls the selected helper");
            click(owner, AgentMenu.BACK);
            AbstractContainerMenu rosterScreen = owner.containerMenu;
            click(owner, AgentMenu.STAY);
            c.assertTrue(owner.containerMenu == rosterScreen, "Squad movement updates its current roster in place");
            c.assertTrue(helpers.data.agents.values().stream().filter(a -> a.owner().equals(owner.getStringUUID()))
                .allMatch(a -> a.mode() == AgentCompanions.Mode.STAY), "Squad stay pauses every owned loaded helper");
            c.assertValueEqual(AgentActions.get(helpers.server).data.proposals.size(), proposals,
                "Profile and movement clicks neither propose nor dispatch server commands");
        } catch (RuntimeException | Error failure) {
            System.err.println("[Infinity helper-menu test] squad controls failed");
            failure.printStackTrace();
            throw failure;
        } finally { cleanup(helpers, owner); }
        c.succeed();
    }

    @GameTest public void firstHelperButtonRevealsTheNamedGolemWithoutRepeatingItsClick(GameTestHelper c) {
        var owner = player(c, "menu-first");
        var helpers = AgentCompanions.get(c.getLevel().getServer());
        int proposals = AgentActions.get(helpers.server).data.proposals.size();
        try {
            AgentMenu.open(owner);
            var firstScreen = menu(owner);
            c.assertValueEqual(firstScreen.view.getItem(AgentMenu.helperSlot(0)).getHoverName().getString(),
                "Create your first helper", "An empty squad has an obvious first-helper action");
            c.assertValueEqual(helpers.count(owner), 0, "Opening helper controls never spawns an entity automatically");
            click(owner, AgentMenu.helperSlot(0));
            c.assertTrue(owner.containerMenu == owner.inventoryMenu,
                "Creating the helper closes its menu so the owner can see the golem and feedback");
            var record = helpers.owned(owner, "helper-1");
            c.assertTrue(record != null, "The first-helper button saves one named helper");
            var golem = helpers.loaded.get(UUID.fromString(record.getKey()));
            c.assertTrue(golem != null && golem.isAlive() && !golem.isInvisible(),
                "The actual helper entity is loaded, alive, and visible");
            c.assertTrue(golem.isCustomNameVisible() && golem.getCustomName().getString().contains("helper-1"),
                "The visible helper name matches the menu feedback");
            c.assertTrue(golem.level() == owner.level() && golem.distanceToSqr(owner) <= 32,
                "The helper appears beside its owner in the same world");
            ((AbstractContainerMenu)firstScreen).clicked(AgentMenu.helperSlot(0), 0, ContainerInput.PICKUP, owner);
            c.assertValueEqual(helpers.count(owner), 1, "A delayed click from the closed menu cannot create a second helper");
            c.assertValueEqual(AgentActions.get(helpers.server).data.proposals.size(), proposals,
                "Creating a helper does not approve or execute a server action");
        } finally { cleanup(helpers, owner); }
        c.succeed();
    }

    @GameTest public void helperInformationClosesTheChestAndRosterReturnsToInfinityMenu(GameTestHelper c) {
        var owner = player(c, "menu-readable-help");
        var helpers = AgentCompanions.get(c.getLevel().getServer());
        try {
            helpers.spawn(owner, "guide");
            AgentMenu.open(owner);
            var roster = menu(owner);
            c.assertValueEqual(roster.view.getItem(AgentMenu.BACK).getHoverName().getString(), "Back to Infinity Menu", "The helper roster has a visible parent-menu route");
            click(owner, AgentMenu.INFO);
            c.assertTrue(owner.containerMenu == owner.inventoryMenu, "Roster help closes its chest before showing chat guidance");
            AgentMenu.open(owner); detail(owner, "guide");
            click(owner, AgentMenu.INFO);
            c.assertTrue(owner.containerMenu == owner.inventoryMenu, "Helper status closes its chest so its location and health are readable");
            c.assertTrue(helpers.owned(owner, "guide") != null, "Reading status leaves the helper intact");
            AgentMenu.open(owner);
            AbstractContainerMenu oldRoster = owner.containerMenu;
            click(owner, AgentMenu.BACK);
            c.assertTrue(owner.containerMenu instanceof ServerMenu.Handler, "Roster Back opens the actual Infinity Menu");
            var parent = owner.containerMenu;
            oldRoster.clicked(AgentMenu.INFO, 0, ContainerInput.PICKUP, owner);
            c.assertTrue(owner.containerMenu == parent, "Delayed help clicks do not close the newly opened parent menu");
        } finally { cleanup(helpers, owner); }
        c.succeed();
    }

    @GameTest public void askHelperButtonClosesForItsAnswerAndKeepsOwnerPermissions(GameTestHelper c) {
        var owner = player(c, "menu-ask-helper");
        var server = c.getLevel().getServer();
        var helpers = AgentCompanions.get(server);
        var originalService = ServerAssistant.AI.remove(server);
        int proposals = AgentActions.get(server).data.proposals.size();
        try {
            helpers.spawn(owner, "guide");
            AgentMenu.open(owner); detail(owner, "guide");
            var first = menu(owner);
            c.assertValueEqual(first.view.getItem(AgentMenu.ASK).getHoverName().getString(), "Ask helper", "Local fallback is never mislabeled as Codex");
            c.assertValueEqual(first.view.getItem(AgentMenu.ASK).getItem(), Items.WRITABLE_BOOK, "The ask button uses a vanilla crossplay icon");
            var lore = first.view.getItem(AgentMenu.ASK).get(DataComponents.LORE).lines();
            c.assertTrue(lore.stream().allMatch(line -> line.getString().length() <= 43), "Guidance disclosure wraps into touch-screen-size lines");
            c.assertTrue(String.join(" ", lore.stream().map(Component::getString).toList()).contains("Answers cannot execute commands"), "The menu explains that answers cannot run commands");
            var session = ServerAssistant.SESSIONS.get(server);
            if (session != null) session.readyAt.remove(owner.getUUID());
            operator(owner, LevelBasedPermissionSet.ADMIN);
            click(owner, AgentMenu.ASK);
            c.assertTrue(owner.containerMenu == owner.inventoryMenu, "Revoking OP4 closes a previously opened Ask button");
            session = ServerAssistant.SESSIONS.get(server);
            c.assertTrue(session == null || !session.readyAt.containsKey(owner.getUUID()), "Rejected Ask does not dispatch a question");
            operator(owner, LevelBasedPermissionSet.OWNER);
            AgentMenu.open(owner); detail(owner, "guide");
            AbstractContainerMenu questionScreen = owner.containerMenu;
            click(owner, AgentMenu.ASK);
            c.assertTrue(owner.containerMenu == owner.inventoryMenu, "Asking closes the chest before the answer is delivered");
            session = ServerAssistant.SESSIONS.get(server);
            c.assertTrue(session != null && session.readyAt.containsKey(owner.getUUID()), "The actual named-helper question path applies its shared cooldown");
            c.assertValueEqual(helpers.owned(owner, "guide").getValue().profile(), AgentCompanions.Profile.REGULAR, "Asking does not change the helper profile");
            c.assertValueEqual(AgentActions.get(server).data.proposals.size(), proposals, "An answer cannot queue or approve a server command");
            session.readyAt.remove(owner.getUUID());
            questionScreen.clicked(AgentMenu.ASK, 0, ContainerInput.PICKUP, owner);
            c.assertFalse(session.readyAt.containsKey(owner.getUUID()), "A delayed click cannot dispatch a second question");
        } finally {
            if (originalService == null) ServerAssistant.AI.remove(server); else ServerAssistant.AI.put(server, originalService);
            var session = ServerAssistant.SESSIONS.get(server);
            if (session != null) session.readyAt.remove(owner.getUUID());
            cleanup(helpers, owner);
        }
        c.succeed();
    }

    @GameTest public void blockedHelperSpawnLeavesItsErrorVisibleAndCanBeRetried(GameTestHelper c) {
        var owner = player(c, "menu-blocked");
        var helpers = AgentCompanions.get(c.getLevel().getServer());
        BlockPos feet = owner.blockPosition();
        try {
            // Leave only the owner's platform. Every searched helper position lacks solid ground.
            for (BlockPos floor : BlockPos.betweenClosed(feet.offset(-5, -1, -5), feet.offset(5, -1, 5)))
                if (!floor.equals(feet.below())) c.getLevel().setBlockAndUpdate(floor, Blocks.AIR.defaultBlockState());
            c.assertTrue(AgentCompanions.spawnPlace(owner) == null, "The test area cannot safely fit a helper");
            var gift = new ItemStack(Items.DIAMOND, 7);
            owner.getInventory().setItem(0, gift.copy());
            AgentMenu.open(owner);
            click(owner, AgentMenu.helperSlot(0));
            c.assertValueEqual(helpers.count(owner), 0, "A blocked spawn creates no ownership record");
            c.assertTrue(owner.containerMenu == owner.inventoryMenu,
                "The failed spawn leaves its clear-ground chat error unobscured by a reopened menu");
            c.assertTrue(owner.containerMenu.getCarried().isEmpty(), "The create icon never enters the cursor");
            c.assertTrue(ItemStack.isSameItemSameComponents(gift, owner.getInventory().getItem(0))
                && owner.getInventory().getItem(0).getCount() == gift.getCount(), "A failed spawn preserves the owner's items");
            for (BlockPos floor : BlockPos.betweenClosed(feet.offset(-5, -1, -5), feet.offset(5, -1, 5)))
                c.getLevel().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
            AgentMenu.open(owner);
            click(owner, AgentMenu.helperSlot(0));
            c.assertValueEqual(helpers.count(owner), 1, "The owner can reopen and retry after moving to suitable ground");
            c.assertTrue(owner.containerMenu == owner.inventoryMenu, "A successful retry reveals the golem");
        } finally { cleanup(helpers, owner); }
        c.succeed();
    }

    @GameTest public void helperMenuDismissalConfirmsAndRejectsStaleHelperIdentity(GameTestHelper c) {
        var owner = player(c, "menu-dismiss");
        var helpers = AgentCompanions.get(c.getLevel().getServer());
        try {
            helpers.spawn(owner, "keeper");
            AgentMenu.open(owner);
            detail(owner, "keeper");
            click(owner, AgentMenu.DISMISS);
            c.assertValueEqual(menu(owner).page, AgentMenu.Page.DISMISS, "Dismiss opens a separate confirmation screen");
            var dismissalLore = menu(owner).view.getItem(AgentMenu.CONFIRM).get(DataComponents.LORE).lines();
            String warning = dismissalLore.stream().map(Component::getString).collect(java.util.stream.Collectors.joining(" "));
            c.assertTrue(warning.contains("saved copy") && warning.contains("cannot see") && warning.contains("edited stats"),
                "Java confirmation warns about unseen copies and edited stats");
            c.assertTrue(warning.contains("after restart") && warning.contains("host review"), "Java confirmation explains retention after restart");
            c.assertValueEqual(helpers.count(owner), 1, "Opening confirmation leaves the helper alive");
            click(owner, AgentMenu.CANCEL);
            c.assertValueEqual(helpers.count(owner), 1, "Cancel preserves the helper");
            click(owner, AgentMenu.DISMISS);
            String original = helpers.owned(owner, "keeper").getKey();
            AbstractContainerMenu oldConfirm = menu(owner);
            helpers.dismiss(owner, "keeper");
            helpers.spawn(owner, "keeper");
            String replacement = helpers.owned(owner, "keeper").getKey();
            c.assertFalse(original.equals(replacement), "Replacement has a different persistent UUID");
            click(owner, AgentMenu.CONFIRM);
            c.assertTrue(helpers.owned(owner, "keeper") != null, "Stale confirmation cannot dismiss a replacement with the same name");
            c.assertValueEqual(menu(owner).page, AgentMenu.Page.ROSTER, "Stale identity returns to a fresh roster");
            oldConfirm.clicked(AgentMenu.CONFIRM, 0, ContainerInput.PICKUP, owner);
            c.assertTrue(helpers.owned(owner, "keeper") != null, "An old screen packet cannot touch the new screen");
            detail(owner, "keeper");
            click(owner, AgentMenu.DISMISS);
            click(owner, AgentMenu.CONFIRM);
            c.assertValueEqual(helpers.count(owner), 0, "Confirm dismisses exactly the current selected UUID");
        } catch (RuntimeException | Error failure) {
            System.err.println("[Infinity helper-menu test] dismissal confirmation failed");
            failure.printStackTrace();
            throw failure;
        } finally { cleanup(helpers, owner); }
        c.succeed();
    }

    /** Dispatches through the registered Brigadier tree; a refused requirement reports -1. */
    private int command(net.minecraft.commands.CommandSourceStack source, String text) {
        try { return source.getServer().getCommands().getDispatcher().execute(text, source); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException refused) { return -1; }
    }

    @GameTest public void dismissCommandOpensExactConfirmationAndRemovesOnlyAfterConfirm(GameTestHelper c) {
        var owner = player(c, "menu-dismiss-command");
        var other = player(c, "menu-dismiss-foreign");
        var helpers = AgentCompanions.get(c.getLevel().getServer());
        try {
            helpers.spawn(owner, "keeper"); helpers.spawn(other, "foreign");
            String id = helpers.owned(owner, "keeper").getKey(); String foreign = helpers.owned(other, "foreign").getKey();
            var body = helpers.loaded.get(UUID.fromString(id)); body.setHealth(37);
            c.assertValueEqual(command(owner.createCommandSourceStack(), "agent dismiss keeper"), 1, "The actual command opens a confirmation");
            c.assertTrue(menu(owner).page == AgentMenu.Page.DISMISS && id.equals(menu(owner).helperId),
                "The confirmation captures the caller's exact current helper identity");
            String warning = menu(owner).view.getItem(AgentMenu.CONFIRM).get(DataComponents.LORE).lines().stream()
                .map(Component::getString).collect(java.util.stream.Collectors.joining(" "));
            c.assertTrue(warning.contains("saved copy") && warning.contains("cannot see") && warning.contains("edited stats"),
                "The command shows the same unseen-copy warning as the menu");
            c.assertTrue(warning.contains("after restart") && warning.contains("host review"), "Java command confirmation explains retention after restart");
            c.assertTrue(helpers.data.agents.containsKey(id) && !body.isRemoved() && body.getHealth() == 37,
                "Nothing is removed before Confirm");
            click(owner, AgentMenu.CANCEL);
            c.assertTrue(helpers.data.agents.containsKey(id) && !body.isRemoved() && menu(owner).page == AgentMenu.Page.HELPER,
                "Keep preserves the helper and returns to its controls");

            c.assertValueEqual(command(owner.createCommandSourceStack(), "agent dismiss keeper"), 0, "An open screen refuses the command");
            c.assertTrue(menu(owner).page == AgentMenu.Page.HELPER, "The existing screen is left alone");
            owner.closeContainer();
            for (String name : new String[]{"missing", "foreign"}) {
                c.assertValueEqual(command(owner.createCommandSourceStack(), "agent dismiss " + name), 0, "An unknown or foreign name fails: " + name);
                c.assertTrue(owner.containerMenu == owner.inventoryMenu, "No misleading confirmation opens: " + name);
            }
            c.assertTrue(helpers.data.agents.containsKey(foreign) && helpers.data.agents.containsKey(id), "No owner can reach another owner's helper");

            command(owner.createCommandSourceStack(), "agent dismiss keeper");
            AbstractContainerMenu stale = owner.containerMenu;
            helpers.dismiss(owner, "keeper"); helpers.spawn(owner, "keeper");
            String replacement = helpers.owned(owner, "keeper").getKey();
            click(owner, AgentMenu.CONFIRM);
            c.assertTrue(!replacement.equals(id) && helpers.data.agents.containsKey(replacement) && menu(owner).page == AgentMenu.Page.ROSTER,
                "A command-opened confirmation cannot dismiss a same-name replacement");
            stale.clicked(AgentMenu.CONFIRM, 0, ContainerInput.PICKUP, owner);
            c.assertTrue(helpers.data.agents.containsKey(replacement), "A delayed old-window confirm cannot touch the replacement");
            owner.closeContainer();

            var replacementBody = helpers.loaded.get(UUID.fromString(replacement));
            command(owner.createCommandSourceStack(), "agent dismiss keeper");
            helpers.unload(replacementBody);
            click(owner, AgentMenu.CONFIRM);
            c.assertTrue(helpers.data.agents.containsKey(replacement) && !replacementBody.isRemoved(),
                "A changed loaded body invalidates the confirmation");
            helpers.load(replacementBody); owner.closeContainer();

            operator(owner, LevelBasedPermissionSet.ADMIN);
            c.assertTrue(command(owner.createCommandSourceStack(), "agent dismiss keeper") <= 0, "OP3 cannot use the command");
            c.assertValueEqual(command(helpers.server.createCommandSourceStack().withEntity(owner), "agent dismiss keeper"), 0,
                "Console execute-as cannot lend OP4 to the player");
            c.assertTrue(owner.containerMenu == owner.inventoryMenu && helpers.data.agents.containsKey(replacement), "Refused permission opens nothing");
            operator(owner, LevelBasedPermissionSet.OWNER);
            owner.setGameMode(GameType.SPECTATOR);
            c.assertValueEqual(command(owner.createCommandSourceStack(), "agent dismiss keeper"), 0, "A spectator cannot open the confirmation");
            owner.setGameMode(GameType.SURVIVAL);
            command(owner.createCommandSourceStack(), "agent dismiss keeper");
            operator(owner, LevelBasedPermissionSet.ADMIN);
            click(owner, AgentMenu.CONFIRM);
            c.assertTrue(helpers.data.agents.containsKey(replacement) && owner.containerMenu == owner.inventoryMenu,
                "Revoking OP4 after the command closes the confirmation without removing the helper");
            operator(owner, LevelBasedPermissionSet.OWNER);

            command(owner.createCommandSourceStack(), "agent dismiss keeper");
            ((AbstractContainerMenu) menu(owner)).clicked(AgentMenu.CONFIRM, 0, ContainerInput.PICKUP, other);
            c.assertTrue(helpers.data.agents.containsKey(replacement), "Another player cannot confirm the owner's dismissal");
            click(owner, AgentMenu.CONFIRM);
            c.assertTrue(!helpers.data.agents.containsKey(replacement) && replacementBody.isRemoved() && helpers.count(owner) == 0,
                "Explicit Confirm dismisses exactly the reviewed helper");
            c.assertTrue(helpers.data.agents.containsKey(foreign), "Another owner's helper is untouched");
        } catch (RuntimeException | Error failure) {
            System.err.println("[Infinity helper-menu test] dismiss command confirmation failed");
            failure.printStackTrace();
            throw failure;
        } finally { cleanup(helpers, owner); cleanup(helpers, other); }
        c.succeed();
    }

    @GameTest public void helperMenuRosterSnapshotCannotSelectAReplacement(GameTestHelper c) {
        var owner = player(c, "menu-snapshot");
        var helpers = AgentCompanions.get(c.getLevel().getServer());
        try {
            helpers.spawn(owner, "keeper");
            AgentMenu.open(owner);
            var stale = menu(owner);
            String original = helpers.owned(owner, "keeper").getKey();
            int slot = stale.choices.entrySet().stream().filter(entry -> entry.getValue().equals(original))
                .mapToInt(java.util.Map.Entry::getKey).findFirst().orElseThrow();
            helpers.dismiss(owner, "keeper");
            helpers.spawn(owner, "keeper");
            click(owner, slot);
            c.assertValueEqual(menu(owner).page, AgentMenu.Page.ROSTER, "A stale roster icon refreshes instead of selecting a replacement");
            c.assertTrue(helpers.owned(owner, "keeper") != null, "Refreshing does not alter the replacement");
        } catch (RuntimeException | Error failure) {
            System.err.println("[Infinity helper-menu test] roster snapshot failed");
            failure.printStackTrace();
            throw failure;
        } finally { cleanup(helpers, owner); }
        c.succeed();
    }

    @GameTest public void helperMenuNeverTransfersIconsOrPlayerItems(GameTestHelper c) {
        var owner = player(c, "menu-inventory");
        var helpers = AgentCompanions.get(c.getLevel().getServer());
        try {
            var item = new ItemStack(Items.DIAMOND, 7);
            item.set(DataComponents.CUSTOM_NAME, Component.literal("Keep this gift"));
            owner.getInventory().setItem(0, item);
            owner.getInventory().setItem(1, new ItemStack(Items.EMERALD, 11));
            var before = new ArrayList<ItemStack>();
            for (int slot = 0; slot < 36; slot++) before.add(owner.getInventory().getItem(slot).copy());
            helpers.spawn(owner, "keeper");
            AgentMenu.open(owner);
            detail(owner, "keeper");
            var screen = menu(owner);
            AbstractContainerMenu vanillaScreen = screen;
            var preview = screen.view.getItem(AgentMenu.profileSlot(0)).copy();
            for (var action : ContainerInput.values()) {
                if (action != ContainerInput.PICKUP && action != ContainerInput.QUICK_MOVE)
                    vanillaScreen.clicked(AgentMenu.profileSlot(0), 0, action, owner);
                vanillaScreen.clicked(54, 0, action, owner);
                vanillaScreen.clicked(-999, 0, action, owner);
            }
            vanillaScreen.setSelectedBundleItemIndex(AgentMenu.profileSlot(0), 1);
            c.assertTrue(vanillaScreen.quickMoveStack(owner, AgentMenu.profileSlot(0)).isEmpty(), "Shift-transfer never returns a preview stack");
            c.assertTrue(vanillaScreen.getCarried().isEmpty(), "No click action puts an icon on the cursor");
            c.assertTrue(ItemStack.isSameItemSameComponents(preview, screen.view.getItem(AgentMenu.profileSlot(0))),
                "Drag, throw, hotbar swap, and double-click leave the display icon intact");
            // Shift-click is a valid button activation, but still never transfers its icon.
            vanillaScreen.clicked(AgentMenu.profileSlot(0), 0, ContainerInput.QUICK_MOVE, owner);
            c.assertValueEqual(helpers.owned(owner, "keeper").getValue().profile(), AgentCompanions.Profile.PRIMITIVE,
                "Shift-click activates a profile without taking the sword icon");
            c.assertTrue(owner.containerMenu.getCarried().isEmpty(), "Button activation keeps the new screen cursor empty");
            for (int slot = 0; slot < 36; slot++) {
                var after = owner.getInventory().getItem(slot);
                c.assertTrue(ItemStack.isSameItemSameComponents(before.get(slot), after), "Player inventory components unchanged at " + slot);
                c.assertValueEqual(after.getCount(), before.get(slot).getCount(), "Player inventory count unchanged at " + slot);
            }
            AbstractContainerMenu current = menu(owner);
            current.setCarried(new ItemStack(Items.GOLD_INGOT, 2));
            current.clicked(AgentMenu.STAY, 0, ContainerInput.PICKUP, owner);
            c.assertValueEqual(helpers.owned(owner, "keeper").getValue().mode(), AgentCompanions.Mode.FOLLOW,
                "A nonempty cursor blocks button activation");
            c.assertValueEqual(current.getCarried().getCount(), 2, "Rejected click does not consume the existing cursor");
            current.setCarried(ItemStack.EMPTY);
        } catch (RuntimeException | Error failure) {
            System.err.println("[Infinity helper-menu test] inventory isolation failed");
            failure.printStackTrace();
            throw failure;
        } finally { cleanup(helpers, owner); }
        c.succeed();
    }

    @GameTest public void helperMenuCeasefireClearsOnlyItsOwnersPlayerOrder(GameTestHelper c) {
        var owner = player(c, "menu-hive");
        var other = player(c, "menu-otherhive");
        var target = player(c, "menu-target");
        var helpers = AgentCompanions.get(c.getLevel().getServer());
        try {
            helpers.spawn(owner, "combat");
            helpers.spawn(other, "combat");
            helpers.profile(owner, "combat", AgentCompanions.Profile.PRIMITIVE);
            helpers.profile(other, "combat", AgentCompanions.Profile.ULTIMATE_FINALS);
            c.assertTrue(helpers.assignPlayerTarget(owner, target), "First owner's exact player order is eligible");
            c.assertTrue(helpers.assignPlayerTarget(other, target), "Second owner's order stays independently scoped");
            AgentMenu.open(owner);
            AbstractContainerMenu screen = owner.containerMenu;
            c.assertTrue(menu(owner).view.getItem(22).getHoverName().getString().contains(target.getGameProfile().name()),
                "Compass shows the approved target name");
            click(owner, AgentMenu.CEASEFIRE);
            c.assertTrue(owner.containerMenu == screen, "Ceasefire refreshes the existing container without close/open packets");
            c.assertTrue(menu(owner).view.getItem(22).getHoverName().getString().contains("none"), "The refreshed compass immediately removes the stopped player target");
            c.assertFalse(helpers.playerTargetStatus(owner).contains(target.getGameProfile().name()),
                "The actual ceasefire menu click clears its owner's order");
            c.assertTrue(helpers.playerTargetStatus(other).contains(target.getGameProfile().name()),
                "Another owner's active order is preserved");
            c.assertTrue(owner.containerMenu.getCarried().isEmpty(), "Ceasefire banner never transfers");
        } finally { helpers.ceasefire(owner); helpers.ceasefire(other); cleanup(helpers, owner); cleanup(helpers, other); cleanup(helpers, target); }
        c.succeed();
    }

    @GameTest public void bringHereMenuPreservesHelperClearsCombatAndRejectsOldClicks(GameTestHelper c) {
        var owner = player(c, "menu-recall");
        var target = player(c, "menu-recall-target");
        var helpers = AgentCompanions.get(c.getLevel().getServer());
        var actions = AgentActions.get(helpers.server);
        try {
            helpers.spawn(owner, "returning");
            helpers.profile(owner, "returning", AgentCompanions.Profile.PRIMITIVE);
            var id = UUID.fromString(helpers.owned(owner, "returning").getKey());
            var helper = helpers.loaded.get(id);
            helper.setPos(owner.position().add(6, 0, 0));
            helper.setHealth(73);
            c.assertTrue(helpers.assignPlayerTarget(owner, target), "An existing player combat order is active before recall");
            actions.target(owner, target);
            var proposal = actions.data.proposals.values().stream()
                .filter(value -> value.owner.equals(owner.getStringUUID()) && value.action == AgentActions.Action.TARGET && value.active()).findFirst().orElse(null);
            c.assertTrue(proposal != null, "A real pending target proposal exists before recall; eligibility="
                + helpers.targetEligibility(owner, target) + "; queueFull=" + actions.queueFull(owner));
            c.assertValueEqual(AgentMenu.open(owner), 1, "Owner can open its recall roster");
            detail(owner, "returning");
            var screen = menu(owner);
            c.assertTrue(screen.view.getItem(AgentMenu.RECALL).getHoverName().getString().startsWith("Bring here"), "Loaded helper has a visible manual recall button");
            var original = helper.position();
            ((AbstractContainerMenu) screen).clicked(AgentMenu.RECALL, 0, ContainerInput.PICKUP, target);
            c.assertValueEqual(helper.position(), original, "Another player cannot activate the owner's recall menu");
            click(owner, AgentMenu.RECALL);
            c.assertTrue(owner.containerMenu == owner.inventoryMenu, "Recall closes the menu so the owner can see the returned helper");
            c.assertTrue(helpers.loaded.get(id) == helper && helper.distanceToSqr(owner) < 36, "Same-world recall keeps the existing helper and moves it beside its owner");
            c.assertValueEqual(helper.getHealth(), 73f, "Menu recall preserves current health");
            c.assertTrue(!helpers.playerTargets.containsKey(owner.getUUID()), "Recall clears the owner's old approved player target");
            c.assertValueEqual(proposal.state, AgentActions.State.CANCELLED, "Recall also cancels pending target approvals");
            c.assertTrue(helper.getTarget() == null && helper.getNavigation().isDone(), "Old combat and path targets are stopped");
            var recalledPosition = helper.position();
            ((AbstractContainerMenu) screen).clicked(AgentMenu.RECALL, 0, ContainerInput.PICKUP, owner);
            c.assertValueEqual(helper.position(), recalledPosition, "Delayed old-window recall packet cannot trigger another transfer");
            c.assertValueEqual(helpers.count(owner), 1, "Recall does not replace or duplicate roster members");
        } catch (RuntimeException | Error failure) {
            System.err.println("[Infinity helper-menu test] bring-here workflow failed");
            failure.printStackTrace();
            throw failure;
        } finally {
            actions.ceasefire(owner);
            cleanup(helpers, owner); cleanup(helpers, target);
        }
        c.succeed();
    }

    @GameTest public void orderMenuQueuesReviewsAndApprovesWithoutRunningItsAction(GameTestHelper c) {
        var owner=player(c,"menu-orders");var helpers=AgentCompanions.get(c.getLevel().getServer());
        var queue=AgentActions.get(c.getLevel().getServer());
        try {
            AgentMenu.open(owner);click(owner,AgentMenu.ORDERS);
            c.assertTrue(owner.containerMenu instanceof AgentOrdersMenu.Handler,"Roster opens command-free order controls");
            click(owner,19);
            var pending=(AgentOrdersMenu.Handler)owner.containerMenu;
            c.assertValueEqual(pending.page,AgentOrdersMenu.Page.PENDING,"Proposing day opens pending orders");
            String id=pending.proposals.values().iterator().next();
            c.assertValueEqual(queue.data.proposals.get(id).state,AgentActions.State.PENDING,"A tap cannot approve its own proposal");
            click(owner,0);
            c.assertValueEqual(((AgentOrdersMenu.Handler)owner.containerMenu).page,AgentOrdersMenu.Page.REVIEW,"Exact action has a separate review");
            AbstractContainerMenu old=owner.containerMenu;click(owner,AgentOrdersMenu.APPROVE);
            c.assertValueEqual(queue.data.proposals.get(id).state,AgentActions.State.OWNER_APPROVED,"Confirm provides only the owner approval");
            old.clicked(AgentOrdersMenu.APPROVE,0,ContainerInput.QUICK_MOVE,owner);
            c.assertValueEqual(queue.data.proposals.get(id).state,AgentActions.State.OWNER_APPROVED,"Old clicks never supply Codex approval or execute");
            click(owner,0);click(owner,AgentOrdersMenu.CANCEL);
            c.assertValueEqual(queue.data.proposals.get(id).state,AgentActions.State.CANCELLED,"Owner can cancel from the review screen");
        } finally { for(var e:java.util.List.copyOf(queue.data.proposals.entrySet()))if(e.getValue().owner.equals(owner.getStringUUID())&&e.getValue().active())queue.cancel(owner,e.getKey());cleanup(helpers,owner); }
        c.succeed();
    }

    @GameTest public void orderMenuTargetSelectionCapturesSessionAndKeepsBothApprovals(GameTestHelper c) {
        var owner=player(c,"menu-target-order");var target=player(c,"menu-target-other");var helpers=AgentCompanions.get(c.getLevel().getServer());
        var queue=AgentActions.get(c.getLevel().getServer());
        try {
            target.setPos(new Vec3(owner.getX()+3,owner.getY(),owner.getZ()));
            helpers.spawn(owner,"attacker");helpers.profile(owner,"attacker",AgentCompanions.Profile.PRIMITIVE);
            AgentOrdersMenu.open(owner);click(owner,AgentOrdersMenu.TARGET);
            var menu=(AgentOrdersMenu.Handler)owner.containerMenu;
            var choice=menu.targets.entrySet().stream().filter(e->e.getValue().player()==target).mapToInt(java.util.Map.Entry::getKey).findFirst();
            // Other native tests can have connected players on earlier pages.
            // Select this exact session through the real pagination controls.
            int remainingPages=c.getLevel().getServer().getPlayerList().getPlayers().size()/45+1;
            while(choice.isEmpty()&&remainingPages-->0&&!menu.getSlot(AgentOrdersMenu.NEXT).getItem().isEmpty()) {
                click(owner,AgentOrdersMenu.NEXT);menu=(AgentOrdersMenu.Handler)owner.containerMenu;
                choice=menu.targets.entrySet().stream().filter(e->e.getValue().player()==target).mapToInt(java.util.Map.Entry::getKey).findFirst();
            }
            c.assertTrue(choice.isPresent(),"The paginated target picker includes the exact connected target session");
            int slot=choice.orElseThrow();
            click(owner,slot);
            var pending=(AgentOrdersMenu.Handler)owner.containerMenu;
            String id=pending.proposals.values().iterator().next();
            c.assertValueEqual(queue.data.proposals.get(id).targetUuid,target.getStringUUID(),"Menu captured the exact target");
            c.assertTrue(helpers.playerTargets.get(owner.getUUID())==null,"Proposing the target does not start combat");
            click(owner,0);click(owner,AgentOrdersMenu.APPROVE);
            c.assertValueEqual(queue.data.proposals.get(id).state,AgentActions.State.OWNER_APPROVED,"Owner confirmation alone still waits");
            c.assertTrue(helpers.playerTargets.get(owner.getUUID())==null,"Menu cannot bypass Codex's separate approval");
            operator(owner,LevelBasedPermissionSet.ADMIN);
            click(owner,0);
            c.assertTrue(owner.containerMenu==owner.inventoryMenu,"Deop closes the order menu immediately");
        } finally { operator(owner,LevelBasedPermissionSet.OWNER);queue.ceasefire(owner);cleanup(helpers,owner);cleanup(helpers,target); }
        c.succeed();
    }

}
