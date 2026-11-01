package dev.convergence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/** Java chest controls; Bedrock uses native touch forms rather than simulated inventory clicks. */
final class AgentMenu {
    static final int FOLLOW = 28, GUARD = 30, STAY = 32, INFO = 34, CEASEFIRE = 37, ASK = 39, RECALL = 41, DISMISS = 43, BACK = 49;
    static final int CONFIRM = 11, CANCEL = 15;
    static final int ORDERS = 19;
    static final int FORMATION = 46;
    static final int SQUAD_PRIMITIVE = 48, SQUAD_REGULAR = 50, SQUAD_ULTIMATE = 52;
    static final int ROSTER_PAGE_SIZE = 6, PREVIOUS_PAGE = 45, NEXT_PAGE = 53;
    static final int ENABLE_COMBOS = 22;
    static final int BATCH_CREATE = 7;
    static final int GPT_PLAN = 25;
    static final String ASK_QUESTION = "Explain my helper's current state and suggest what I should do next.";
    static final String DISMISS_WARNING = "Dismiss removes this helper's identity. A saved copy, even one you cannot see, may be deleted with its edited stats this session. Copies first loaded after restart are kept for host review. Choose Keep to cancel.";
    enum Page { ROSTER, HELPER, DISMISS }

    private AgentMenu() {}

    static boolean allowed(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.hasDisconnected() && !player.isSpectator()
            && player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player
            && AgentCompanions.operator(player.createCommandSourceStack());
    }

    static int helperSlot(int index) { return 10 + index; }
    static int profileSlot(int index) { return 10 + index; }

    static void icon(SimpleContainer view, int slot, Item item, String text) {
        var stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(text));
        view.setItem(slot, stack);
    }

    static void description(SimpleContainer view, int slot, String text) {
        var lines = new java.util.ArrayList<Component>();
        var line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (!line.isEmpty() && line.length() + word.length() + 1 > 43) {
                lines.add(Component.literal(line.toString())); line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(Component.literal(line.toString()));
        view.getItem(slot).set(DataComponents.LORE, new ItemLore(lines));
    }

    /** Shared, read-only facts for Java's status action and its tooltip. */
    static String helperInfo(ServerPlayer owner, String id, AgentCompanions.Agent agent) {
        String summary = HelperCombatStatus.summary(owner, id, agent);
        if (owner == null || agent == null || id == null) return summary;
        var helpers = AgentCompanions.get(owner.level().getServer());
        if (helpers.data.agents.get(id) != agent || !agent.owner().equals(owner.getStringUUID())) return summary;
        UUID helperId;
        try { helperId = UUID.fromString(id); }
        catch (IllegalArgumentException invalid) { return summary; }
        var golem = helpers.loaded.get(helperId);
        boolean live = golem != null && golem.isAlive() && !golem.isRemoved();
        return summary + "\nLocation: " + AgentCompanions.location(golem, agent)
            + "\nHP: " + (live ? Math.round(golem.getHealth()) + "/" + Math.round(golem.getMaxHealth()) : "unloaded")
            + (helpers.unresolvedCreation(id) ? "\n" + helpers.creationReview(id) : "");
    }

    private static void statusButton(SimpleContainer view, ServerPlayer owner, String id, AgentCompanions.Agent agent) {
        icon(view, INFO, Items.SPYGLASS, "Live status • combat, location and health");
        description(view, INFO, helperInfo(owner, id, agent));
    }

    static Item profileIcon(AgentCompanions.Profile profile) {
        return switch (profile) {
            case PRIMITIVE -> Items.WOODEN_SWORD;
            case REGULAR -> Items.IRON_SWORD;
            case ULTIMATE_FINALS -> Items.NETHERITE_SWORD;
            case DEBUG -> Items.SPYGLASS;
            case CLI -> Items.REPEATER;
            case API -> Items.AMETHYST_SHARD;
        };
    }

    static String nextName(AgentCompanions helpers, ServerPlayer owner) {
        for (int index = 1; index <= AgentCompanions.LIMIT; index++) {
            String name = "helper-" + index;
            if (helpers.owned(owner, name) == null) return name;
        }
        return null;
    }

    static int open(ServerPlayer player) { return open(player, Page.ROSTER, null); }

    /**
     * {@code /agent dismiss <name>}: opens the edition's confirmation for the
     * caller's exact current helper. Nothing is removed until Confirm; the
     * confirmation screen rechecks that same record and loaded body.
     */
    static int confirmDismiss(ServerPlayer player, String name) {
        if (!allowed(player)) {
            player.sendSystemMessage(Component.literal("Helper controls require a living, non-spectator OP4 owner. Nothing was dismissed."));
            return 0;
        }
        if (CrossplaySupport.bedrock(player)) return BedrockAgentMenu.confirmDismiss(player, name);
        if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) {
            player.sendSystemMessage(Component.literal("Close your current screen and empty the cursor before dismissing a helper. Nothing was dismissed."));
            return 0;
        }
        var entry = AgentCompanions.validName(name) ? AgentCompanions.get(player.level().getServer()).owned(player, name) : null;
        if (entry == null) {
            player.sendSystemMessage(Component.literal("No helper named " + name + " belongs to you. Nothing was dismissed."));
            return 0;
        }
        return open(player, Page.DISMISS, entry.getKey(), 0);
    }

    private static int open(ServerPlayer player, Page page, String helperId) {
        return open(player, page, helperId, 0);
    }

    private static Map<String, AgentCompanions.Agent> roster(AgentCompanions helpers, ServerPlayer owner) {
        var result = new LinkedHashMap<String, AgentCompanions.Agent>();
        helpers.data.agents.entrySet().stream()
            .filter(entry -> entry.getValue().owner().equals(owner.getStringUUID()))
            .sorted(java.util.Comparator.comparing(entry -> entry.getValue().name()))
            .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    static int rosterPages(int count) {
        int slots = Math.max(1, Math.min(AgentCompanions.LIMIT, count + 1));
        return (slots + ROSTER_PAGE_SIZE - 1) / ROSTER_PAGE_SIZE;
    }

    private static void pageButtons(SimpleContainer view, int page, int pages) {
        if (page > 0) icon(view, PREVIOUS_PAGE, Items.ARROW, "Previous page • " + page + "/" + pages);
        if (page + 1 < pages) icon(view, NEXT_PAGE, Items.ARROW, "Next page • " + (page + 2) + "/" + pages);
    }

    private static int open(ServerPlayer player, Page page, String helperId, int requestedRosterPage) {
        if (!allowed(player)) {
            player.sendSystemMessage(Component.literal("Helper controls require a living, non-spectator OP4 owner."));
            return 0;
        }
        if (player.containerMenu != player.inventoryMenu
            || !player.containerMenu.getCarried().isEmpty()) {
            player.sendSystemMessage(Component.literal("Close your current screen and empty the cursor before opening helpers."));
            return 0;
        }
        if (CrossplaySupport.bedrock(player)) return BedrockAgentMenu.open(player);
        var helpers = AgentCompanions.get(player.level().getServer());
        var selected = helperId == null ? null : helpers.data.agents.get(helperId);
        if (page != Page.ROSTER && (selected == null || !selected.owner().equals(player.getStringUUID()))) {
            player.sendSystemMessage(Component.literal("That helper is no longer in your roster."));
            page = Page.ROSTER;
            helperId = null;
        }
        var view = new SimpleContainer(54);
        var choices = new LinkedHashMap<Integer, String>();
        var savedRoster = roster(helpers, player);
        int pages = rosterPages(savedRoster.size());
        int rosterPage = Math.max(0, Math.min(requestedRosterPage, pages - 1));
        String title;
        if (page == Page.ROSTER) {
            title = "Your Helper Squad • " + (rosterPage + 1) + "/" + pages;
            icon(view, 4, Items.IRON_GOLEM_SPAWN_EGG,
                "Helpers: " + helpers.count(player) + "/" + AgentCompanions.LIMIT);
            batchButton(view, helpers, player);
            icon(view,GPT_PLAN,Items.WRITABLE_BOOK,"Ask GPT for a squad plan");
            description(view,GPT_PLAN,"Choose a goal to send explicitly through the configured GPT planner. Plans enter Orders for your approval and separate live Codex review. Opening this screen sends nothing.");
            var entries = java.util.List.copyOf(savedRoster.entrySet());
            if (entries.isEmpty()) description(view, 4, helpers.remaining(player) > 0
                ? "No helpers yet. Tap the green Create your first helper button."
                : "The server's helper capacity is full, including saved unloaded helpers. Your existing helpers remain available.");
            else description(view, 4, "Page " + (rosterPage + 1) + " of " + pages + ". Use Next page and Previous page to find every saved helper.");
            for (int local = 0; local < ROSTER_PAGE_SIZE; local++) {
                int index = rosterPage * ROSTER_PAGE_SIZE + local;
                int slot = helperSlot(local);
                if (index >= AgentCompanions.LIMIT) break;
                if (index < entries.size()) {
                    var entry = entries.get(index);
                    var agent = entry.getValue();
                    choices.put(slot, entry.getKey());
                    rosterIcon(view, slot, helpers, entry.getKey(), agent);
                } else {
                    creationButton(view, slot, index, entries.isEmpty(), helpers.remaining(player) > 0);
                }
            }
            movement(view, "Squad");
            icon(view,RECALL,Items.ENDER_PEARL,"Bring your loaded squad here");
            description(view,RECALL,"Moves existing loaded helpers to clear ground beside you. Keeps health, stats and profiles. Clears chosen mob and player orders. Unloaded helpers stay saved.");
            icon(view, INFO, Items.BOOK, "Help • chat and approval commands");
            hive(view, helpers, player);
            squadProfiles(view, helpers, player);
            emptySquadControls(view, helpers, player);
            icon(view, BACK, Items.ARROW, "Back to Infinity Menu");
            pageButtons(view, rosterPage, pages);
        } else {
            title = selected.name() + (page == Page.DISMISS ? " • Dismiss?" : " • Helper");
            icon(view, 4, Items.IRON_INGOT, selected.name() + " • " + selected.profile().label());
            if (page == Page.DISMISS) {
                icon(view, CONFIRM, Items.DYE.red(), "Confirm dismiss " + selected.name());
                description(view, CONFIRM, DISMISS_WARNING);
                icon(view, CANCEL, Items.DYE.lime(), "Keep this helper");
            } else {
                profileChoices(view, selected);
                movement(view, selected.name());
                statusButton(view, player, helperId, selected);
                askButton(view, player);
                recallButton(view);
                icon(view, DISMISS, Items.DYE.red(), "Dismiss helper • confirmation required");
                hive(view, helpers, player);
                comboButton(view, helpers, player, helperId);
            }
            icon(view, BACK, Items.ARROW, "Back to your squad");
        }
        Page openedPage = page;
        String openedId = helperId;
        player.openMenu(new SimpleMenuProvider((sync, inventory, who) ->
            new Handler(sync, inventory, view, player, openedPage, openedId, choices, rosterPage, savedRoster), Component.literal(title)));
        return 1;
    }

    private static void movement(SimpleContainer view, String label) {
        icon(view, FOLLOW, Items.LEAD, label + " • follow");
        icon(view, GUARD, Items.SHIELD, label + " • guard current area");
        icon(view, STAY, Items.REDSTONE_TORCH, label + " • stay / pause");
    }

    private static void batchButton(SimpleContainer view, AgentCompanions helpers, ServerPlayer owner) {
        int free = helpers.remaining(owner);
        icon(view, BATCH_CREATE, free > 0 ? Items.IRON_GOLEM_SPAWN_EGG : Items.BARRIER,
            free > 0 ? "Create helpers in a batch • " + free + " free slots" : "Helper capacity full");
        description(view, BATCH_CREATE, free > 0
            ? "Choose any quantity from 1 to your remaining capacity, then a profile. One Spawn activation creates the whole batch. The owner and server caps include unloaded helpers."
            : "No helper slots are free. Saved unloaded helpers count toward the owner and server limits.");
    }

    private static void creationButton(SimpleContainer view, int slot, int index, boolean emptyRoster, boolean free) {
        icon(view, slot, free ? Items.DYE.lime() : Items.BARRIER, free
            ? emptyRoster && index == 0 ? "Create your first helper" : "Create a helper • slot " + (index + 1)
            : "Helper capacity full");
        description(view, slot, free
            ? "Tap to summon a named AI player avatar beside you. Stand on clear solid ground."
            : "No helper slots are free. Saved unloaded helpers count toward the owner and server limits.");
    }

    private static void emptySquadControls(SimpleContainer view, AgentCompanions helpers, ServerPlayer owner) {
        if (helpers.count(owner) != 0) return;
        boolean free = helpers.remaining(owner) > 0;
        for (int slot : new int[]{FOLLOW, GUARD, STAY, RECALL, FORMATION, SQUAD_PRIMITIVE, SQUAD_REGULAR, SQUAD_ULTIMATE}) {
            icon(view, slot, Items.BARRIER, free ? "Create a helper first" : "No helpers • capacity full");
            description(view, slot, free
                ? "Your roster is empty. Select the green Create your first helper button above. Nothing spawns automatically."
                : "Your roster is empty and the server has no helper slots free, including saved unloaded helpers.");
        }
    }

    private static void askButton(SimpleContainer view, ServerPlayer owner) {
        boolean codex = ServerAssistant.codexEnabled(owner.level().getServer());
        icon(view, ASK, Items.WRITABLE_BOOK, codex ? "Ask Codex" : "Ask helper");
        description(view, ASK, (codex ? "Sends limited Minecraft facts to Codex using the host's signed-in account. "
            : "Uses the configured AI or local helper. Configured AI receives limited Minecraft facts. ")
            + "Explains this helper's state and suggests a next step. Answers cannot execute commands.");
    }

    private static void recallButton(SimpleContainer view) {
        icon(view, RECALL, Items.ENDER_PEARL, "Bring here • then follow");
        description(view, RECALL, "Moves this existing loaded helper beside you, even from another mode. Keeps health, stats and profile. Clears your squad's chosen mob and player orders and pending target approvals. Unloaded helpers must first be loaded by visiting their area.");
    }

    private static void comboButton(SimpleContainer view, AgentCompanions helpers, ServerPlayer owner, String id) {
        var golem = helpers.loaded.get(UUID.fromString(id));
        boolean ready = golem != null && golem.isAlive() && !golem.isRemoved() && golem.level() == owner.level();
        icon(view, ENABLE_COMBOS, ready ? Items.FEATHER : Items.BARRIER,
            ready ? "Enable combos and follow me" : "Load this helper here to enable combos");
        description(view, ENABLE_COMBOS, ready
            ? "Sets this same helper to Ultimate Finals and Follow. Keeps its UUID, health, edited stats and current position. No helper is created or teleported. Hostile defense and chosen mob targets use normal combat checks; player targets still need both approvals."
            : "Return near this helper to load it, or use Bring here for an existing loaded helper in another world. Its saved identity and stats stay intact.");
    }

    private static void rosterIcon(SimpleContainer view, int slot, AgentCompanions helpers, String id, AgentCompanions.Agent agent) {
        icon(view, slot, Items.IRON_INGOT,
            agent.name() + " • " + agent.profile().label() + " • " + agent.mode().name().toLowerCase(java.util.Locale.ROOT));
        var golem = helpers.loaded.get(UUID.fromString(id));
        description(view, slot, helpers.brief(helpers.server.getPlayerList().getPlayer(UUID.fromString(agent.owner())),id,agent)+" • "+AgentCompanions.location(golem, agent)
            + (golem != null && golem.isAlive() && !golem.isRemoved()
                ? " • select, then Bring here to move it beside you" : " • return nearby to load this helper"));
    }

    private static void profileChoices(SimpleContainer view, AgentCompanions.Agent selected) {
        var profiles = AgentCompanions.Profile.values();
        for (int index = 0; index < profiles.length; index++) {
            var profile = profiles[index];
            icon(view, profileSlot(index), profileIcon(profile),
                (selected.profile() == profile ? "Selected: " : "Choose: ") + profile.label());
            description(view, profileSlot(index), profile.description());
        }
    }

    private static void squadProfiles(SimpleContainer view, AgentCompanions helpers, ServerPlayer owner) {
        var profiles = new AgentCompanions.Profile[]{AgentCompanions.Profile.PRIMITIVE, AgentCompanions.Profile.REGULAR, AgentCompanions.Profile.ULTIMATE_FINALS};
        var slots = new int[]{SQUAD_PRIMITIVE, SQUAD_REGULAR, SQUAD_ULTIMATE};
        for (int i = 0; i < profiles.length; i++) {
            var profile = profiles[i];
            long selected = helpers.data.agents.values().stream().filter(agent -> agent.owner().equals(owner.getStringUUID()) && agent.profile() == profile).count();
            icon(view, slots[i], profileIcon(profile), "Set squad: " + profile.label() + " • " + selected + "/" + helpers.count(owner));
            description(view, slots[i], "Changes all your saved helpers to this combat profile, including passive and unloaded helpers. Movement, health and edited stats stay unchanged. Tap Follow to resume Stay helpers. Player targeting still needs both approvals. " + profile.description());
        }
    }

    private static void hive(SimpleContainer view, AgentCompanions helpers, ServerPlayer owner) {
        icon(view,FORMATION,Items.COMPASS,"Follow formation: "+AgentCompanions.formation(owner));
        description(view,FORMATION,"Cycle Escort, Wedge and Ring. Spreads idle followers onto safe loaded ground. Combat and target approvals stay separate.");
        icon(view,ORDERS,Items.WRITABLE_BOOK,"Orders • targets and approvals");
        description(view,ORDERS,"Choose a nearby mob target or review a player target and server action without typing. Player attacks and server-changing AI orders still need owner and live Codex approvals.");
        icon(view, 22, Items.COMPASS, HelperCombatStatus.playerOrderSummary(owner));
        description(view, 22, "Use Orders to choose a nearby mob target. Player targets still require owner and live Codex approval.");
        icon(view, CEASEFIRE, Items.BANNER.white(), "Ceasefire • clear mob/player targets and queued orders");
    }

    static final class Handler extends ChestMenu {
        final SimpleContainer view;
        final ServerPlayer owner;
        final ServerGamePacketListenerImpl ownerConnection;
        final UUID ownerId;
        final Page page;
        final String helperId;
        final Map<Integer, String> choices;
        final net.minecraft.world.level.Level world;
        final int rosterPage;
        Map<String, AgentCompanions.Agent> rosterSnapshot;
        AgentCompanions.Agent selectedSnapshot;
        net.minecraft.world.entity.animal.golem.IronGolem selectedEntity;

        Handler(int sync, Inventory inventory, SimpleContainer view, ServerPlayer owner,
                Page page, String helperId, Map<Integer, String> choices, int rosterPage,
                Map<String, AgentCompanions.Agent> savedRoster) {
            super(MenuType.GENERIC_9x6, sync, inventory, view, 6);
            this.view = view;
            this.owner = owner;
            this.ownerConnection = owner.connection;
            this.ownerId = owner.getUUID();
            this.page = page;
            this.helperId = helperId;
            this.choices = Map.copyOf(choices);
            this.world = owner.level();
            this.rosterPage = rosterPage;
            this.rosterSnapshot = Map.copyOf(savedRoster);
            captureSelection(AgentCompanions.get(owner.level().getServer()));
        }

        @Override public boolean stillValid(Player player) {
            return player == owner && owner.containerMenu == this && owner.level() == world
                && ownerId.equals(player.getUUID()) && owner.connection == ownerConnection && allowed(owner);
        }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public void setSelectedBundleItemIndex(int slot, int selected) { }

        private void reopen(Page next, String id) {
            owner.closeContainer();
            open(owner, next, id, rosterPage);
        }

        private void captureSelection(AgentCompanions helpers) {
            selectedSnapshot = helperId == null ? null : helpers.data.agents.get(helperId);
            selectedEntity = helperId == null ? null : helpers.loaded.get(UUID.fromString(helperId));
        }

        private boolean sameRoster(AgentCompanions helpers) {
            var current = roster(helpers, owner);
            return current.size() == rosterSnapshot.size() && rosterSnapshot.entrySet().stream()
                .allMatch(entry -> current.get(entry.getKey()) == entry.getValue());
        }

        private boolean sameSelection(AgentCompanions helpers) {
            return selectedSnapshot != null && helpers.data.agents.get(helperId) == selectedSnapshot
                && helpers.loaded.get(UUID.fromString(helperId)) == selectedEntity;
        }

        /** Stable buttons update slots without sending a close/open pair to Geyser.
         * Identity or page changes still get a fresh sync ID, rejecting old-window packets. */
        private void refresh() {
            if (owner.containerMenu != this) return;
            if (!stillValid(owner)) { owner.closeContainer(); return; }
            var helpers = AgentCompanions.get(owner.level().getServer());
            if (page == Page.ROSTER) {
                var currentRoster = roster(helpers, owner);
                if (!currentRoster.keySet().equals(rosterSnapshot.keySet())) { reopen(Page.ROSTER, null); return; }
                rosterSnapshot = Map.copyOf(currentRoster);
                icon(view, 4, Items.IRON_GOLEM_SPAWN_EGG, "Helpers: " + helpers.count(owner) + "/" + AgentCompanions.LIMIT);
                batchButton(view, helpers, owner);
                for (var choice : choices.entrySet()) {
                    var agent = selected(helpers, choice.getValue());
                    if (agent == null) { stale(); return; }
                    rosterIcon(view, choice.getKey(), helpers, choice.getValue(), agent);
                }
                for (int local = 0; local < ROSTER_PAGE_SIZE; local++) {
                    int index = rosterPage * ROSTER_PAGE_SIZE + local;
                    if (index >= AgentCompanions.LIMIT) break;
                    if (!choices.containsKey(helperSlot(local))) creationButton(view, helperSlot(local), index,
                        currentRoster.isEmpty(), helpers.remaining(owner) > 0);
                }
                hive(view, helpers, owner);
                squadProfiles(view, helpers, owner);
                emptySquadControls(view, helpers, owner);
            } else if (page == Page.HELPER) {
                var agent = selected(helpers, helperId);
                if (agent == null) { stale(); return; }
                icon(view, 4, Items.IRON_INGOT, agent.name() + " • " + agent.profile().label());
                profileChoices(view, agent);
                statusButton(view, owner, helperId, agent);
                hive(view, helpers, owner);
                askButton(view, owner);
                recallButton(view);
                comboButton(view, helpers, owner, helperId);
                captureSelection(helpers);
            }
            sendAllDataToRemote();
        }

        private AgentCompanions.Agent selected(AgentCompanions helpers, String id) {
            var agent = helpers.data.agents.get(id);
            return agent != null && agent.owner().equals(ownerId.toString()) ? agent : null;
        }

        private void stale() {
            owner.sendSystemMessage(Component.literal("Your helper roster changed. Choose the helper again."));
            reopen(Page.ROSTER, null);
        }

        private boolean refreshUnavailableCreation(AgentCompanions helpers, int slot) {
            boolean barrier = view.getItem(slot).is(Items.BARRIER);
            if (!barrier && helpers.remaining(owner) > 0) return false;
            // A disabled control cannot turn into a creation action under the
            // click that reveals new capacity. A fresh window also retires
            // duplicate packets sent while the old barrier was displayed.
            if (barrier && helpers.remaining(owner) > 0) reopen(Page.ROSTER, null);
            else refresh();
            return true;
        }

        @Override public void clicked(int clicked, int button, ContainerInput action, Player player) {
            // A packet for an old window cannot affect a newer screen or another owner.
            if (player != owner || owner.containerMenu != this) return;
            if (!stillValid(player)) { owner.closeContainer(); return; }
            if ((action != ContainerInput.PICKUP && action != ContainerInput.QUICK_MOVE)
                || button < 0 || button > 1 || !getCarried().isEmpty()) { sendAllDataToRemote(); return; }
            var helpers = AgentCompanions.get(owner.level().getServer());
            if (page == Page.ROSTER && !sameRoster(helpers)
                || page != Page.ROSTER && !sameSelection(helpers)) { stale(); return; }
            if (page == Page.ROSTER && helpers.count(owner) == 0
                    && (clicked == FOLLOW || clicked == GUARD || clicked == STAY || clicked == RECALL
                        || clicked == FORMATION || clicked == SQUAD_PRIMITIVE || clicked == SQUAD_REGULAR || clicked == SQUAD_ULTIMATE)) {
                owner.closeContainer();
                var message = Component.literal(helpers.remaining(owner) > 0
                    ? "Your roster is empty. Reopen AI Helpers and choose Create your first helper."
                    : "Your roster is empty and the server's helper capacity is full, including saved unloaded helpers.");
                owner.sendSystemMessage(message); owner.sendOverlayMessage(message);
                return;
            }
            if(clicked==FORMATION&&page!=Page.DISMISS){AgentCompanions.cycleFormation(owner);refresh();return;}
            if(clicked==ORDERS && page!=Page.DISMISS) { owner.closeContainer();AgentOrdersMenu.open(owner);return; }
            if (clicked == CEASEFIRE && page != Page.DISMISS) {
                AgentActions.get(helpers.server).ceasefire(owner);
                refresh();
                return;
            }
            if (page == Page.ROSTER) {
                if(clicked==GPT_PLAN) {owner.closeContainer();GptSquadMenu.open(owner);return;}
                if (clicked == BATCH_CREATE) {
                    if (refreshUnavailableCreation(helpers, BATCH_CREATE)) return;
                    owner.closeContainer(); HelperBatchMenu.open(owner); return;
                }
                if (clicked == PREVIOUS_PAGE || clicked == NEXT_PAGE) {
                    int targetPage = rosterPage + (clicked == NEXT_PAGE ? 1 : -1);
                    if (targetPage >= 0 && targetPage < rosterPages(helpers.count(owner))) {
                        owner.closeContainer(); open(owner, Page.ROSTER, null, targetPage);
                    } else sendAllDataToRemote();
                    return;
                }
                var squadProfile = clicked == SQUAD_PRIMITIVE ? AgentCompanions.Profile.PRIMITIVE
                    : clicked == SQUAD_REGULAR ? AgentCompanions.Profile.REGULAR
                    : clicked == SQUAD_ULTIMATE ? AgentCompanions.Profile.ULTIMATE_FINALS : null;
                if (squadProfile != null) { helpers.squadProfile(owner, squadProfile); refresh(); return; }
                if (clicked == BACK) { owner.closeContainer(); ServerMenu.open(owner); return; }
                if (clicked >= helperSlot(0) && clicked < helperSlot(ROSTER_PAGE_SIZE)) {
                    if (rosterPage * ROSTER_PAGE_SIZE + clicked - helperSlot(0) >= AgentCompanions.LIMIT) {
                        sendAllDataToRemote(); return;
                    }
                    String id = choices.get(clicked);
                    if (id != null) {
                        if (selected(helpers, id) == null) { stale(); return; }
                        reopen(Page.HELPER, id);
                    } else {
                        if (refreshUnavailableCreation(helpers, clicked)) return;
                        String name = nextName(helpers, owner);
                        // Reveal the nearby helper on success, and keep a failed spawn's
                        // explanation visible instead of hiding it behind another chest.
                        owner.closeContainer();
                        if (name != null) {
                            // An unresolved creation keeps its record but returns 0 and
                            // has already explained itself; never add a success over it.
                            if (helpers.spawn(owner, name) == 1 && helpers.owned(owner, name) != null) {
                                var message = Component.literal(name + " appeared beside you. Look for the named player avatar.");
                                owner.sendSystemMessage(message);
                                owner.sendOverlayMessage(message);
                            }
                        } else owner.sendSystemMessage(Component.literal("All helper slots are in use. Dismiss a helper to free one."));
                    }
                    return;
                }
                AgentCompanions.Mode movement = clicked == FOLLOW ? AgentCompanions.Mode.FOLLOW
                    : clicked == GUARD ? AgentCompanions.Mode.GUARD : clicked == STAY ? AgentCompanions.Mode.STAY : null;
                if (movement != null) {
                    helpers.squad(owner, movement);
                    refresh();
                    return;
                }
                if (clicked == INFO) {
                    owner.closeContainer();
                    owner.sendSystemMessage(Component.literal(
                        "Choose a helper and its profile, then tap Ask Codex or Ask helper for guidance. "
                        + "/agent ask <name> <message> lets you write your own question. "
                        + "Player targeting: /agent target <player>; /agent ceasefire stops it. "
                        + "CLI proposals: /agent pending, /agent approve <id>, /agent cancel <id>. "
                        + "Every server-changing proposal also waits for live Codex review; this menu cannot run it."));
                    return;
                }
                if(clicked==RECALL){owner.closeContainer();helpers.recallSquad(owner);return;}
            } else {
                if (clicked == BACK) { reopen(Page.ROSTER, null); return; }
                var agent = selected(helpers, helperId);
                if (agent == null) { stale(); return; }
                if (page == Page.DISMISS) {
                    if (clicked == CONFIRM) { helpers.dismiss(owner, agent.name()); reopen(Page.ROSTER, null); return; }
                    if (clicked == CANCEL) { reopen(Page.HELPER, helperId); return; }
                } else {
                    if (clicked == ENABLE_COMBOS) {
                        if (helpers.enableCombos(owner, agent.name())) refresh();
                        else {
                            owner.closeContainer();
                            var message = Component.literal("Combos need this existing helper loaded in your current world. Its saved identity and stats were kept.");
                            owner.sendSystemMessage(message); owner.sendOverlayMessage(message);
                        }
                        return;
                    }
                    var profiles = AgentCompanions.Profile.values();
                    for (int index = 0; index < profiles.length; index++) if (clicked == profileSlot(index)) {
                        helpers.profile(owner, agent.name(), profiles[index]);
                        refresh();
                        return;
                    }
                    AgentCompanions.Mode movement = clicked == FOLLOW ? AgentCompanions.Mode.FOLLOW
                        : clicked == GUARD ? AgentCompanions.Mode.GUARD : clicked == STAY ? AgentCompanions.Mode.STAY : null;
                    if (movement != null) { helpers.mode(owner, agent.name(), movement); refresh(); return; }
                    if (clicked == INFO) {
                        owner.closeContainer(); owner.sendSystemMessage(Component.literal(helperInfo(owner, helperId, agent))); return;
                    }
                    if (clicked == RECALL) { owner.closeContainer(); helpers.recall(owner, agent.name()); return; }
                    if (clicked == ASK) {
                        owner.closeContainer();
                        AgentChat.ask(owner.createCommandSourceStack(), agent.name(), ASK_QUESTION);
                        return;
                    }
                    if (clicked == DISMISS) { reopen(Page.DISMISS, helperId); return; }
                }
            }
            // Never delegate to the container: every stack is an icon, including on
            // drag, hotbar swap, throw, double-click, and player-inventory slots.
            sendAllDataToRemote();
        }
    }
}
