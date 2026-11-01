package dev.convergence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.golem.IronGolem;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.CustomForm;

/** Native touch controls share the existing helper mutators and exact-session form transport. */
final class BedrockAgentMenu {
    private BedrockAgentMenu() {}

    static int open(ServerPlayer owner) { return open(owner, HelperForms.sender(owner)); }
    static int open(ServerPlayer owner, Predicate<Form> sender) {
        if (!AgentMenu.allowed(owner) || !HelperForms.emptyScreen(owner)) {
            HelperForms.message(owner, "Close other screens before using helpers. A living OP4 owner is required.");
            return 0;
        }
        return roster(owner, "", sender);
    }

    private static AgentCompanions helpers(ServerPlayer owner) {
        return AgentCompanions.get(owner.level().getServer());
    }

    private static Map<String, AgentCompanions.Agent> snapshot(ServerPlayer owner) {
        var result = new LinkedHashMap<String, AgentCompanions.Agent>();
        helpers(owner).data.agents.entrySet().stream()
            .filter(e -> e.getValue().owner().equals(owner.getStringUUID()))
            .sorted(Comparator.comparing(e -> e.getValue().name()))
            .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    private static boolean current(ServerPlayer owner, Map<String, AgentCompanions.Agent> saved) {
        var now = snapshot(owner);
        return now.size() == saved.size() && saved.entrySet().stream().allMatch(e -> now.get(e.getKey()) == e.getValue());
    }

    private static boolean current(ServerPlayer owner, String id, AgentCompanions.Agent agent, IronGolem loaded) {
        return agent != null && agent.owner().equals(owner.getStringUUID())
            && helpers(owner).data.agents.get(id) == agent
            && helpers(owner).loaded.get(UUID.fromString(id)) == loaded;
    }

    private static IronGolem live(AgentCompanions helpers, String id) {
        var golem = helpers.loaded.get(UUID.fromString(id));
        return golem != null && golem.isAlive() && !golem.isRemoved()
            && AgentCompanions.isAgent(golem) && golem.level() instanceof ServerLevel level
            && level.getEntity(golem.getUUID()) == golem ? golem : null;
    }

    private static String withFeedback(String feedback, String text) {
        return feedback.isEmpty() ? text : feedback + "\n\n" + text;
    }

    private static int roster(ServerPlayer owner, String feedback, Predicate<Form> sender) {
        return roster(owner, feedback, sender, 0);
    }

    private static int pageFor(ServerPlayer owner, String id) {
        int index = new ArrayList<>(snapshot(owner).keySet()).indexOf(id);
        return Math.max(0, index) / AgentMenu.ROSTER_PAGE_SIZE;
    }

    private static int roster(ServerPlayer owner, String feedback, Predicate<Form> sender, int requestedPage) {
        var helpers = helpers(owner);
        var saved = snapshot(owner);
        var entries = List.copyOf(saved.entrySet());
        int pages = AgentMenu.rosterPages(saved.size());
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        var choices = new ArrayList<HelperForms.Choice>();
        int free = helpers.remaining(owner);
        if (free > 0) {
            choices.add(new HelperForms.Choice(saved.isEmpty() ? "Create your first helper" : "Create another helper",
                () -> create(owner, "", sender)));
            choices.add(new HelperForms.Choice("Create helpers in a batch • " + free + " free slots",
                () -> openBatch(owner, sender)));
        }
        int first = page * AgentMenu.ROSTER_PAGE_SIZE;
        for (var entry : entries.subList(Math.min(first, entries.size()), Math.min(first + AgentMenu.ROSTER_PAGE_SIZE, entries.size()))) {
            String id = entry.getKey(); var agent = entry.getValue();
            choices.add(new HelperForms.Choice(agent.name() + " • " + agent.profile().label()
                + (live(helpers, id) == null ? " • unloaded" : " • " + agent.mode().name().toLowerCase(java.util.Locale.ROOT))
                + (helpers.unresolvedCreation(id) ? " • creation unverified" : ""),
                () -> detail(owner, id, "", sender)));
        }
        if (page > 0) choices.add(new HelperForms.Choice("Previous page • " + page + "/" + pages,
            () -> roster(owner, "", sender, page - 1)));
        if (page + 1 < pages) choices.add(new HelperForms.Choice("Next page • " + (page + 2) + "/" + pages,
            () -> roster(owner, "", sender, page + 1)));
        if (!saved.isEmpty()) choices.add(new HelperForms.Choice("Squad controls • movement, profiles and recall", () -> squad(owner, "", sender)));
        choices.add(new HelperForms.Choice("Orders • targets and both approvals", () -> BedrockAgentOrdersMenu.open(owner, sender)));
        choices.add(new HelperForms.Choice("Ask GPT for a squad plan", () -> GptSquadMenu.openBedrock(owner,sender,GptSquadPlanner::request)));
        choices.add(new HelperForms.Choice("Back to Infinity Menu", () -> ServerMenu.open(owner)));
        String content = "Your helpers: " + saved.size() + "/" + AgentCompanions.LIMIT + ". Page " + (page + 1) + " of " + pages + ".\n"
            + (saved.isEmpty() ? helpers.creationRecoveryRequired
                ? "Helper creation is unavailable while ownership is frozen for host review."
                : free > 0
                ? "No helpers yet. Create one, then choose its profile. Nothing spawns just by opening this screen."
                : "The server's helper capacity is full, including saved unloaded helpers."
                : "Choose a named helper for its live status and controls. Unloaded identities remain reserved; their bodies may be elsewhere or may have died. These menus never load distant chunks.")
            + "\n\nRegular follows and guards. Ultimate Finals uses a real spear charge, elytra flight and a falling mace strike when the target and terrain allow it."
            + "\nChoose nearby mob targets through Orders. Player targets and server-changing AI orders still require your approval and separate live Codex review.\n" + HelperCombatStatus.squadSummary(owner);
        return HelperForms.show(owner, "Your Helper Squad", withFeedback(feedback, content), choices, () -> current(owner, saved), sender);
    }

    private static int create(ServerPlayer owner, String feedback, Predicate<Form> sender) {
        if (helpers(owner).remaining(owner) < 1) return roster(owner,
            "No helper slots are free. Saved unloaded helpers count toward both limits. Nothing was created.", sender);
        var saved = snapshot(owner);
        var choices = new ArrayList<HelperForms.Choice>();
        if (saved.size() < AgentCompanions.LIMIT) {
            for (var profile : creationProfiles())
                choices.add(new HelperForms.Choice("Create: " + profile.label(), () -> spawn(owner, profile, sender)));
        }
        choices.add(new HelperForms.Choice("Back to your squad", () -> roster(owner, "", sender)));
        String content = "Choose the new helper's profile. It appears on clear solid ground beside you; the screen closes on success so you can see it.\n"
            + "Regular: follows/guards and fights nearby hostile mobs.\nUltimate Finals: shared squad focus, native spear charge, brief elytra flight, then a falling mace strike.\n"
            + "Primitive seeks hostile mobs. Debug, CLI and API are passive physical profiles. CLI does not edit or run commands automatically; API never sends a question just by opening a screen.\n"
            + "Player attacks need both approvals. Maximum " + AgentCompanions.LIMIT + " per owner and " + AgentCompanions.GLOBAL_LIMIT + " server-wide, including unloaded helpers.";
        return HelperForms.show(owner, "Create a helper", withFeedback(feedback, content), choices, () -> current(owner, saved), sender);
    }

    private static List<AgentCompanions.Profile> creationProfiles() {
        return List.of(AgentCompanions.Profile.REGULAR, AgentCompanions.Profile.ULTIMATE_FINALS,
            AgentCompanions.Profile.PRIMITIVE, AgentCompanions.Profile.DEBUG, AgentCompanions.Profile.CLI, AgentCompanions.Profile.API);
    }

    static int openBatch(ServerPlayer owner, Predicate<Form> sender) {
        if (!AgentMenu.allowed(owner) || !HelperForms.emptyScreen(owner)) return 0;
        var request = new HelperBatchMenu.Request(owner);
        if (!request.valid(owner)) return roster(owner,
            "No helper slots are free. The owner and server limits include unloaded helpers. Nothing was created.", sender);
        var profiles = creationProfiles();
        var ticket = new HelperForms.Ticket(UUID.randomUUID(), new AdminStatsMenu.Session(owner), () -> request.valid(owner),
            owner.level().getServer().getTickCount() + 1800, sender);
        HelperForms.ACTIVE.put(owner.getUUID(), ticket);
        HelperForms.DELIVERIES.remove(owner.getUUID());
        var form = CustomForm.builder().title("Spawn helpers")
            .label("Choose 1–" + request.remaining + " new helpers and a profile. Submit creates the whole batch now on clear solid ground beside you.\n"
                + "Your existing helpers count toward the " + AgentCompanions.LIMIT + " owner limit and " + AgentCompanions.GLOBAL_LIMIT
                + " server limit, including unloaded helpers. Existing helpers keep their identities, health, stats and profiles.")
            .input("Number of helpers", "1–" + request.remaining, "1")
            .dropdown("Profile", profiles.stream().map(AgentCompanions.Profile::label).toList());
        form.validResultHandler(result -> HelperForms.response(ticket, () -> {
            int amount;
            String input = result.asInput(1);
            try { amount = Integer.parseInt(input == null ? "" : input.strip()); }
            catch (NumberFormatException invalid) {
                HelperForms.message(owner, "Enter a whole number from 1 to " + request.remaining + ". Nothing spawned.");
                openBatch(owner, sender); return;
            }
            int profile = result.asDropdown(2);
            if (amount < 1 || amount > request.remaining || profile < 0 || profile >= profiles.size()) {
                HelperForms.message(owner, "Choose a whole number from 1 to " + request.remaining + " and a listed profile. Nothing spawned.");
                openBatch(owner, sender); return;
            }
            request.spawn(owner, amount, profiles.get(profile));
        })).closedOrInvalidResultHandler(() -> cancelBatch(ticket));
        return HelperForms.send(ticket, form.build(), sender) ? 1 : 0;
    }

    private static void cancelBatch(HelperForms.Ticket ticket) {
        var server = ticket.actor().world().getServer();
        if (!server.isSameThread()) { server.execute(() -> cancelBatch(ticket)); return; }
        var id = ticket.actor().player().getUUID();
        HelperForms.ACTIVE.remove(id, ticket);
        HelperForms.DELIVERIES.computeIfPresent(id, (key, delivery) -> delivery.ticket() == ticket ? null : delivery);
    }

    private static void spawn(ServerPlayer owner, AgentCompanions.Profile profile, Predicate<Form> sender) {
        var helpers = helpers(owner);
        String name = AgentMenu.nextName(helpers, owner);
        if (name == null || helpers.count(owner) >= AgentCompanions.LIMIT) {
            roster(owner, "All your helper slots are in use. Dismiss one only if you want to free its slot.", sender); return;
        }
        if (helpers.data.agents.size() >= AgentCompanions.GLOBAL_LIMIT) {
            create(owner, "The server's helper slots are full, including unloaded helpers. Nothing was created.", sender); return;
        }
        boolean unresolvedBefore = helpers.creationUnresolved, frozenBefore = helpers.creationRecoveryRequired;
        int created = helpers.spawn(owner, name);
        var entry = helpers.owned(owner, name);
        if (created != 1 && !unresolvedBefore && helpers.creationUnresolved) {
            // Neither the roster record nor its absence shows whether a helper appeared.
            roster(owner, AgentCompanions.UNRESOLVED_CREATION + name + " may have appeared and was kept; its ownership needs host review. "
                + AgentCompanions.FROZEN, sender);
            return;
        }
        // Storage and frozen-ownership failures (spawn returns 0, or ownership
        // froze) are not fixed by moving; the chat notice gives the roster detail.
        if (helpers.creationRecoveryRequired || (entry == null && created != 1)) {
            roster(owner, helpers.creationRecoveryRequired
                ? (frozenBefore ? "Helper creation is blocked. " : "No helper entity spawned. ") + AgentCompanions.FROZEN
                : "Could not save helper ownership. Nothing spawned. Ask the host to check server storage before retrying.", sender);
            return;
        }
        if (entry == null || live(helpers, entry.getKey()) == null) {
            create(owner, "No helper appeared. Move onto clear solid ground with open space around you and try again. No existing helper was replaced.", sender); return;
        }
        int profileResult = helpers.profile(owner, name, profile);
        var saved = helpers.data.agents.get(entry.getKey());
        String selected = profileResult == 1 && saved != null && saved.profile() == profile
            ? profile.label() : (saved == null ? "profile unavailable" : saved.profile().label() + "; selected profile was not confirmed");
        var message = Component.literal(name + " appeared beside you • " + selected + ". Reopen AI Helpers to give orders.");
        owner.sendSystemMessage(message);
        owner.sendOverlayMessage(message);
        // The form response already closes the native form. Do not immediately
        // reopen a screen over the actual newly spawned helper.
    }

    private static int detail(ServerPlayer owner, String id, String feedback, Predicate<Form> sender) {
        var helpers = helpers(owner);
        var agent = helpers.data.agents.get(id);
        if (agent == null || !agent.owner().equals(owner.getStringUUID()))
            return roster(owner, "That helper is no longer in your roster. Choose it again.", sender);
        var captured = helpers.loaded.get(UUID.fromString(id));
        var golem = live(helpers, id);
        var choices = new ArrayList<HelperForms.Choice>();
        String comboUnavailable = golem == null
            ? captured == null ? "This helper is unloaded. Return near its saved location, then refresh this screen."
                : "This helper is unavailable. Refresh this screen to check its current state."
            : golem.level() != owner.level() ? "This helper is in another world. Use Bring this helper here, then enable combos."
                : null;
        String comboLabel = "Enable combos and follow me" + (comboUnavailable == null ? ""
            : golem == null ? captured == null ? " • load helper nearby first" : " • refresh helper status first"
                : " • bring helper here first");
        // Keep the main control visible even when its prerequisite is missing.
        // An unavailable tap explains that state without changing the helper.
        choices.add(new HelperForms.Choice(comboLabel, () -> {
            if (comboUnavailable != null) {
                detail(owner, id, "Combos unavailable: " + comboUnavailable + " No helper was changed.", sender);
                return;
            }
            boolean enabled = helpers.enableCombos(owner, agent.name());
            detail(owner, id, enabled
                ? "Combos enabled: Ultimate Finals and Follow. This same helper kept its position, health and edited stats."
                : "Could not enable combos: this existing helper must be loaded in your current world. Its saved identity and stats were kept.", sender);
        }));
        if (golem != null && golem.level() == owner.level()) {
            for (var mode : AgentCompanions.Mode.values()) choices.add(new HelperForms.Choice(
                switch (mode) { case FOLLOW -> "Follow me"; case GUARD -> "Guard this helper's current area"; case STAY -> "Stay / pause"; },
                () -> {
                    int result = helpers.mode(owner, agent.name(), mode);
                    var after = helpers.data.agents.get(id);
                    boolean applied = result == 1 && after != null && after != agent && after.mode() == mode;
                    detail(owner, id, applied ? "Movement updated: " + mode.name().toLowerCase(java.util.Locale.ROOT) + "."
                        : "Movement change was not confirmed. Check the in-game notice before retrying.", sender);
                }));
        }
        choices.add(new HelperForms.Choice("Choose profile • " + agent.profile().label(), () -> profiles(owner, id, sender)));
        if (golem != null) choices.add(new HelperForms.Choice("Bring this helper here • then follow", () -> {
            helpers.recall(owner, agent.name());
            if (helpers.data.agents.get(id) == agent)
                detail(owner, id, "Bring here could not be confirmed. The helper may have moved; check the in-game notice and its current status before retrying.", sender);
            else owner.sendOverlayMessage(Component.literal(agent.name() + " is beside you and following. Health, stats and profile were kept."));
        }));
        choices.add(new HelperForms.Choice("Refresh live status", () -> detail(owner, id, "Status refreshed.", sender)));
        choices.add(new HelperForms.Choice(ServerAssistant.codexEnabled(helpers.server) ? "Ask Codex • send this helper's facts" : "Ask helper • request guidance", () -> {
            AgentChat.ask(owner.createCommandSourceStack(), agent.name(), AgentMenu.ASK_QUESTION);
        }));
        choices.add(new HelperForms.Choice("Orders • targets and both approvals", () -> BedrockAgentOrdersMenu.open(owner, sender)));
        choices.add(new HelperForms.Choice("Ceasefire • clear mob and player targets", () -> {
            AgentActions.get(helpers.server).ceasefire(owner); detail(owner, id, "Mob and player targets and pending player-target orders cleared.", sender);
        }));
        choices.add(new HelperForms.Choice("Dismiss helper • review first", () -> dismiss(owner, id, sender)));
        choices.add(new HelperForms.Choice("Back to your squad", () -> roster(owner, "", sender, pageFor(owner, id))));
        String content = AgentMenu.helperInfo(owner, id, agent) + "\n\n" + agent.profile().description()
            + "\n" + (golem == null ? captured == null
                ? "Unloaded: return near its saved location to load it. Movement and recall are unavailable."
                : "Unavailable: refresh this screen to check this helper's current state. Movement and recall are unavailable."
                : golem.level() != owner.level() ? "In another world: use Bring here before giving movement orders." : "Movement applies to this helper only.")
            + "\nEnable combos and follow me changes this helper to Ultimate Finals and Follow, without creating or teleporting a helper. Choose nearby mob targets in Orders; player targets still need both approvals."
            + "\nAsk sends limited Minecraft facts to the configured service only when tapped. Answers cannot execute commands.\n" + HelperCombatStatus.squadSummary(owner);
        return HelperForms.show(owner, agent.name() + " • Helper", withFeedback(feedback, content), choices,
            () -> current(owner, id, agent, captured) && (golem == null || live(helpers, id) == golem), sender);
    }

    private static int profiles(ServerPlayer owner, String id, Predicate<Form> sender) {
        var helpers = helpers(owner); var agent = helpers.data.agents.get(id);
        if (agent == null || !agent.owner().equals(owner.getStringUUID())) return roster(owner, "Choose your helper again.", sender);
        var captured = helpers.loaded.get(UUID.fromString(id));
        var choices = new ArrayList<HelperForms.Choice>();
        var content = new StringBuilder("Current: ").append(agent.profile().label()).append(". Movement and edited stats are kept.\n");
        for (var profile : creationProfiles()) {
            content.append("\n").append(profile.label()).append(": ").append(profile.description());
            choices.add(new HelperForms.Choice("Choose: " + profile.label(), () -> {
                int result = helpers.profile(owner, agent.name(), profile);
                var after = helpers.data.agents.get(id);
                boolean applied = result == 1 && after != null && after != agent && after.profile() == profile;
                detail(owner, id, applied ? "Profile updated: " + profile.label() + "."
                    : "Profile change was not confirmed. Check the in-game notice before retrying.", sender);
            }));
        }
        choices.add(new HelperForms.Choice("Back to helper", () -> detail(owner, id, "", sender)));
        return HelperForms.show(owner, agent.name() + " • Profiles", content.toString(), choices, () -> current(owner, id, agent, captured), sender);
    }

    /** {@code /agent dismiss <name>} opens the same exact-identity review; nothing is removed until Confirm. */
    static int confirmDismiss(ServerPlayer owner, String name) { return confirmDismiss(owner, name, HelperForms.sender(owner)); }
    static int confirmDismiss(ServerPlayer owner, String name, Predicate<Form> sender) {
        if (!AgentMenu.allowed(owner) || !HelperForms.emptyScreen(owner)) {
            HelperForms.message(owner, "Close other screens before using helpers. A living OP4 owner is required. Nothing was dismissed.");
            return 0;
        }
        var entry = AgentCompanions.validName(name) ? helpers(owner).owned(owner, name) : null;
        if (entry == null) {
            HelperForms.message(owner, "No helper named " + name + " belongs to you. Nothing was dismissed.");
            return 0;
        }
        return dismiss(owner, entry.getKey(), sender);
    }

    private static int dismiss(ServerPlayer owner, String id, Predicate<Form> sender) {
        var helpers = helpers(owner); var agent = helpers.data.agents.get(id);
        if (agent == null || !agent.owner().equals(owner.getStringUUID())) return roster(owner, "Choose your helper again.", sender);
        var captured = helpers.loaded.get(UUID.fromString(id));
        int returnPage = pageFor(owner, id);
        return HelperForms.show(owner, "Dismiss " + agent.name() + "?",
            AgentMenu.DISMISS_WARNING,
            List.of(new HelperForms.Choice("Confirm dismiss " + agent.name(), () -> {
                int result = helpers.dismiss(owner, agent.name());
                boolean removed = result == 1 && !helpers.data.agents.containsKey(id);
                roster(owner, removed
                    ? agent.name() + (helpers.creationRecoveryRequired ? " was dismissed, but its recovery note needs attention." : " was dismissed.")
                    : "Dismissal was not confirmed. Check the in-game notice before retrying.", sender, returnPage);
            }), new HelperForms.Choice("Keep this helper", () -> detail(owner, id, "Kept this helper. Nothing was removed.", sender))),
            () -> current(owner, id, agent, captured), sender);
    }

    private static int squad(ServerPlayer owner, String feedback, Predicate<Form> sender) {
        var helpers = helpers(owner); var saved = snapshot(owner);
        if (saved.isEmpty()) return roster(owner, "Create your first helper before using squad controls.", sender);
        var choices = new ArrayList<HelperForms.Choice>();
        long loaded = saved.keySet().stream().filter(id -> live(helpers, id) != null).count();
        long sameWorld = saved.keySet().stream().map(id -> live(helpers, id)).filter(g -> g != null && g.level() == owner.level()).count();
        if (sameWorld > 0) for (var mode : AgentCompanions.Mode.values()) choices.add(new HelperForms.Choice(
            "Squad: " + mode.name().toLowerCase(java.util.Locale.ROOT), () -> {
                var before = snapshot(owner);
                var eligible = new LinkedHashMap<String, AgentCompanions.Agent>();
                before.forEach((id, record) -> {
                    var helper = live(helpers, id);
                    if (helper != null && helper.level() == owner.level()) eligible.put(id, record);
                });
                int result = helpers.squad(owner, mode);
                boolean applied = result == 1 && !eligible.isEmpty() && eligible.entrySet().stream().allMatch(entry -> {
                    var after = helpers.data.agents.get(entry.getKey());
                    return after != null && after != entry.getValue() && after.mode() == mode;
                });
                squad(owner, applied ? "Requested " + mode.name().toLowerCase(java.util.Locale.ROOT) + " for loaded helpers in this world. Unloaded and other-world helpers were skipped."
                    : "Squad movement change was not confirmed. Check the in-game notice before retrying.", sender);
            }));
        for (var profile : List.of(AgentCompanions.Profile.PRIMITIVE, AgentCompanions.Profile.REGULAR, AgentCompanions.Profile.ULTIMATE_FINALS))
            choices.add(new HelperForms.Choice("Squad profile: " + profile.label(), () -> {
                var before = snapshot(owner);
                int result = helpers.squadProfile(owner, profile);
                boolean applied = result == 1 && before.entrySet().stream().allMatch(entry -> {
                    var after = helpers.data.agents.get(entry.getKey());
                    return after != null && after.profile() == profile && (entry.getValue().profile() == profile || after != entry.getValue());
                });
                squad(owner, applied ? "Saved squad profile: " + profile.label() + ". Movement and edited stats are kept."
                    : "Squad profile change was not confirmed. Check the in-game notice before retrying.", sender);
            }));
        if (loaded > 0) choices.add(new HelperForms.Choice("Bring loaded squad here", () -> {
            helpers.recallSquad(owner);
            long nearby = snapshot(owner).keySet().stream().map(id -> live(helpers, id))
                .filter(g -> g != null && g.level() == owner.level() && g.distanceToSqr(owner) < 36).count();
            squad(owner, "Bring here finished: " + nearby + " helpers now nearby. Unloaded helpers and those without safe landing space were kept where they were.", sender);
        }));
        choices.add(new HelperForms.Choice("Cycle follow formation • " + AgentCompanions.formation(owner), () -> {
            AgentCompanions.cycleFormation(owner); squad(owner, "Follow formation: " + AgentCompanions.formation(owner) + ". Combat approvals are separate.", sender);
        }));
        choices.add(new HelperForms.Choice("Ceasefire • clear mob and player targets", () -> {
            AgentActions.get(helpers.server).ceasefire(owner); squad(owner, "Mob and player targets and pending player-target orders cleared.", sender);
        }));
        choices.add(new HelperForms.Choice("Orders • targets and both approvals", () -> BedrockAgentOrdersMenu.open(owner, sender)));
        choices.add(new HelperForms.Choice("Back to your squad", () -> roster(owner, "", sender)));
        String content = saved.size() + " saved helpers; " + loaded + " loaded; " + sameWorld + " loaded in this world.\n"
            + "Movement affects loaded helpers here. Profiles also save for unloaded helpers. Bring here never loads distant chunks and clears mob and player-target orders.\n"
            + "Ultimate Finals shares focus and uses native spear, elytra and mace tactics when conditions allow. STAY must be changed to Follow/Guard to resume.\n" + HelperCombatStatus.squadSummary(owner);
        return HelperForms.show(owner, "Squad controls", withFeedback(feedback, content), choices, () -> current(owner, saved), sender);
    }
}
