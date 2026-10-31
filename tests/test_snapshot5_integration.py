"""The save gate derives an exact helper migration from NBT, never receipt claims."""
import copy
import gzip
import json
from pathlib import Path
import shutil
import struct
import sys
import tempfile
import types
import unittest
from unittest import mock
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import verify_snapshot5_integration as gate
sys.path.insert(0, str(ROOT / "server"))
import terrain_storage as real_storage


class HelperMigrationTests(unittest.TestCase):
    def fixture(self):
        identity = uuid.UUID("34e67df0-b40c-491d-a6ec-5d706e2cb598")
        native_uuid = list(struct.unpack(">iiii", identity.bytes))
        owner = "9a86ef9e-6060-4ca1-88dc-dfc8bd32113c"
        entity = {
            "id": "minecraft:iron_golem", "UUID": native_uuid,
            "Tags": ["infinity_agent", "infinity_owner_" + owner, "infinity_name_buddy",
                     "infinity_mode_follow", "infinity_profile_ultimate_finals"],
            "PlayerCreated": 1, "PersistenceRequired": 1, "Health": 73.0,
            "Pos": [12.5, 64.0, -9.5], "Motion": [0.0, 0.0, 0.0],
            "CustomName": {"text": "Owner's saved helper"}, "Invulnerable": 0,
            "equipment": {"mainhand": {"id": "convergence:spear", "count": 1,
                          "components": {"minecraft:custom_name": "Saved named spear"}}},
            "InfinityAdminStats": {"minecraft:max_absorption": {"original": 0.0, "expanded": 1}},
            "attributes": [
                {"id": "minecraft:max_health", "base": 100.0},
                {"id": "minecraft:attack_damage", "base": 15.0,
                 "modifiers": [{"id": "convergence:fixture", "amount": 2.0, "operation": "add_value"}]},
                {"id": "minecraft:armor", "base": 0.0},
                {"id": "minecraft:armor_toughness", "base": 0.0},
                {"id": "minecraft:knockback_resistance", "base": 1.0},
                {"id": "minecraft:movement_speed", "base": 0.33},
            ],
        }
        registration = {"format": 2, "agents": {str(identity): {
            "owner": owner, "name": "buddy", "mode": "FOLLOW", "profile": "ULTIMATE_FINALS",
            "dimension": "minecraft:overworld", "x": 12.5, "y": 64.0, "z": -9.5}}}
        return entity, registration

    @staticmethod
    def row(entity, name):
        return next(row for row in entity["attributes"] if row["id"] == "minecraft:" + name)

    def expected(self, original):
        # Explicit expected fixture values independent of the verifier's defaults.
        changed = copy.deepcopy(original)
        if gate.HELPER_POWER_MARKER not in original.get("InfinityAdminStats", {}):
            for name, old, new in (("max_health", 100.0, 1000.0), ("attack_damage", 15.0, 100.0),
                                   ("armor", 0.0, 30.0), ("armor_toughness", 0.0, 20.0),
                                   ("knockback_resistance", 1.0, 1.0)):
                row = self.row(changed, name)
                if "minecraft:" + name not in original.get("InfinityAdminStats", {}) and struct.pack(">d", row["base"]) == struct.pack(">d", old):
                    row["base"] = new
            changed.setdefault("InfinityAdminStats", {})[gate.HELPER_POWER_MARKER] = 1
        return changed

    def verify(self, original, converted, registration, version="2.13.0-explore.51", dimension="minecraft:overworld"):
        return gate.verify_saved_helper(original, converted, dimension, registration, version)

    def test_exact_five_default_policy_preserves_damage_equipment_modifiers_and_input(self):
        original, registration = self.fixture()
        saved = copy.deepcopy(original)
        converted = self.expected(original)
        converted["attributes"].reverse()  # Native list ordering is not an attribute change.
        converted["Pos"][0] += 0.25
        converted["Motion"] = [0.0, -0.1, 0.0]
        summary = self.verify(original, converted, registration)
        self.assertEqual(summary, {"migration_applied": True, "existing_marker_preserved": False,
            "changed_attribute_ids": ["minecraft:armor", "minecraft:armor_toughness", "minecraft:attack_damage", "minecraft:max_health"],
            "materialized_attribute_ids": [], "marker_added": True})
        self.assertEqual(original, saved)
        self.assertEqual(converted["Health"], 73.0)
        encoded = json.dumps(summary)
        for secret in (str(uuid.UUID(bytes=struct.pack(">iiii", *original["UUID"]))), "buddy", "Saved named spear", "12.5", "73.0"):
            self.assertNotIn(secret, encoded)

    def test_evidence_reader_rejects_duplicate_or_nonstandard_json(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'evidence.json'
            for text in ('{"format":3,"format":3}', '{format:3}', '{/*comment*/"format":3}', '{"value":NaN}', '{"value":Infinity}'):
                path.write_text(text)
                with self.subTest(text=text), self.assertRaises(ValueError):
                    gate.read_json(path)
                self.assertEqual(path.read_text(), text)

    def test_provenance_roster_acceptance_and_invalid_records_fail_closed(self):
        original, old = self.fixture()
        converted = self.expected(original)
        newer = copy.deepcopy(old)
        removed = str(uuid.uuid4())
        newer.update(format=3, dismissed=[removed], guardedRemovals=[removed], pendingCreations=[])
        self.assertEqual(self.verify(original, converted, newer), self.verify(original, converted, old))
        bad = [dict(newer, guardedRemovals=None), dict(newer, guardedRemovals=[removed, removed]),
               dict(newer, guardedRemovals=[str(uuid.uuid4())]), dict(newer, dismissed=[]),
               dict(newer, format=2), dict(newer, pendingCreations=None),
               dict(newer, pendingCreations=[str(uuid.uuid4())]),
               {key: value for key, value in newer.items() if key != 'guardedRemovals'}]
        for record in bad:
            with self.subTest(record=record), self.assertRaises(ValueError):
                self.verify(original, converted, record)
        noncanonical = copy.deepcopy(newer)
        identity, agent = next(iter(noncanonical['agents'].items()))
        noncanonical['agents'] = {identity.upper(): agent}
        noncanonical['dismissed'] = [identity]; noncanonical['guardedRemovals'] = [identity]
        with self.assertRaisesRegex(ValueError, 'noncanonical registered helper identity'):
            self.verify(original, converted, noncanonical)
        noncanonical['agents'] = {'1-1-1-1-1': agent}
        with self.assertRaisesRegex(ValueError, 'noncanonical registered helper identity'):
            self.verify(original, converted, noncanonical)

    def test_saved_override_even_at_vanilla_base_is_never_upgraded(self):
        original, registration = self.fixture()
        original["InfinityAdminStats"]["minecraft:attack_damage"] = {"original": 15.0, "expanded": 1}
        self.row(original, "armor")["base"] = 9000.0
        converted = self.expected(original)
        summary = self.verify(original, converted, registration)
        self.assertEqual(self.row(converted, "attack_damage")["base"], 15.0)
        self.assertEqual(self.row(converted, "armor")["base"], 9000.0)
        self.assertNotIn("minecraft:attack_damage", summary["changed_attribute_ids"])
        self.assertNotIn("minecraft:armor", summary["changed_attribute_ids"])
        self.row(converted, "attack_damage")["base"] = 100.0
        with self.assertRaises(ValueError):
            self.verify(original, converted, registration)

    def test_new51_registered_helper_must_receive_exact_marker_and_defaults(self):
        original, registration = self.fixture()
        for converted in (copy.deepcopy(original), self.expected(original)):
            if gate.HELPER_POWER_MARKER in converted["InfinityAdminStats"]:
                converted["InfinityAdminStats"][gate.HELPER_POWER_MARKER] = 2
            with self.subTest(marker=converted["InfinityAdminStats"].get(gate.HELPER_POWER_MARKER)):
                with self.assertRaises(ValueError):
                    self.verify(original, converted, registration)

    def test_existing_or_unknown_marker_preserves_every_value_without_reapplying(self):
        for marker in (0, 1, 2, "future", {"unrecognized": 1}):
            with self.subTest(marker=marker):
                original, registration = self.fixture()
                original["InfinityAdminStats"][gate.HELPER_POWER_MARKER] = marker
                summary = self.verify(original, copy.deepcopy(original), registration)
                self.assertFalse(summary["migration_applied"])
                self.assertTrue(summary["existing_marker_preserved"])
                self.assertEqual(summary["changed_attribute_ids"], [])
                converted = copy.deepcopy(original)
                self.row(converted, "attack_damage")["base"] = 100.0
                with self.assertRaises(ValueError):
                    self.verify(original, converted, registration)
                converted = copy.deepcopy(original)
                converted["InfinityAdminStats"][gate.HELPER_POWER_MARKER] = 99
                with self.assertRaises(ValueError):
                    self.verify(original, converted, registration)

    def test_existing_damage_cannot_be_healed_by_declared_migration(self):
        original, registration = self.fixture()
        for health in (74.0, 1000.0, 0.0, float("nan")):
            converted = self.expected(original)
            converted["Health"] = health
            with self.subTest(health=health):
                with self.assertRaises(ValueError):
                    self.verify(original, converted, registration)

    def test_equipment_name_identity_flags_and_other_native_fields_are_strict(self):
        for field, value in (("equipment", {}), ("CustomName", {"text": "Replaced"}),
                             ("Invulnerable", 1), ("PlayerCreated", 0), ("new_field", 1)):
            original, registration = self.fixture()
            converted = self.expected(original)
            converted[field] = value
            with self.subTest(field=field):
                with self.assertRaises(ValueError):
                    self.verify(original, converted, registration)
        original, registration = self.fixture()
        converted = self.expected(original)
        converted["UUID"][0] += 1
        with self.assertRaises(ValueError):
            self.verify(original, converted, registration)

    def test_custom_attribute_modifiers_and_undo_history_cannot_change(self):
        original, registration = self.fixture()
        for alteration in ("custom_base", "modifier", "undo", "drop", "extra", "duplicate"):
            converted = self.expected(original)
            if alteration == "custom_base":
                self.row(converted, "movement_speed")["base"] = 0.5
            elif alteration == "modifier":
                self.row(converted, "attack_damage")["modifiers"][0]["amount"] = 3.0
            elif alteration == "undo":
                converted["InfinityAdminStats"]["minecraft:max_absorption"]["original"] = 5.0
            elif alteration == "drop":
                converted["attributes"].pop()
            elif alteration == "extra":
                converted["attributes"].append({"id": "minecraft:luck", "base": 1.0})
            else:
                converted["attributes"].append(copy.deepcopy(converted["attributes"][0]))
            with self.subTest(alteration=alteration):
                with self.assertRaises(ValueError):
                    self.verify(original, converted, registration)

    def test_unregistered_helper_and_wrong_owner_or_profile_tags_are_rejected(self):
        for alteration in ("unregistered", "owner", "name", "mode", "profile", "duplicate_owner", "not_helper"):
            original, registration = self.fixture()
            converted = self.expected(original)
            if alteration == "unregistered":
                registration["agents"].clear()
            elif alteration == "not_helper":
                original["Tags"].remove("infinity_agent")
            elif alteration == "duplicate_owner":
                original["Tags"].append("infinity_owner_00000000-0000-0000-0000-000000000000")
            else:
                prefix = "infinity_" + alteration + "_"
                original["Tags"] = [prefix + "wrong" if tag.startswith(prefix) else tag for tag in original["Tags"]]
            with self.subTest(alteration=alteration):
                with self.assertRaises(ValueError):
                    self.verify(original, converted, registration)

    def test_actual_native_level_and_valid_registration_are_required(self):
        original, registration = self.fixture()
        with self.assertRaises(ValueError):
            self.verify(original, self.expected(original), registration, dimension="minecraft:the_nether")
        for field, value in (("owner", "not-a-uuid"), ("mode", "UNKNOWN"), ("profile", "UNKNOWN"),
                             ("name", "invalid name"), ("x", float("inf")), ("y", 3000.0)):
            altered = copy.deepcopy(registration)
            next(iter(altered["agents"].values()))[field] = value
            with self.subTest(field=field):
                with self.assertRaises(ValueError):
                    self.verify(original, self.expected(original), altered)

    def test_dimension_comes_from_native_region_layout_including_legacy_and_nested_paths(self):
        for path, dimension in (("entities/r.0.0.mca", "minecraft:overworld"),
                                ("DIM-1/entities/r.0.0.mca", "minecraft:the_nether"),
                                ("DIM1/entities/r.0.0.mca", "minecraft:the_end"),
                                ("dimensions/convergence/survival/entities/r.0.0.mca", "convergence:survival"),
                                ("dimensions/example/nested/world/entities/r.0.0.mca", "example:nested/world")):
            self.assertEqual(gate.native_entity_dimension(path), dimension)
        for path in ("unknown/entities/r.0.0.mca", "dimensions/Bad/World/entities/r.0.0.mca"):
            with self.assertRaises(ValueError):
                gate.native_entity_dimension(path)

    def test_historical_pre51_requires_unchanged_entity_and_rejects_new_migration(self):
        original, registration = self.fixture()
        summary = self.verify(original, copy.deepcopy(original), registration, version="2.13.0-explore.50")
        self.assertFalse(summary["migration_applied"])
        self.assertFalse(summary["marker_added"])
        with self.assertRaises(ValueError):
            self.verify(original, self.expected(original), registration, version="2.13.0-explore.50")

    def test_signed_zero_is_not_an_untouched_java_double_default(self):
        original, registration = self.fixture()
        self.row(original, "armor")["base"] = -0.0
        converted = self.expected(original)
        summary = self.verify(original, converted, registration)
        self.assertNotIn("minecraft:armor", summary["changed_attribute_ids"])
        self.assertEqual(struct.pack(">d", self.row(converted, "armor")["base"]), struct.pack(">d", -0.0))
        self.row(converted, "armor")["base"] = 0.0
        with self.assertRaises(ValueError):
            self.verify(original, converted, registration)

    def test_only_five_native_supported_missing_attributes_materialize_and_other_data_stays(self):
        original, registration = self.fixture()
        original["attributes"] = [self.row(original, "movement_speed")]
        original["InfinityAdminStats"]["future_private_metadata"] = {"saved": [1, 2, 3]}
        converted = copy.deepcopy(original)
        for name, value in (("max_health", 1000.0), ("attack_damage", 100.0), ("armor", 30.0),
                            ("armor_toughness", 20.0), ("knockback_resistance", 1.0)):
            converted["attributes"].append({"id": "minecraft:" + name, "base": value})
        converted["InfinityAdminStats"][gate.HELPER_POWER_MARKER] = 1
        summary = self.verify(original, converted, registration)
        self.assertEqual(summary["materialized_attribute_ids"], sorted(gate.HELPER_POWER_DEFAULTS))
        incomplete = copy.deepcopy(converted)
        incomplete["attributes"] = [row for row in incomplete["attributes"] if row["id"] != "minecraft:armor"]
        with self.assertRaises(ValueError):
            self.verify(original, incomplete, registration)
        converted["InfinityAdminStats"].pop("future_private_metadata")
        with self.assertRaises(ValueError):
            self.verify(original, converted, registration)

    def test_missing_native_attribute_with_admin_history_materializes_without_upgrading(self):
        original, registration = self.fixture()
        original["attributes"] = [row for row in original["attributes"] if row["id"] != "minecraft:armor"]
        original["InfinityAdminStats"]["minecraft:armor"] = {"original": 0.0, "expanded": 1}
        converted = copy.deepcopy(original)
        self.row(converted, "max_health")["base"] = 1000.0
        self.row(converted, "attack_damage")["base"] = 100.0
        self.row(converted, "armor_toughness")["base"] = 20.0
        converted["attributes"].append({"id": "minecraft:armor", "base": 0.0})
        converted["InfinityAdminStats"][gate.HELPER_POWER_MARKER] = 1
        self.verify(original, converted, registration)
        self.row(converted, "armor")["base"] = 30.0
        with self.assertRaises(ValueError):
            self.verify(original, converted, registration)

    def test_nonfinite_bases_duplicate_uuid_parts_and_large_displacement_are_rejected(self):
        original, registration = self.fixture()
        for invalid in (float("inf"), float("nan"), True):
            changed = copy.deepcopy(original)
            self.row(changed, "attack_damage")["base"] = invalid
            with self.assertRaises(ValueError):
                self.verify(changed, copy.deepcopy(changed), registration)
        for raw in ([1, 2, 3], [True, 2, 3, 4], [2 ** 31, 2, 3, 4]):
            changed = copy.deepcopy(original)
            changed["UUID"] = raw
            with self.assertRaises(ValueError):
                self.verify(changed, copy.deepcopy(changed), registration)
        converted = self.expected(original)
        converted["Pos"][0] += 2.0
        with self.assertRaises(ValueError):
            self.verify(original, converted, registration)


class NativeTagKindBoundaryTests(unittest.TestCase):
    def test_decoded_comparison_cannot_verify_native_tag_kinds(self):
        prefix, name, end = b"\x0a\x00\x00", b"\x00\x01x", b"\x00"
        byte_tag = prefix + b"\x01" + name + b"\x01" + end
        int_tag = prefix + b"\x03" + name + struct.pack(">i", 1) + end
        self.assertNotEqual(byte_tag, int_tag)
        self.assertTrue(gate.same_native_value(real_storage.NBT(byte_tag).parse(),
                                               real_storage.NBT(int_tag).parse()))


def native_uuid(identity):
    return list(struct.unpack(">iiii", uuid.UUID(identity).bytes))


def rider(kind="minecraft:zombie", identity=None, tags=None, passengers=None):
    entity = {"id": kind, "UUID": native_uuid(identity or str(uuid.uuid4()))}
    if tags is not None:
        entity["Tags"] = tags
    if passengers is not None:
        entity["Passengers"] = passengers
    return entity


def chain(depth, leaf):
    """A top-level vehicle whose innermost rider, `depth` levels down, is leaf."""
    node = leaf
    for _ in range(depth - 1):
        node = rider(passengers=[node])
    return rider("minecraft:oak_boat", passengers=[node])


def nbt_kind(value):
    if isinstance(value, dict):
        return 10
    if isinstance(value, str):
        return 8
    if isinstance(value, float):
        return 6
    if type(value) is int:
        return 3
    if isinstance(value, list):
        # Non-empty all-int lists (UUID/Attach) are native int arrays.
        return 11 if value and all(type(item) is int for item in value) else 9
    raise TypeError("unsupported synthetic NBT value")


def nbt_text(text):
    data = text.encode()
    return struct.pack(">H", len(data)) + data


def nbt_body(value):
    kind = nbt_kind(value)
    if kind == 10:
        return b"".join(bytes([nbt_kind(item)]) + nbt_text(key) + nbt_body(item) for key, item in value.items()) + b"\0"
    if kind == 8:
        return nbt_text(value)
    if kind == 6:
        return struct.pack(">d", value)
    if kind == 3:
        return struct.pack(">i", value)
    if kind == 11:
        return struct.pack(">i", len(value)) + b"".join(struct.pack(">i", item) for item in value)
    child = nbt_kind(value[0]) if value else 0
    return bytes([child]) + struct.pack(">i", len(value)) + b"".join(nbt_body(item) for item in value)


def encode_player_save(player):
    """Minimal test-only gzip NBT writer for synthetic player saves (not Minecraft output)."""
    return gzip.compress(b"\x0a" + nbt_text("") + nbt_body(player), mtime=0)


def mounted(entity, attach=None):
    return {"RootVehicle": {"Attach": attach or list(entity["UUID"]), "Entity": entity}}


class NestedPassengerTraversalTests(unittest.TestCase):
    """Pure decoded-dict checks of the production traversal helper.

    MOCK BOUNDARY: hand-built dicts only. Not real saved-world, region,
    crash-recovery or saved-entity durability evidence.
    """
    REGISTERED = {"34e67df0-b40c-491d-a6ec-5d706e2cb598"}

    def test_depth_limit_is_inclusive_and_beyond_it_fails_closed(self):
        limit = gate.NESTED_PASSENGER_DEPTH_LIMIT
        gate.verify_nested_passengers(chain(limit, rider()), self.REGISTERED)
        with self.assertRaisesRegex(ValueError, "nesting exceeds"):
            gate.verify_nested_passengers(chain(limit + 1, rider()), self.REGISTERED)

    def test_non_dict_root_and_registered_identity_are_rejected(self):
        for root in ("entity", ["Passengers"], None):
            with self.subTest(root=root), self.assertRaises(ValueError):
                gate.verify_nested_passengers(root, self.REGISTERED)
        registered = next(iter(self.REGISTERED))
        with self.assertRaisesRegex(ValueError, "nested passenger is or may be a helper"):
            gate.verify_nested_passengers(chain(2, rider(identity=registered)), self.REGISTERED)

    def test_traversal_never_mutates_the_decoded_tree(self):
        vehicle = chain(3, rider("minecraft:chicken", tags=["unrelated"]))
        saved = copy.deepcopy(vehicle)
        gate.verify_nested_passengers(vehicle, self.REGISTERED)
        self.assertEqual(vehicle, saved)


class NativeWorldPassengerTests(unittest.TestCase):
    """Exercise the real native_world() flow on isolated temp files.

    MOCK BOUNDARY: terrain_storage region/NBT decoding and level.dat parsing are
    replaced by a stub returning synthetic decoded NBT (identity() stays real).
    Offline player saves and level.dat_old backups are test-generated gzip NBT
    decoded by the real terrain_storage.saved_nbt parser, not Minecraft-written
    files; level.dat Data.Player is injected into the stubbed level data. None
    of these synthetic shapes is a claim about what Minecraft actually writes.
    Evidence JSON, player, mod-state and log files are real temp files checked by
    the production code. These tests are NOT real saved-world, region-encoding,
    crash-recovery or saved-entity durability evidence; they only show the
    verifier's own traversal runs on both worlds and rejects or admits the trees.
    """

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        self.base = self.root / gate.PRIVATE
        self.before = self.base / "world-upgrade-fixture/world"
        self.after = self.base / "snapshot5-world-conversion-lab/world"
        helper, self.registration = HelperMigrationTests.fixture(self)
        # An existing marker means no defaults migration under any release VERSION.
        helper["InfinityAdminStats"][gate.HELPER_POWER_MARKER] = 1
        self.helper = helper
        self.identity = next(iter(self.registration["agents"]))
        self.parsed = []
        self.player_reads = []
        self.level_reads = []
        # Synthetic level.dat Data.Player per world (None = absent, the default).
        self.level_players = {"before": None, "after": None}
        self.private_extra = []  # Extra synthetic identities that must not leak.
        self.label = {"Infinity-Armor-fixture.jar": "0" * 64}
        # Synthetic offline player name; never a real account.
        self.player_save = "players/data/" + str(uuid.uuid4()) + ".dat"
        self.prepare()

    def prepare(self, player=None):
        """Write isolated synthetic copies of both worlds plus matching evidence JSON.

        `player` is a decoded dict encoded here into real gzip NBT bytes (see
        encode_player_save); it is generated test data, not a Minecraft-written save.
        """
        label = self.label
        shutil.rmtree(self.base, ignore_errors=True)  # Only the isolated temp fixture.
        player = {"DataVersion": 3953, "Health": 20.0, "Pos": [1.5, 64.0, 1.5]} if player is None else player
        log = self.base / "conversion.log"
        log.parent.mkdir(parents=True, exist_ok=True)
        log.write_bytes(b"synthetic conversion log\n")
        files = {"level.dat": b"synthetic level placeholder",
                 self.player_save: encode_player_save(player),
                 "players/stats/" + Path(self.player_save).stem + ".json": b'{"stats": {}}',
                 "entities/r.0.0.mca": b"synthetic region placeholder",
                 "infinity-agents.json": json.dumps(self.registration).encode()}
        for world in (self.before, self.after):
            for relative, body in files.items():
                path = world / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(body)
        originals = {relative: gate.digest(self.before / relative) for relative in files}
        lab = self.base / "snapshot5-world-conversion-lab"
        self.write(lab / "conversion-result.json", {
            "exit_code": 0, "clean_stop": True, "no_player_logins": True, "input_fixture_unchanged": True,
            "terrain_refresh": False, "force_load_removed": True, "passed_preservation_checks": True,
            "mods": label, "input_file_count": len(originals), "offline_player_file_count": 2,
            "all_offline_player_files_byte_identical": True, "private_mod_json": ["infinity-agents.json"],
            "source_data_version": 3953, "converted_level_data_version": 5125,
            "helpers": [{"uuid": self.identity}], "log": str(log.relative_to(self.root)),
            "log_sha256": gate.digest(log)})
        self.write(lab / "world-copy-manifest.json", {
            "source_world": str(self.before.relative_to(self.root)),
            "converted_world": str(self.after.relative_to(self.root)),
            "live_world_used": False, "terrain_refresh": False, "copied_world_files_match": True,
            "mods": label, "original_fixture_files": originals, "input_file_count": len(originals)})
        self.write(self.base / "world-semantics-comparison.json", {
            "passed": True, "live_world_used": False, "original_fixture_unchanged": True,
            "mod_json_equal": {"infinity-agents.json": True}, "converted_data_version": 5125,
            "helper_comparison": [{}]})

    @staticmethod
    def write(path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data))

    def edit_evidence(self, change):
        """Apply change(result, manifest) to the synthetic conversion JSON."""
        lab = self.base / "snapshot5-world-conversion-lab"
        result = json.loads((lab / "conversion-result.json").read_text())
        manifest = json.loads((lab / "world-copy-manifest.json").read_text())
        change(result, manifest)
        self.write(lab / "conversion-result.json", result)
        self.write(lab / "world-copy-manifest.json", manifest)

    def storage_stub(self, before_entities, after_entities):
        # MOCK: synthetic decoded NBT replaces real region bytes; see class docstring.
        decoded = {"before": {"Entities": before_entities}, "after": {"Entities": after_entities}}
        worlds = {self.before: "before", self.after: "after"}
        parsed = self.parsed

        def region_records(path):
            return [(0, 0, 0, 0, worlds[Path(path).parents[1]], 3)]

        def unpack(data, compression):
            self.assertEqual(compression, 3)
            return data

        class SyntheticNBT:
            def __init__(inner, data):
                inner.data = data

            def parse(inner):
                parsed.append(inner.data)
                return decoded[inner.data]

        def saved_nbt(path):
            path = Path(path)
            world = "before" if path.parent == self.before else "after"
            if path.name == "level.dat":  # MOCK: synthetic decoded level data.
                data = {"DataVersion": 3953 if world == "before" else 5125, "Difficulty": 2}
                if self.level_players[world] is not None:
                    data["Player"] = copy.deepcopy(self.level_players[world])
                return {"Data": data}
            if path.name == "level.dat_old":  # Generated gzip NBT through the REAL parser.
                self.level_reads.append(world)
                return real_storage.saved_nbt(path)
            # Player saves go through the REAL bounded gzip/NBT parser on generated bytes.
            self.player_reads.append("before" if path.parents[2] == self.before else "after")
            return real_storage.saved_nbt(path)

        return types.SimpleNamespace(identity=real_storage.identity, region_records=region_records,
                                     unpack=unpack, NBT=SyntheticNBT, saved_nbt=saved_nbt)

    def run_world(self, before_entities, after_entities):
        stub = self.storage_stub(before_entities, after_entities)
        with mock.patch.dict(sys.modules, {"terrain_storage": stub}), \
                mock.patch.object(sys, "path", list(sys.path)), \
                mock.patch.object(gate, "verify_nested_passengers", wraps=gate.verify_nested_passengers) as traversal:
            summary = gate.native_world(self.root, self.base, self.label, {})
        return summary, traversal

    def world(self, *extra):
        return [copy.deepcopy(self.helper), *extra]

    def assert_rejected(self, before, after, pattern):
        with self.assertRaisesRegex(ValueError, pattern) as caught:
            self.run_world(before, after)
        message = str(caught.exception)
        owner = self.registration["agents"][self.identity]["owner"]
        for private in (self.identity, owner, "buddy", str(self.root), "Saved named spear",
                        Path(self.player_save).stem, *self.private_extra):
            self.assertNotIn(private, message)
        return message

    def test_ordinary_unrelated_riders_pass_and_traversal_runs_on_both_worlds(self):
        before_vehicle = chain(2, rider("minecraft:chicken", tags=["unrelated_tag"]))
        after_vehicle = copy.deepcopy(before_vehicle)
        before, after = self.world(before_vehicle), self.world(after_vehicle)
        saved = copy.deepcopy((before, after))
        summary, traversal = self.run_world(before, after)
        self.assertEqual(summary["helpers"], 1)
        self.assertTrue(summary["helper_decoded_fields_preserved"])
        self.assertFalse(summary["helper_native_nbt_tag_kinds_verified"])
        self.assertNotIn("helper_stable_fields_preserved", summary)
        self.assertEqual(self.parsed, ["before", "after"])
        inspected = [call.args[0] for call in traversal.call_args_list]
        self.assertEqual(len(inspected), 4)
        for entity in (*before, *after):
            self.assertTrue(any(entity is seen for seen in inspected))
        self.assertTrue(all(call.args[1] == {self.identity} for call in traversal.call_args_list))
        self.assertEqual((before, after), saved)  # Read-only: nothing removed or rewritten.

    def test_top_level_duplicate_and_unregistered_helpers_still_fail(self):
        self.assert_rejected(self.world(copy.deepcopy(self.helper)), self.world(), "duplicate helper UUID")
        self.assert_rejected(self.world(), self.world(copy.deepcopy(self.helper)), "duplicate helper UUID")
        stray = rider("minecraft:iron_golem", tags=["infinity_agent"])
        self.assert_rejected(self.world(stray), self.world(copy.deepcopy(stray)), "unregistered tagged helper")

    def test_registered_helper_duplicate_nested_under_another_entity_fails(self):
        vehicle = rider("minecraft:minecart", passengers=[copy.deepcopy(self.helper)])
        self.assert_rejected(self.world(vehicle), self.world(copy.deepcopy(vehicle)),
                             "nested passenger is or may be a helper")

    def test_unregistered_tagged_helper_nested_several_levels_fails(self):
        vehicle = chain(4, rider("minecraft:iron_golem", tags=["infinity_agent", "infinity_mode_follow"]))
        self.assert_rejected(self.world(vehicle), self.world(copy.deepcopy(vehicle)),
                             "nested passenger is or may be a helper")

    def test_registered_identity_without_helper_tag_fails(self):
        for tags in (None, [], ["unrelated_tag"]):
            vehicle = chain(2, rider("minecraft:iron_golem", identity=self.identity, tags=tags))
            with self.subTest(tags=tags):
                self.assert_rejected(self.world(vehicle), self.world(copy.deepcopy(vehicle)),
                                     "nested passenger is or may be a helper")

    def test_nested_issue_only_before_or_only_after_fails(self):
        bad = lambda: rider("minecraft:oak_boat", passengers=[copy.deepcopy(self.helper)])
        clean = lambda: chain(1, rider())
        for where, before, after, decoded in (("before", bad(), clean(), ["before"]),
                                              ("after", clean(), bad(), ["before", "after"])):
            with self.subTest(where=where):
                self.parsed.clear()
                self.assert_rejected(self.world(before), self.world(after), "nested passenger is or may be a helper")
                self.assertEqual(self.parsed, decoded)

    def test_malformed_shared_cyclic_or_excessive_passenger_trees_fail_closed(self):
        def self_cycle():
            vehicle = rider("minecraft:oak_boat", passengers=[])
            vehicle["Passengers"].append(vehicle)
            return vehicle

        def deep_cycle():
            first = rider(passengers=[])
            second = rider(passengers=[first])
            first["Passengers"].append(second)
            return rider("minecraft:oak_boat", passengers=[first])

        def shared_container():
            shared = [rider()]
            return rider("minecraft:oak_boat", passengers=[rider(passengers=shared), rider(passengers=shared)])

        def shared_node():
            node = rider()
            return rider("minecraft:oak_boat", passengers=[node, node])

        def with_field(**fields):
            node = rider()
            node.update(fields)
            return rider("minecraft:oak_boat", passengers=[node])

        def nested_null_container():
            leaf = rider()
            leaf["Passengers"] = None
            return chain(2, leaf)

        def missing_uuid():
            node = rider()
            del node["UUID"]
            return rider("minecraft:oak_boat", passengers=[node])

        cases = {
            "dict container": lambda: rider(passengers={"0": rider()}),
            "string container": lambda: rider(passengers="infinity_agent"),
            "non-compound node": lambda: rider(passengers=["infinity_agent"]),
            "nested malformed container": nested_null_container,
            "string tags": lambda: with_field(Tags="infinity_agent"),
            "non-string tag": lambda: with_field(Tags=[1]),
            "missing identity": missing_uuid,
            "short identity": lambda: with_field(UUID=[1, 2, 3]),
            "boolean identity": lambda: with_field(UUID=[True, 2, 3, 4]),
            "out of range identity": lambda: with_field(UUID=[2 ** 31, 2, 3, 4]),
            "string identity": lambda: with_field(UUID=self.identity),
            "ambiguous identity": lambda: with_field(uuid=self.identity),
            "legacy identity alongside UUID": lambda: with_field(UUIDMost=1),
            "self cycle": self_cycle,
            "deep cycle": deep_cycle,
            "shared container": shared_container,
            "shared node": shared_node,
            "excessive nesting": lambda: chain(gate.NESTED_PASSENGER_DEPTH_LIMIT + 1, rider()),
            "excessive riders": lambda: rider("minecraft:oak_boat",
                                              passengers=[rider() for _ in range(gate.NESTED_PASSENGER_NODE_LIMIT + 1)]),
        }
        for name, build in cases.items():
            with self.subTest(case=name):
                self.assert_rejected(self.world(build()), self.world(), "passenger")

    # Offline player saves. Before/after copies are byte-equal by contract, so a
    # helper-like player save is rejected during the before scan; the passing
    # cases prove the after copy is decoded too, and the after-only case proves
    # the byte contract still stops a divergent copy before decoding.

    def player_bytes(self):
        return [(world / self.player_save).read_bytes() for world in (self.before, self.after)]

    def test_player_root_vehicle_absent_passes_and_both_worlds_are_decoded(self):
        saved = self.player_bytes()
        summary, traversal = self.run_world(self.world(), self.world())
        self.assertEqual(summary["helpers"], 1)
        self.assertEqual(summary["offline_player_files"], 2)
        self.assertEqual(self.player_reads, ["before", "after"])
        self.assertEqual(traversal.call_count, 2)  # Region helpers only; no RootVehicle.
        self.assertEqual(self.player_bytes(), saved)

    def test_ordinary_player_vehicle_tree_with_unrelated_identities_passes(self):
        pig = rider("minecraft:pig", tags=["unrelated_tag"], passengers=[
            rider("minecraft:chicken", passengers=[rider("minecraft:zombie", tags=[])]), rider("minecraft:cat")])
        self.prepare(mounted(pig))
        saved = self.player_bytes()
        summary, traversal = self.run_world(self.world(), self.world())
        self.assertEqual(summary["helpers"], 1)
        self.assertEqual(self.player_reads, ["before", "after"])
        self.assertEqual(traversal.call_count, 4)  # One helper per region world plus each decoded RootVehicle.
        self.assertEqual(self.player_bytes(), saved)  # Read-only.

    def test_helper_like_player_root_vehicle_fails(self):
        cases = {"registered tagged helper": lambda: copy.deepcopy(self.helper),
                 "unregistered tagged helper": lambda: rider("minecraft:iron_golem", tags=["infinity_agent"]),
                 "untagged registered identity": lambda: rider("minecraft:iron_golem", identity=self.identity),
                 "unrelated-tagged registered identity":
                     lambda: rider("minecraft:pig", identity=self.identity, tags=["unrelated_tag"])}
        for name, build in cases.items():
            with self.subTest(case=name):
                self.prepare(mounted(build()))
                self.player_reads.clear()
                self.assert_rejected(self.world(), self.world(), "player root vehicle is or may be a helper")
                self.assertEqual(self.player_reads, ["before"])

    def test_helper_like_rider_nested_in_player_vehicle_fails(self):
        # The region world independently holds the registered top-level helper.
        cases = {"pig carrying tagged helper duplicate":
                     lambda: rider("minecraft:pig", passengers=[copy.deepcopy(self.helper)]),
                 "deep unregistered tagged helper":
                     lambda: chain(4, rider("minecraft:iron_golem", tags=["infinity_agent"])),
                 "untagged registered identity":
                     lambda: chain(2, rider("minecraft:iron_golem", identity=self.identity))}
        for name, build in cases.items():
            with self.subTest(case=name):
                self.prepare(mounted(build()))
                self.player_reads.clear()
                self.assert_rejected(self.world(), self.world(), "nested passenger is or may be a helper")
                self.assertEqual(self.player_reads, ["before"])

    def test_player_attachment_reference_to_registered_helper_fails(self):
        self.prepare(mounted(rider("minecraft:pig"), attach=native_uuid(self.identity)))
        self.assert_rejected(self.world(), self.world(), "vehicle attachment may reference a helper")
        self.assertEqual(self.player_reads, ["before"])

    def test_malformed_player_root_vehicle_fails_closed(self):
        attach = [1, 2, 3, 4]

        def pig(**fields):
            entity = rider("minecraft:pig")
            entity.update(fields)
            return entity

        def without_identity():
            entity = pig()
            del entity["UUID"]
            return {"RootVehicle": {"Attach": attach, "Entity": entity}}

        cases = {
            "string root vehicle": lambda: {"RootVehicle": "minecraft:pig"},
            "list root vehicle": lambda: {"RootVehicle": []},
            "empty root vehicle": lambda: {"RootVehicle": {}},
            "missing attach": lambda: {"RootVehicle": {"Entity": pig()}},
            "missing entity": lambda: {"RootVehicle": {"Attach": attach}},
            "short attach": lambda: {"RootVehicle": {"Attach": [1, 2, 3], "Entity": pig()}},
            "string attach": lambda: {"RootVehicle": {"Attach": "minecraft:pig", "Entity": pig()}},
            "extra field": lambda: {"RootVehicle": {"Attach": attach, "Entity": pig(), "Extra": 1}},
            "string entity": lambda: {"RootVehicle": {"Attach": attach, "Entity": "minecraft:pig"}},
            "list entity": lambda: {"RootVehicle": {"Attach": attach, "Entity": [pig()]}},
            "string tags": lambda: mounted(pig(Tags="infinity_agent")),
            "non-string tag": lambda: mounted(pig(Tags=[1])),
            "missing identity": without_identity,
            "string identity": lambda: mounted(pig(UUID=self.identity), attach=attach),
            "short identity": lambda: mounted(pig(UUID=[1, 2, 3]), attach=attach),
            "ambiguous identity": lambda: mounted(pig(uuid=self.identity)),
            "legacy identity alongside UUID": lambda: mounted(pig(UUIDLeast=1)),
            "string passengers": lambda: mounted(pig(Passengers="infinity_agent")),
            "non-compound passenger": lambda: mounted(pig(Passengers=["infinity_agent"])),
            "excessive nesting": lambda: mounted(chain(gate.NESTED_PASSENGER_DEPTH_LIMIT + 1, rider())),
        }
        for name, build in cases.items():
            with self.subTest(case=name):
                self.prepare(build())
                self.assert_rejected(self.world(), self.world(), "RootVehicle|root vehicle|passenger")

    def test_undecodable_player_save_fails_closed_generically(self):
        for body in (b"not gzip", gzip.compress(b"\x08\x00\x00"), gzip.compress(b"\x0a\x00\x00")):
            with self.subTest(body=body):
                self.prepare()
                for world in (self.before, self.after):
                    (world / self.player_save).write_bytes(body)
                sha = gate.digest(self.before / self.player_save)
                self.edit_evidence(lambda result, manifest: manifest["original_fixture_files"].update({self.player_save: sha}))
                self.assert_rejected(self.world(), self.world(), "offline player save could not be decoded")

    def test_after_only_player_change_is_stopped_by_byte_contract_before_decoding(self):
        (self.after / self.player_save).write_bytes(
            encode_player_save(mounted(rider("minecraft:pig", passengers=[copy.deepcopy(self.helper)]))))
        self.assert_rejected(self.world(), self.world(), "offline player data/stat/advancement bytes changed")
        self.assertEqual(self.player_reads, [])

    def test_player_data_inventory_must_match_manifest_in_both_worlds(self):
        stem = Path(self.player_save).stem
        extra = encode_player_save({"DataVersion": 3953})

        def write(worlds, relative, body=extra):
            for world in worlds:
                path = world / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(body)

        def ghost(result, manifest):
            manifest["original_fixture_files"]["players/data/" + str(uuid.uuid4()) + ".dat"] = "0" * 64
            for document in (result, manifest):
                document["input_file_count"] += 1
            result["offline_player_file_count"] += 1

        def drop(result, manifest):
            del manifest["original_fixture_files"][self.player_save]
            for document in (result, manifest):
                document["input_file_count"] -= 1
            result["offline_player_file_count"] -= 1

        both = (self.before, self.after)
        cases = {
            "missing after": lambda: (self.after / self.player_save).unlink(),
            "missing before": lambda: (self.before / self.player_save).unlink(),
            "unmanifested in both": lambda: write(both, "players/data/" + str(uuid.uuid4()) + ".dat"),
            "additional after only": lambda: write((self.after,), "players/data/" + str(uuid.uuid4()) + ".dat"),
            "unmanifested backup": lambda: write(both, "players/data/" + stem + ".dat_old"),
            "subdirectory": lambda: [(world / "players/data/nested").mkdir() for world in both],
            "populated legacy layout": lambda: write(both, "playerdata/" + stem + ".dat"),
            "manifest lists absent save": lambda: self.edit_evidence(ghost),
            "manifest omits actual save": lambda: self.edit_evidence(drop),
        }
        for name, mutate in cases.items():
            with self.subTest(case=name):
                self.prepare()
                mutate()
                self.player_reads.clear()
                self.assert_rejected(self.world(), self.world(), "offline player")
                self.assertEqual(self.player_reads, [])

    def test_stats_and_old_save_alone_do_not_count_as_an_active_player_save(self):
        old_save = self.player_save + "_old"
        for world in (self.before, self.after):
            (world / self.player_save).rename(world / old_save)

        def replace_with_backup(result, manifest):
            originals = manifest["original_fixture_files"]
            originals[old_save] = originals.pop(self.player_save)

        self.edit_evidence(replace_with_backup)
        self.assert_rejected(self.world(), self.world(), "offline player data lacks an active UUID-named save")
        self.assertEqual(self.player_reads, [])

    # Top-level region entity identity and tags.

    def test_ambiguous_identity_cannot_hide_registered_uuid_at_top_level(self):
        # Reproduction: registered native UUID plus an unrelated lowercase uuid, no helper tag.
        for field, value in (("uuid", str(uuid.uuid4())), ("UUIDMost", 1), ("UUIDLeast", 1)):
            with self.subTest(field=field):
                decoy = rider("minecraft:pig", identity=self.identity)
                decoy[field] = value
                self.assert_rejected(self.world(decoy), self.world(copy.deepcopy(decoy)),
                                     "ambiguous top-level saved entity identity")
        helper = copy.deepcopy(self.helper)
        helper["uuid"] = str(uuid.uuid4())
        self.assert_rejected([helper], [copy.deepcopy(helper)], "ambiguous top-level saved entity identity")
        # Without ambiguity the strict identity still finds the duplicate.
        untagged = rider("minecraft:pig", identity=self.identity)
        self.assert_rejected(self.world(untagged), self.world(), "duplicate helper UUID")

    def test_malformed_top_level_identity_or_tags_fail_closed(self):
        def without_uuid():
            entity = rider("minecraft:pig")
            del entity["UUID"]
            return entity

        def with_field(**fields):
            entity = rider("minecraft:pig")
            entity.update(fields)
            return entity

        cases = {"string tags": lambda: with_field(Tags="infinity_agent"),
                 "non-string tag": lambda: with_field(Tags=[1]),
                 "missing identity": without_uuid,
                 "short identity": lambda: with_field(UUID=[1, 2, 3]),
                 "string identity": lambda: with_field(UUID=self.identity),
                 "non-compound entity": lambda: "minecraft:pig"}
        for name, build in cases.items():
            with self.subTest(case=name):
                self.assert_rejected(self.world(build()), self.world(), "top-level saved entity")
        helper = copy.deepcopy(self.helper)
        helper["Tags"] = "infinity_agent"
        self.assert_rejected([helper], [copy.deepcopy(helper)], "malformed top-level saved entity tags")

    # level.dat host player and optional level.dat_old backups.

    def write_level_backup(self, worlds, data):
        for world in worlds:
            (world / "level.dat_old").write_bytes(encode_player_save(data))

    def test_level_host_player_vehicle_is_inspected_in_both_worlds(self):
        # Reproduction: Data.Player.RootVehicle with a tagged duplicate nested passenger.
        bad = mounted(rider("minecraft:pig", passengers=[copy.deepcopy(self.helper)]))
        for where in ("before", "after"):
            with self.subTest(where=where):
                self.level_players = {"before": None, "after": None}
                self.level_players[where] = bad
                self.assert_rejected(self.world(), self.world(), "nested passenger is or may be a helper")
        self.level_players = {"before": None, "after": None}
        self.level_players["after"] = mounted(copy.deepcopy(self.helper))
        self.assert_rejected(self.world(), self.world(), "player root vehicle is or may be a helper")
        ordinary = mounted(rider("minecraft:pig", passengers=[rider("minecraft:chicken")]))
        self.level_players = {"before": ordinary, "after": copy.deepcopy(ordinary)}
        summary, _ = self.run_world(self.world(), self.world())
        self.assertEqual(summary["helpers"], 1)

    def test_level_backup_absent_or_ordinary_passes_and_is_decoded_per_copy(self):
        self.run_world(self.world(), self.world())
        self.assertEqual(self.level_reads, [])  # Absent backups are legitimate.
        self.write_level_backup((self.before,), {"Data": {"DataVersion": 3953}})
        self.write_level_backup((self.after,), {"Data": {"DataVersion": 5125, "Player": mounted(
            rider("minecraft:pig", passengers=[rider("minecraft:chicken")]))}})
        self.level_reads.clear()
        summary, _ = self.run_world(self.world(), self.world())
        self.assertEqual(summary["helpers"], 1)
        self.assertEqual(self.level_reads, ["before", "after"])

    def test_level_backup_helper_or_malformed_fails_closed(self):
        bad = {"Data": {"Player": {"ShoulderEntityLeft": copy.deepcopy(self.helper)}}}
        for where in ("before", "after"):
            with self.subTest(where=where):
                self.prepare()
                self.write_level_backup((getattr(self, where),), bad)
                self.assert_rejected(self.world(), self.world(), "player shoulder entity is or may be a helper")
        for name, body in (("undecodable", b"not gzip"), ("missing Data", encode_player_save({"Player": {}})),
                           ("non-compound Data", encode_player_save({"Data": "x"}))):
            with self.subTest(case=name):
                self.prepare()
                (self.after / "level.dat_old").write_bytes(body)
                self.assert_rejected(self.world(), self.world(), "level backup")
        self.prepare()
        target = self.base / "outside-backup.dat"
        target.write_bytes(encode_player_save({"Data": {}}))
        (self.before / "level.dat_old").symlink_to(target)
        self.level_reads.clear()
        self.assert_rejected(self.world(), self.world(), "missing or oversized level backup")
        self.assertEqual(self.level_reads, [])  # The symlink was never followed into the parser.

    # Shoulder entities.

    def test_shoulder_entities_ordinary_or_empty_pass(self):
        self.prepare({"ShoulderEntityLeft": rider("minecraft:parrot", tags=["unrelated_tag"]),
                      "ShoulderEntityRight": {}})
        summary, traversal = self.run_world(self.world(), self.world())
        self.assertEqual(summary["helpers"], 1)
        self.assertEqual(traversal.call_count, 4)  # Region helper plus non-empty shoulder, per world.

    def test_helper_like_or_malformed_shoulder_entity_fails(self):
        # Reproduction: a tagged duplicate in ShoulderEntityLeft.
        cases = {
            "tagged duplicate left": ({"ShoulderEntityLeft": copy.deepcopy(self.helper)}, "shoulder entity is or may be"),
            "untagged registered right": ({"ShoulderEntityRight": rider("minecraft:parrot", identity=self.identity)},
                                          "shoulder entity is or may be"),
            "nested tagged rider": ({"ShoulderEntityLeft": rider("minecraft:parrot", passengers=[
                rider("minecraft:iron_golem", tags=["infinity_agent"])])}, "nested passenger is or may be"),
            "string shoulder": ({"ShoulderEntityLeft": "minecraft:parrot"}, "malformed offline player shoulder"),
            "list shoulder": ({"ShoulderEntityRight": []}, "malformed offline player shoulder"),
            "shoulder without identity": ({"ShoulderEntityLeft": {"id": "minecraft:parrot"}}, "shoulder entity identity"),
            "shoulder string tags": ({"ShoulderEntityLeft": dict(rider("minecraft:parrot"), Tags="infinity_agent")},
                                     "malformed player shoulder entity tags"),
        }
        for name, (player, pattern) in cases.items():
            with self.subTest(case=name):
                self.prepare(player)
                self.player_reads.clear()
                self.assert_rejected(self.world(), self.world(), pattern)
                self.assertEqual(self.player_reads, ["before"])

    # RootVehicle.Attach resolution.

    def test_attach_must_resolve_exactly_once_in_vehicle_tree(self):
        chicken = rider("minecraft:chicken")
        self.prepare(mounted(rider("minecraft:pig", passengers=[chicken]), attach=list(chicken["UUID"])))
        summary, _ = self.run_world(self.world(), self.world())
        self.assertEqual(summary["helpers"], 1)  # Attach to a nested rider resolves.
        self.prepare(mounted(rider("minecraft:pig"), attach=native_uuid(str(uuid.uuid4()))))
        self.assert_rejected(self.world(), self.world(), "attachment does not resolve")
        pig = rider("minecraft:pig")
        pig["Passengers"] = [dict(rider("minecraft:chicken"), UUID=list(pig["UUID"]))]
        self.prepare(mounted(pig))
        self.assert_rejected(self.world(), self.world(), "duplicate identity in offline player vehicle tree")

    # Reserved dismissed / guarded-removal identities.

    def use_reserved(self, fmt):
        dismissed = str(uuid.uuid4())
        self.private_extra.append(dismissed)
        self.registration["dismissed"] = [dismissed]
        if fmt == 3:
            self.registration.update(format=3, guardedRemovals=[dismissed], pendingCreations=[])
        return dismissed

    def test_untagged_reserved_identity_is_helper_like_everywhere(self):
        for fmt in (2, 3):
            original = copy.deepcopy(self.registration)
            dismissed = self.use_reserved(fmt)
            ghost = lambda: rider("minecraft:iron_golem", identity=dismissed)
            region = {"top-level": (lambda: [ghost()], "top-level saved entity is or may be"),
                      "region rider": (lambda: [rider("minecraft:minecart", passengers=[ghost()])],
                                       "nested passenger is or may be")}
            player = {"player vehicle": (lambda: mounted(ghost()), "player root vehicle is or may be"),
                      "player rider": (lambda: mounted(rider("minecraft:pig", passengers=[ghost()])),
                                       "nested passenger is or may be"),
                      "shoulder": (lambda: {"ShoulderEntityRight": ghost()}, "shoulder entity is or may be")}
            with self.subTest(fmt=fmt, case="ordinary roster passes"):
                self.prepare()
                self.run_world(self.world(), self.world())
            for name, (extra, pattern) in region.items():
                with self.subTest(fmt=fmt, case=name):
                    self.prepare()
                    self.assert_rejected(self.world(*extra()), self.world(*extra()), pattern)
            for name, (build, pattern) in player.items():
                with self.subTest(fmt=fmt, case=name):
                    self.prepare(build())
                    self.assert_rejected(self.world(), self.world(), pattern)
            with self.subTest(fmt=fmt, case="level host"):
                self.prepare()
                self.level_players = {"before": None, "after": mounted(ghost())}
                self.assert_rejected(self.world(), self.world(), "player root vehicle is or may be")
                self.level_players = {"before": None, "after": None}
            self.registration = original

    def test_invalid_old_format_dismissed_list_fails_closed(self):
        valid = str(uuid.uuid4())
        for name, dismissed in (("not a list", valid), ("overlaps active helper", [self.identity]),
                                ("duplicate", [valid, valid]), ("noncanonical", [valid.upper()]),
                                ("non-string", [1]), ("oversized", [str(uuid.uuid4()) for _ in range(257)])):
            with self.subTest(case=name):
                self.registration["dismissed"] = dismissed
                self.prepare()
                self.assert_rejected(self.world(), self.world(), "removal provenance")
        del self.registration["dismissed"]


class OfflinePlayerVehicleTests(unittest.TestCase):
    """Direct decoded-dict checks of the production RootVehicle traversal.

    MOCK BOUNDARY: hand-built dicts, including shapes the native NBT parser can
    never produce (cycles, shared nodes, out-of-range ints). Not real player
    saves, durability or crash-recovery evidence.
    """
    REGISTERED = {"34e67df0-b40c-491d-a6ec-5d706e2cb598"}

    def test_absent_and_ordinary_root_vehicle_pass(self):
        gate.verify_offline_player_save({}, self.REGISTERED)
        gate.verify_offline_player_save({"Health": 20.0}, self.REGISTERED)
        gate.verify_offline_player_save(mounted(chain(3, rider("minecraft:chicken"))), self.REGISTERED)

    def test_cyclic_shared_and_unencodable_trees_fail_closed(self):
        def self_cycle():
            pig = rider("minecraft:pig", passengers=[])
            pig["Passengers"].append(pig)
            return mounted(pig)

        def deep_cycle():
            first = rider(passengers=[])
            first["Passengers"].append(rider(passengers=[first]))
            return mounted(rider("minecraft:pig", passengers=[first]))

        def shared_node():
            node = rider()
            return mounted(rider("minecraft:pig", passengers=[node, node]))

        def shared_container():
            shared = [rider()]
            return mounted(rider("minecraft:pig", passengers=[rider(passengers=shared), rider(passengers=shared)]))

        def vehicle_is_itself():
            vehicle = {"Attach": [1, 2, 3, 4]}
            vehicle["Entity"] = vehicle
            return {"RootVehicle": vehicle}

        def with_uuid(raw):
            entity = rider("minecraft:pig")
            entity["UUID"] = raw
            return {"RootVehicle": {"Attach": [1, 2, 3, 4], "Entity": entity}}

        cases = {"non-compound player": lambda: ["RootVehicle"], "self cycle": self_cycle, "deep cycle": deep_cycle,
                 "shared node": shared_node, "shared container": shared_container,
                 "vehicle is itself": vehicle_is_itself, "out of range identity": lambda: with_uuid([2 ** 31, 0, 0, 0]),
                 "boolean identity": lambda: with_uuid([True, 0, 0, 0]),
                 "out of range attach": lambda: {"RootVehicle": {"Attach": [2 ** 31, 0, 0, 0], "Entity": rider()}}}
        for name, build in cases.items():
            with self.subTest(case=name), self.assertRaises(ValueError):
                gate.verify_offline_player_save(build(), self.REGISTERED)


if __name__ == "__main__":
    unittest.main()
