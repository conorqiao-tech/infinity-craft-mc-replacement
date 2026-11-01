"""Typed NBT checks use synthetic bytes; they never read a playable world."""
import struct
import sys
from pathlib import Path
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
import typed_nbt as nbt


def text(raw):
    return struct.pack('>H', len(raw)) + raw


def named(kind, name, payload):
    return bytes([kind]) + text(name) + payload


def document(*entries):
    return b'\x0a\x00\x00' + b''.join(entries) + b'\x00'


class TypedNBTTests(unittest.TestCase):
    def test_all_native_kinds_and_declared_list_kind_are_preserved(self):
        data = document(
            named(1, b'byte', struct.pack('>b', -1)),
            named(2, b'short', struct.pack('>h', -2)),
            named(3, b'int', struct.pack('>i', -3)),
            named(4, b'long', struct.pack('>q', -4)),
            named(5, b'float', struct.pack('>f', 0.5)),
            named(6, b'double', struct.pack('>d', 0.5)),
            named(7, b'bytes', struct.pack('>i', 2) + b'\x00\xff'),
            named(8, b'text', text(b'hello')),
            named(9, b'list', b'\x03' + struct.pack('>i', 2) + struct.pack('>ii', 7, -8)),
            named(10, b'compound', named(1, b'x', b'\x01') + b'\x00'),
            named(11, b'ints', struct.pack('>i', 2) + struct.pack('>ii', 7, -8)),
            named(12, b'longs', struct.pack('>i', 2) + struct.pack('>qq', 7, -8)))
        root = nbt.parse(data)
        self.assertEqual(root.kind, 10)
        names = (b'byte', b'short', b'int', b'long', b'float', b'double', b'bytes', b'text', b'list', b'compound', b'ints', b'longs')
        self.assertEqual([root.payload[name].kind for name in names], list(range(1, 13)))
        self.assertEqual(root.payload[b'list'].payload[0], 3)
        self.assertEqual(nbt.plain(root), {'byte': -1, 'short': -2, 'int': -3, 'long': -4,
                                           'float': 0.5, 'double': 0.5, 'bytes': b'\x00\xff',
                                           'text': 'hello', 'list': [7, -8], 'compound': {'x': 1},
                                           'ints': [7, -8], 'longs': [7, -8]})

    def test_equal_decoded_values_do_not_hide_types_or_signed_zero(self):
        pairs = [
            (named(1, b'x', b'\x01'), named(3, b'x', struct.pack('>i', 1))),
            (named(5, b'x', struct.pack('>f', 0.5)), named(6, b'x', struct.pack('>d', 0.5))),
            (named(11, b'x', struct.pack('>ii', 1, 7)), named(9, b'x', b'\x03' + struct.pack('>ii', 1, 7))),
            (named(9, b'x', b'\x00' + struct.pack('>i', 0)), named(9, b'x', b'\x08' + struct.pack('>i', 0))),
        ]
        for first, second in pairs:
            with self.subTest(first=first[0], second=second[0]):
                a, b = nbt.parse(document(first)), nbt.parse(document(second))
                self.assertEqual(nbt.plain(a), nbt.plain(b))
                self.assertFalse(nbt.same(a, b))
        plus = nbt.parse(document(named(5, b'x', struct.pack('>f', 0.0))))
        minus = nbt.parse(document(named(5, b'x', struct.pack('>f', -0.0))))
        self.assertFalse(nbt.same(plus, minus))
        self.assertTrue(nbt.same_shape(plus, minus))

    def test_compound_order_is_irrelevant_but_list_order_is_not(self):
        x, y = named(3, b'x', struct.pack('>i', 1)), named(3, b'y', struct.pack('>i', 2))
        self.assertTrue(nbt.same(nbt.parse(document(x, y)), nbt.parse(document(y, x))))
        one = named(9, b'x', b'\x03' + struct.pack('>iii', 2, 1, 2))
        two = named(9, b'x', b'\x03' + struct.pack('>iii', 2, 2, 1))
        self.assertFalse(nbt.same(nbt.parse(document(one)), nbt.parse(document(two))))
        self.assertTrue(nbt.same_shape(nbt.parse(document(one)), nbt.parse(document(two))))

    def test_canonical_modified_utf8_is_accepted_and_other_encodings_fail(self):
        valid = b'\xc0\x80\xed\xa0\xbd\xed\xb8\x80'  # Java NUL and a surrogate pair.
        parsed = nbt.parse(document(named(8, b'x', text(valid))))
        self.assertEqual(parsed.payload[b'x'].payload, valid)
        for bad in (b'\x00', b'\xc1\x81', b'\xe0\x80\x81', b'\xf0\x9f\x98\x80', b'\xc2'):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                nbt.parse(document(named(8, b'x', text(bad))))

    def test_malformed_or_unbounded_nbt_fails_closed(self):
        duplicate = document(named(3, b'x', struct.pack('>i', 1)), named(3, b'x', struct.pack('>i', 1)))
        invalid = [b'', b'\x01\x00\x00', document(named(13, b'x', b'')), duplicate,
                   document(named(9, b'x', b'\x00' + struct.pack('>i', 1))),
                   document(named(9, b'x', b'\x0d' + struct.pack('>i', 0))),
                   document(named(7, b'x', struct.pack('>i', -1))),
                   document(named(11, b'x', struct.pack('>i', nbt.MAX_COLLECTION + 1))),
                   document(named(12, b'x', struct.pack('>i', 1) + b'\x00')),
                   document() + b'\x00']
        for value in invalid:
            with self.subTest(value=value[:10]), self.assertRaises(ValueError):
                nbt.parse(value)
        nested = b'\x00'
        for _ in range(nbt.MAX_DEPTH + 1):
            nested = named(10, b'x', nested) + b'\x00'
        with self.assertRaises(ValueError):
            nbt.parse(b'\x0a\x00\x00' + nested)
        with mock.patch.object(nbt, 'MAX_NODES', 2), self.assertRaises(ValueError):
            nbt.parse(document(named(9, b'x', b'\x03' + struct.pack('>iii', 2, 1, 2))))
        with mock.patch.object(nbt, 'MAX_STRING_BYTES', 1), self.assertRaises(ValueError):
            nbt.parse(document(named(8, b'x', text(b'two'))))


if __name__ == '__main__':
    unittest.main()
