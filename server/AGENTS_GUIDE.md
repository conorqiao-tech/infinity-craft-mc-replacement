# Explore.51 source candidate — explicit groups, stronger helpers and GPT plans

These features are a tested source candidate, **not a live installation**.
The running approved version is explore.50. Explore.51 passes 722 native tests
in 39 groups, 434 launcher/resource tests and the fresh crossplay/persistence
checks detailed in [VALIDATION.md](VALIDATION.md). Physical iPad checks remain
pending. Preparing or merging source does not install the candidate, restart the
server or grant either AI approval.

## Edit specifically selected players together

Open **Infinity Menu → Admin editor • players and AI**, choose a player and a
stat, then **Edit matching players together** in the Java stat editor. In the
iPad stat form, select **Edit matching players together** under **Action** and
submit to open the player list. This opens selection without applying the typed
single-player amount.

The list contains living online player sessions whose selected stat has the
same current **base value** as the chosen anchor player. Effective values can
differ because of equipment and effects. No player, including the anchor or
admin, starts selected. Toggle the actual named players you want, using previous
and next pages where needed. Equal values or names never add someone to the
group automatically. AI helpers retain the existing individual editor.

Choose **Enter exact number**, enter one value, and review the before/after
changes for every selected player before one **Confirm changes** activation.
Capacity increases needed for health or absorption appear in each affected
player's preview. Group health must stay above zero; individual lethal health
editing retains its existing warning and confirmation. Toggling, typing,
previewing and cancelling change no stats. The group session expires after 90
seconds and rejects changed player sessions, selected values, permissions or
reviewed capacities. Switching between single/group editors retires their old
private number prompts and native forms. Manual stat confirmation remains an
OP4 action and does not require AI approval.

## One-time finite helper strength defaults

Registered helpers with untouched native defaults receive **1,000 maximum
health, 100 attack damage, 30 armor, 20 armor toughness and 1 knockback
resistance**. This is a one-time native default upgrade, not an infinite-health
mode. Recorded admin edits and other non-default bases remain intact. Existing
helpers retain their current health, including damage already taken; loading,
recalling or changing profile does not silently heal them. Newly created single
or batch helpers start at their resulting full health capacity. Their saved
identities, equipment and explicit orders are preserved. Normal damage, terrain
and native combat requirements still apply; OP4 remains necessary for controls.

## Follow formations on real loaded ground

Java's squad roster has **Follow formation**; iPad uses **Squad controls → Cycle
follow formation**. **Escort** makes spaced rows behind the owner, **Wedge**
makes symmetric widening rows, and **Ring** distributes followers around the
owner. These are distinct owner-relative positions for idle helpers in Follow
mode. Each member gets a separate supported position on loaded ground, with
local alternatives when the ideal position is obstructed. If none is available,
that helper holds rather than crowding the owner's position. Following does not
automatically teleport helpers or load distant terrain. The explicit **Bring
loaded squad here** control remains available separately. Combat pursuit and
both player-target approvals remain unchanged.

## Ask GPT for one reviewed squad plan

Open **Infinity Menu → AI Helpers → Ask GPT for a squad plan**. Java offers
**Request an escort plan**, **Request an area defense plan**, and **Request the
strongest combat plan**. On iPad, type a one-line goal of 1–256 characters and
submit. `/agent plan <goal>` is the optional text route. Opening this menu,
reading status, refreshing helpers and normal movement/combat ticks send no
GPT request. Each submitted goal is an explicit request through the host's
already configured GPT route and shares existing AI request budgets and usage
limits. No account, provider or model setting is changed by the planner.

The request sends the goal and limited counts/settings for the owner's loaded
squad. GPT can propose only supported high-level movement mode, Regular or
Ultimate Finals profile, and Escort/Wedge/Ring formation for that exact squad.
It does not receive a live terrain simulation and cannot promise a flawless
formation or combo. Unsupported goals can produce an honest no-action answer.
It cannot issue arbitrary commands, write code, spawn helpers, change stats,
target players or pets, edit the world, grant permissions or approve actions.

A valid result enters **Orders • targets and both approvals** as a squad
proposal. Review the exact named helper set and proposed settings. It still
requires **your owner approval and separate live Codex approval** before the
native controls can apply it. Asking GPT grants neither approval and changes no
helper orders. Reconnected owners, changed helpers, changed formations or stale
reviews cannot inherit an earlier proposal's authority. A disabled or unavailable
configured route sends nothing; no request or setting change occurs merely by
viewing this screen.

# Historical explore.50 candidate — create a helper batch

Open **Infinity Menu → AI Helpers → Create helpers in a batch**. On iPad, enter
a whole-number quantity and choose a profile, then submit once. On Java, select
quantity presets or use ±1/±10, choose a profile and press **Spawn** once. Changing
quantity or profile does not create anything. New helpers follow immediately.

Choose from one to the remaining capacity. Both the owner and entire server have
a total cap of fifty helpers; existing and saved distant helpers count. A batch
requires enough clear, dry solid ground nearby and does not load distant terrain
or replace an existing helper. If the entire requested batch cannot be placed or
ownership cannot be saved, no smaller surprise batch is created. Existing helper
identities, names, health, edited stats and profiles remain intact. OP4 access and
combat/target approval rules are unchanged. A stale or replayed menu cannot spawn
a second batch.

Source candidate50 passed 673 native and 419 launcher/resource tests while
explore.49 was running, before the separately approved explore.50 installation.
These counts are historical explore.50 evidence, not results for explore.51.
A live update needs separate owner approval and a verified stopped-world backup.

# Helper squads, AI modes, and full operator controls

**Explore.48 source candidate for Minecraft Java 26.4 Snapshot 5:** helpers retain
the fifty-slot paginated squad, explicit reviewed living targets, native-backed
Infinity weapons, retained pursuit and checked close-range mace leaps.
Select an existing helper and choose
**Enable combos and follow me** to resume it without replacing or healing it.
Player orders and server actions retain owner and separate live Codex approval.
See [Helper controls](HELPER_CONTROLS.md), [Helper combos](HELPER_COMBOS.md) and
[VALIDATION.md](VALIDATION.md). Java clients need this exact snapshot; Bedrock
crossplay uses experimental public-source compatibility ports. Private tests
retain normal native Java authentication and verified Floodgate identities, but
actual Xbox-authenticated iPad joining and device visuals remain unconfirmed.
The live server remains explore.47 until installation; preparing source does not
install it.

Admins can edit a loaded helper through **Infinity Menu → Admin editor • players and AI**. Select its golem icon, change health, damage, speed, armor, size or another supported base attribute, then confirm. On iPad/Bedrock this opens native buttons and a number input field; Java uses Review and Confirm. Manual stat edits do not require Codex approval. Original attribute values can be restored. Exact saved stats stay unchanged by the compact health display sent to observers. Physical knockback stays bounded to keep the world responsive. The edits and reset values save with the entity; helper behavior/profile changes invalidate an open stat review. Follow, guard and profile controls remain in **AI Helpers**, and player-target orders still require both approvals.

Helpers are vanilla iron golems controlled by this server. They work with Java and Bedrock without a client mod. **Only an operator with level 4 can create, change, dismiss, or ask a helper.** The free Admin role now grants actual OP4 when obtained through the private code or a trusted owner grant.

## Manage a squad

Select the **Infinity Menu** recovery compass in your hotbar, then **AI Helpers • open your squad**. If the compass is missing, tap an **INFINITY MENU** sign in the Main Hub or a mode lobby and leave one inventory slot free for a replacement. `/agent menu` remains an optional shortcut.

On your first visit, tap the green **Create your first helper** button. Stand on clear solid ground with enough room for a player-sized helper. The screen closes before spawning; look nearby for the named player avatar and read the success or failure message. Helpers do not appear automatically when you join. If spawning fails, move to an open area and reopen the menu to try again.

Create up to **50 named helpers per owner**, choose one to manage it, select an AI profile, or set **follow**, **guard current area**, or **stay / pause** for the loaded squad. **Bring loaded squad here** moves existing loaded helpers to safe nearby ground while preserving their entities, health and edited stats; unavailable helpers are reported rather than replaced. Roster icons show whether each helper is fighting, ready, staying, unloaded or paused for another reason. The entire server has **50 slots**, including unloaded helpers. **Unloaded** means no live body is currently registered; it may be in an unloaded area or may have died. Return nearby if possible, and ask the host to review an uncertain identity. Menu icons are controls rather than collectible items. Dismissal has a confirmation screen, and `/agent dismiss <name>` opens that same confirmation for your exact helper; nothing is removed until you choose Confirm. Everyone can see the Infinity Menu's helper button, but creating and controlling a squad requires **OP level 4**.

Choose a saved helper to inspect its profile, combat phase/reason, location and health. Java's **Live status • combat, location and health** shows these details and closes the chest when clicked so the answer is readable; iPad/Bedrock shows the shared combat summary in the native form with **Refresh live status**. Opening or refreshing status does not order an attack, advance a combo or approve anything. **Ask Codex** appears when the Codex provider is enabled (otherwise **Ask helper**). The Ask button sends “Explain my helper's current state and suggest what I should do next.” The screen closes so you can read the answer. **Back to your squad** returns to the roster; its **Back to Infinity Menu** button returns to the main menu. Use `/agent ask <name> <question>` to write a different question.

**AI Helpers → Orders • targets and approvals** opens the nearby living-target picker, the separate player picker and the six fixed action proposals. Non-player choices require confirmation of the exact entity; player choices still need both approvals. **Pending orders** opens an exact review with **Approve as owner** and **Cancel**. The menu never provides Codex approval: ask Codex in this chat to review the displayed proposal ID before expiry. Follow, Guard and Stay work directly for loaded helpers in your world.

The following commands remain available for named creation, questions and advanced actions:

```text
/agent spawn guide
/agent spawn defender
/agent profile guide primitive
/agent profile defender ultimate_finals
/agent ask guide how do ranks work
/agent status defender
/agent squad follow
/agent menu
```

Names use 1–24 lowercase letters, digits, `_` or `-`. Each helper has its own name, saved owner, AI profile and movement setting. `/agent list` lists your roster. `/agent profile <name> <profile>` changes a saved profile, including an unloaded helper; movement changes need a loaded helper in your dimension. `/agent squad follow`, `guard`, or `stay` affects your loaded helpers in your dimension and reports skipped helpers.

Explore.26 adds **Set squad: Primitive**, **Regular** and **Ultimate Finals** buttons to the roster. They change every helper owned by you, including unloaded or passive-profile helpers, and save the profile immediately. The matching/total count shows which helpers already use that profile. Existing positions, health, edited attributes and Follow/Guard/Stay settings remain. A helper on Stay still needs **Follow** to resume. These buttons do not authorize attacking a player or approve a queued action. `/ag` is an optional shortcut for `/agent`; the existing `/ai` chat command is unchanged.

| AI profile | What it does |
| --- | --- |
| `primitive` | Combat-ready: proactively seeks nearby hostile mobs within 12 blocks. Follow, Guard and Stay remain available; questions use Codex when enabled, otherwise local guidance. |
| `regular` | Standard follow/guard hostile-mob defense; questions use Codex when enabled, otherwise local server help. |
| `ultimate_finals` | Shares a focus target, prioritizes hostiles threatening the owner, flanks, and leads moving targets. The squad equips native spears, briefly glides with equipped elytras and switches to maces for falling attacks when the landing and airspace are clear. Primitive and Ultimate Finals can share one explicitly named player target after both reviews below. Pets and ordinary animals are never selected automatically; an exact confirmed non-player order can select one. |
| `debug` | Passive helper with read-only status: health, loaded state, position, movement and pause reason. Does not expose host files or secrets. |
| `cli` | Saves source-change requests for owner approval and live Codex code review, edits and testing. Also previews six fixed Minecraft server actions. It does not launch a terminal, shell or apply model-generated code. |
| `api` | Optional external AI text answers with a limited live Minecraft snapshot. `/agent data <name>` previews the data. Requires the owner's configured connection and shares the server's request budget with `/ai`. It has no tools and cannot run its suggestions. |

When Codex is enabled, questions to **all six profiles** use the Codex connection. The profile still selects the helper's movement/combat behavior and the context for its answer. With the OpenAI API provider, only API-profile helper questions are sent externally; other profiles keep their local responses.

AI profile and movement are separate controls. Follow moves toward the owner; Guard anchors a combat helper at its current location; Stay stops navigation and combat. Player targeting requires a separate reviewed order. Helpers pause while the owner is offline, dead, spectating, in another dimension, or no longer OP4. Follow pauses beyond 48 blocks; return nearby to resume ordinary mob defense. Helpers never teleport automatically or load distant chunks. For a loaded helper left in another world or beyond follow range, choose **AI Helpers → helper → Bring here**, or use `/agent recall <name>`. The owner must be online, alive and OP4, with safe space nearby. This moves the existing golem, preserves its health and edited attributes, switches it to Follow and clears the squad’s active mob and player-target orders and pending target approvals. Helpers that still exist in an unloaded area must first be loaded by visiting it; an uncertain or dead helper may need host review. They are not replaced or force-loaded.

Ultimate Finals predicts at most two blocks ahead of a moving target. Only ready squad members with a checked route take aerial turns: an unloaded, cooling-down, out-of-range or obstructed teammate cannot reserve the next turn. Launches start within seven blocks on roughly level ground, using a mace opener at close range; only one helper in that owner's squad performs the aerial sequence against the same target at a time. Each successful launch starts that helper's 80-tick leap cooldown. The path needs loaded, dry, unobstructed airspace and a clear solid landing inside its movement area. During flight, bounded landing adjustments are checked again against terrain and the movement leash; unsafe target movement keeps the last checked landing. Walls, low ceilings or unsuitable ground keep ordinary ground pursuit available. Status explains the current phase and reason rather than promising a hit.

Ultimate Finals holds a native-backed Infinity Spear on ground approaches, preserves its charge instead of restarting use every tick, and switches to its Infinity Mace when the target is inside the spear’s minimum reach. A small return margin avoids repeatedly swapping weapons at that boundary. Native LivingEntity use ticks run the spear's kinetic charging component. The aerial arc equips an elytra and briefly enters native glide, stops gliding, then equips a mace before a falling attack through Minecraft’s MobEntity weapon path, including the native mace fall bonus. Follow-up uses a close mace or native spear charging contacts; ordinary strikes retain their twenty-tick cadence and native spear contacts retain their own cooldowns. Hits still need actual reach, line of sight and target eligibility; hits are not guaranteed. Helpers use native player avatars that show equipped weapons and elytra. Their server AI stays golem-based, with player-sized default collision/eye height and native scale edits preserved; they are not authenticated Minecraft player accounts. Status reports name the actual held item and glide state. These mechanics do not reset damage immunity or guarantee bypassing a totem. Native spear side effects and mace splash knockback reject unapproved players and other ineligible targets before touching them. Stay, ceasefire, a profile change, an invalid target/order or loss of the owner's OP4 cancels the sequence; gravity remains active. No AI request is made for each movement or attack.

## Shared player targets

Your Primitive and Ultimate Finals helpers can share an exact player target. They never choose a player automatically. Use `/agent target <player>` to create a proposal, read its target name, UUID and dimension with `/agent pending`, and approve its exact ID with `/agent approve <id>`. Then ask Codex in this live chat to review it. Only the local-console `agent-codex-approve <id>` after that review can activate the order. Chat answers cannot activate it.

An active player order lasts at most five minutes while the target stays within 48 blocks of the owner. Minecraft PvP and team rules apply. Helpers can pursue around obstacles; actual swings still require melee reach, line of sight and a target within 24 blocks. Guard helpers stay within their 14-block anchor area and wait if the player leaves it. Moving outside a helper's immediate attack range does not consume the order. The target must be an eligible living player nearby in the owner's dimension; Creative and Spectator players are excluded. Owner logout, death, dimension change or loss of OP4 clears the order, as do target logout, death, dimension change and other eligibility changes, or having no loaded compatible helper active inside its movement leash. A respawn or reused name does not inherit an order. Pending player orders are cancelled across server restarts. Regular, Debug, CLI and API helpers do not join this player hunt.

Use `/agent ceasefire` or the **Ceasefire** button in the AI Helpers menu to clear your chosen mob order, player order and queued target proposals immediately. This only affects your squad. The menu compass shows your current target status; Stay also pauses a helper's combat. Shared targeting is deterministic golem coordination. This live Codex chat reviews orders when you ask; it does not watch the queue in the background. The separate in-game Codex answer connection does not activate orders or replace that review.

After a roster write, the roster is saved as format 3 in the world's `infinity-agents.json`; an old-format roster stays unchanged on disk until a write. Existing format-1 helpers upgrade with the Regular profile. Earlier builds recorded different events using the same removal entries, so after restart those entries do not establish that an unseen saved body was intentionally dismissed. A matching body is retained inert for host review rather than automatically deleted; direct removal, `/kill`, some void damage and other mods can still remove it. An explicitly confirmed, durable dismissal can delete the loaded helper and a saved copy first loaded during the same server session, including edited stats. A saved copy first loaded after a restart is retained for host review. Keep a matching world backup when inspecting uncertain records. A helper that dies naturally keeps its roster slot until the owner deliberately dismisses it or the host resolves the uncertainty. Older server builds cannot read format 3; use a matching verified whole-world backup for a rollback, including ordinary entity data. Do not restore `.previous` alone into an older build: it may omit later changes or treat old deaths as dismissals.

Before inserting new helpers, creation first establishes a durable recovery guard and saves their identities as pending. If the guard cannot be established, no helper is inserted. Pending identity and a successful insertion do not prove that the entity reached disk; an interrupted creation or a guard that cannot be retired needs host review. If insertion fails after the pending save, the server keeps the pending identities and preserves any existing guard instead of automatically treating later same-identity bodies as disposable. While ownership is frozen, loaded registered bodies are stopped without applying helper defaults or rewriting their names and ownership tags. The freeze may still stop their AI and change persistence behavior. Inspect the matching world and recovery evidence before deciding what to do.

If helper ownership freezes, preserve the roster, its `.previous` copy and any `.recovery-required` or `.recovery-required.tmp` note for investigation with the matching world data. An unfinished temporary note also freezes ownership, even if no final note exists. Do not remove these files to make the warning disappear. If storage cannot leave any recovery note, a restart cannot be guaranteed to remain frozen.

When a saved helper's owner or name tag explicitly conflicts with its roster record, ownership freezes before those identity tags, its saved name or stat defaults are rewritten. The body and record remain for host review. Matching or missing tags do not prove that a UUID is unique, that an entity reached disk, or that a pending creation is resolved; mode and profile tags can be stale after an interrupted update.

## Reviewed code changes

Set a helper to CLI, then use `/agent code <name> <request>` to save a plain-text change request (up to 500 characters). It writes a private review record with the world, not an executable patch. Do not include secrets. `/agent code-pending` shows your requests; `/agent code-approve <id>` approves the exact saved text; `/agent code-cancel <id>` cancels it. Requests expire after 24 hours, with three active requests per owner and 16 server-wide.

Ask Codex in this live chat to review the request. Codex reads `agent-code-pending` in the **local** server console and uses `agent-code-review <id>` only after checking your approval. Codex then inspects the repository, edits the source, reviews the resulting diff, runs the needed tests and creates a Git commit. `agent-code-complete <id> <full-40-character-commit>` records the result. Use the stopped-world backup and tested upgrade procedure to install it. No code changes happen while this chat is closed. If a review is already underway, cancelling the request in Minecraft cannot undo source edits made here; tell Codex to stop too.

The queue validates state, helper identity, OP4 and local-console origin. A console completion records the reviewer-supplied commit; Minecraft cannot independently verify Git, test results, or that the console operator is Codex. Those checks happen in the live review. Requests are not sent to an external model automatically.

## Live Minecraft data

`/agent data <name>` reads a bounded snapshot for one of your own helpers. Codex questions in every profile, and API-profile questions with the OpenAI API provider, include the current snapshot with your question and the server guide. A notice appears before sending these facts to OpenAI. It contains game status such as the owner's current location/mode, helper status, world time/weather and online player count. It excludes IP addresses, keys, private host settings, other players' inventories and host files. API-profile helpers also keep the latest 12 sanitized samples in memory, one every five seconds. `/agent history <name>` shows the latest three with server-tick timestamps; explicit external questions may include those three samples when available to compare recent changes. Sampling never makes API requests automatically. History clears when the helper leaves API mode, is dismissed, or its owner goes offline/loses OP4; it also resets on server restart. It is a recent game-status history, not a persistent analytics database. All helpers share the same server request budget and selected provider account; changing helper names or profiles cannot create extra quotas.

## Chat help and provider setup

`/ai help` always uses the built-in server guide, without an API key or Codex request. When Codex is enabled, an OP4 player's other `/ai <question>` requests and `/agent ask <name> <question>` in all six profiles go to Codex. Ordinary players retain built-in responses for known server topics and cannot spend the host's Codex allowance. With the OpenAI API provider, `/ai` answers known server topics locally and sends questions outside that catalogue through the optional API; only API-profile helpers use that external connection. `/ai status` shows the configured provider, model, daily request count and readiness to an OP4 owner.

To connect Codex:

1. **Save & Stop** the world and open **Owner: AI chat** in the private host panel.
2. Choose **Codex CLI · signed-in account**. Enter the **absolute path to the native Codex executable**, without command arguments. This integration currently accepts the audited **Codex CLI 0.155.1** only; other versions stop with an error until reviewed for compatibility. The executable must already exist on this computer and be signed in with the host's ChatGPT account. Prefer the native binary over a Node-dependent launcher shim, since the server launcher's environment may not include Node. Saving checks the path; it does not install Codex, sign in, or verify the login/version. Those are checked when a question is sent.
3. Enable AI chat, choose a model available to that account, and set the daily request limit. Codex requires **Only allow operators with level 4**; the panel keeps it checked. No OpenAI API key is needed.
4. Save and start the world. Select a helper's **Ask Codex** button or type your question with `/ai` or `/agent ask`.

Codex uses the host account's usage allowance and availability; it is not a promise of free or unlimited chat. The OpenAI API option remains available: choose **OpenAI API**, enter a key or leave the field blank to retain the saved key, then save and restart. API usage is billed to the host's API account. Switching providers keeps the private API key. A fresh download contains neither a key nor a Codex login.

External questions send your text and the supplied server guide to OpenAI. Helper questions also send the bounded Minecraft facts described above. Responses are private to the asking player and identify the provider used. The integration requests a text answer, with no helper interface for reading the host environment, editing files or running server commands. A Codex answer is a separate request, **not this live Codex conversation**, and cannot count as its review or approval. No environment access or automated approval is granted by choosing a profile called CLI, API or Debug.

Both providers share the server's daily request counter, with 50 requests per UTC day by default. The count is saved with the world, survives restarts, and includes accepted attempts that fail. A zero limit pauses external chat. Codex is always OP4-only; OpenAI API chat is OP4-only by default and uses the owner's configured access setting. `/ai help` remains available when an external connection cannot answer. Normal public Minecraft chat is not automatically sent to either provider.

Settings are stored in `fabric/config/infinity-ai.json` (`provider`, `codexExecutable`, `model`, `enabled`, `ownerOnly`, and `dailyRequestLimit`). The OpenAI API key stays in `fabric/config/infinity-ai-key.txt`, never in the JSON or in-game chat. Keep it private; on Mac/Linux it is written with permission 0600. It is excluded from server/source downloads, world backups and the public website.

## Owner-approved helper actions

The helper action queue is separate from `/ai` chat and from the iron-golem follow/guard controls. An OP4 owner can propose exactly six fixed server actions: **set day**, **set night**, **clear weather**, **make it rain**, **list players**, and **save world**. Arbitrary natural-language requests and arbitrary commands are not accepted. Proposing never runs an action.

1. In Minecraft, an OP4 owner enters `/agent suggest set day` (or one of the other supported phrases). The server returns a proposal ID and the exact fixed command it would run.
2. Review the command with `/agent pending`, then use `/agent approve <id>` to approve that ID, or `/agent cancel <id>` to discard it. Owner approval still does not run the command.
3. Ask Codex in a **live session on the host Mac** to review that proposal. Codex must read the local console's `agent-codex-pending` output and independently decide whether to dispatch the exact approved ID through `agent-codex-approve <id>`. These console commands cannot be used by an in-game player. No background Codex session watches the queue or approves it automatically.

Proposals expire after ten minutes. The OP4 owner must still be online when Codex dispatches one. A proposal can dispatch only once; the queue keeps a short saved history in `fabric/world/infinity-agent-actions.json`. At most three active proposals are allowed per owner and 16 across the server. Restore this file with the matching world backup. The fixed command is never generated by a chat model. An Admin with actual OP4 can propose and give the player approval, but still needs the separate live Codex review and local-console dispatch.

Enabling Codex chat does not change this approval process. An **Ask Codex** reply can explain a proposal, but only the separate live review and local-console approval described above can dispatch it.

## Full operator access

The host can grant full in-game access with `op ExactPlayerName` in the server console. Bedrock accounts use their exact Floodgate name, including its prefix. Full access means **OP level 4**, the highest Minecraft operator level. The free Admin role also grants this vanilla permission level through a verified private code or `membership grantadmin PlayerName`; existing saved Admin roles are promoted on join. This includes vanilla commands such as `/give`, `/gamemode`, `/tp`, `/op` and `/stop`.

Level-4 operators can use all mod powers and cosmetics without achievements or item payment, in every game world. Operator powers bypass combat waits and Windstep cooldowns. Admin also bypasses Infinity weapon ability cooldowns and has the operator unlocks for gear and backpacks. Operators switch modes immediately, can enter Hardcore after elimination, and can use vanilla `/gamemode` without the mod forcing a mode afterward. Cross-world operator teleports save and restore the matching inventories and mode profiles. Operators bypass community teleport waits, home-slot limits, and chat moderation limits.

```text
/power hacks
/cosmetic dragon
/play creative
/gamemode creative
/give @s minecraft:diamond 64
/agent spawn buddy
```

The server still keeps separate mode profiles and backpack storage, checks valid names and coordinates, and requires both players to confirm trades. Commands that require a living player or a clear spawn location keep that requirement. One power preset is active at a time; use vanilla `/effect` for additional effects. Admin does not increase the fifty-helper owner/server limits, AI request allowance or available host resources. AI actions and named-player attacks keep both approval gates even for Admin.

Remove Admin with `membership revokeadmin PlayerName`. It restores the operator state replaced by the role's own promotion, while retaining a pre-existing OP4 grant or a separately recognized manual operator grant. Remove any remaining independent grant with `deop` if needed. De-opping alone while the Admin role remains can be reversed by the role's permission sync. These privileges do not award permanent achievement or trade unlocks. See [MEMBERSHIPS.md](MEMBERSHIPS.md) for private-code setup, revocation and backup details.

The OpenAI API provider follows the [Responses API quickstart](https://developers.openai.com/api/docs/quickstart) and uses the configurable [GPT-6 Luna model](https://developers.openai.com/api/docs/models/gpt-6-luna) by default.


## Helper robot skin in explore.15

Helpers share a charcoal and cyan robot skin with a silver face. The chest carries a small white ChatGPT knot and the blue-purple Codex cloud containing a tiny terminal prompt. Both are pixel approximations within Minecraft’s 8 by 12 pixel chest, rather than official OpenAI avatars. Native signed Minecraft texture data is sent to Java clients and Geyser; clients download the skin from Minecraft’s texture host. The server does not need a MineSkin account or API key at runtime. Reconnect after the update to refresh tracked helper profiles. Your saved helpers, owners, names, stats, equipment and orders are preserved. A chestplate can cover the chest emblems while equipped.
