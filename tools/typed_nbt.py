"""Bounded, read-only NBT parser retaining on-disk tag kinds and scalar bits.

Used only to verify disposable copied-world evidence. It does not establish
that a world is complete, durable, or safe to modify.
"""
from dataclasses import dataclass
import struct

MAX_BYTES = 64 * 1024 * 1024
MAX_NODES = 250_000
MAX_COLLECTION = 250_000
MAX_DEPTH = 64
MAX_STRING_BYTES = 16 * 1024 * 1024
_WIDTH = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}
_FORMAT = {1: '>b', 2: '>h', 3: '>i', 4: '>q', 5: '>f', 6: '>d'}


@dataclass(frozen=True)
class Tag:
    kind: int
    # Scalar/array/string payloads are exact native bytes. List payloads are
    # (declared child kind, tuple of Tags); compound payloads map raw names to Tags.
    payload: object


def _valid_modified_utf8(data):
    """Require canonical Java modified UTF-8; keep UTF-16 surrogate bytes intact."""
    i = 0
    while i < len(data):
        a = data[i]
        if 1 <= a <= 0x7f:
            i += 1
        elif 0xc0 <= a <= 0xdf:
            if i + 1 >= len(data) or data[i + 1] & 0xc0 != 0x80:
                return False
            b = data[i + 1]
            value = ((a & 0x1f) << 6) | (b & 0x3f)
            if not (value >= 0x80 or (a == 0xc0 and b == 0x80)):
                return False
            i += 2
        elif 0xe0 <= a <= 0xef:
            if i + 2 >= len(data) or data[i + 1] & 0xc0 != 0x80 or data[i + 2] & 0xc0 != 0x80:
                return False
            value = ((a & 0x0f) << 12) | ((data[i + 1] & 0x3f) << 6) | (data[i + 2] & 0x3f)
            if value < 0x800:
                return False
            i += 3
        else:
            return False
    return True


class Reader:
    def __init__(self, data):
        if not isinstance(data, bytes) or len(data) > MAX_BYTES:
            raise ValueError('Invalid or oversized native NBT')
        self.data = data
        self.offset = 0
        self.nodes = 0
        self.string_bytes = 0

    def read(self, length):
        if length < 0 or length > len(self.data) - self.offset:
            raise ValueError('Truncated native NBT')
        out = self.data[self.offset:self.offset + length]
        self.offset += length
        return out

    def byte(self):
        return self.read(1)[0]

    def size(self):
        return struct.unpack('>i', self.read(4))[0]

    def text(self):
        length = struct.unpack('>H', self.read(2))[0]
        self.string_bytes += length
        if self.string_bytes > MAX_STRING_BYTES:
            raise ValueError('Native NBT string budget exceeded')
        result = self.read(length)
        if not _valid_modified_utf8(result):
            raise ValueError('Unsupported native NBT string encoding')
        return result

    def value(self, kind, depth=0):
        if depth > MAX_DEPTH:
            raise ValueError('Native NBT nesting limit exceeded')
        self.nodes += 1
        if self.nodes > MAX_NODES:
            raise ValueError('Native NBT node budget exceeded')
        if kind in _WIDTH:
            return Tag(kind, self.read(_WIDTH[kind]))
        if kind == 8:
            return Tag(kind, self.text())
        if kind == 10:
            children = {}
            while True:
                child_kind = self.byte()
                if child_kind == 0:
                    return Tag(kind, children)
                if child_kind not in range(1, 13):
                    raise ValueError('Unsupported native NBT tag')
                name = self.text()
                if name in children:
                    raise ValueError('Duplicate native NBT field')
                children[name] = self.value(child_kind, depth + 1)
        if kind in (7, 9, 11, 12):
            child_kind = self.byte() if kind == 9 else None
            count = self.size()
            limit = MAX_BYTES if kind == 7 else MAX_COLLECTION
            if count < 0 or count > limit:
                raise ValueError('Native NBT collection limit exceeded')
            if kind == 9:
                if child_kind not in range(0, 13) or (count and child_kind == 0):
                    raise ValueError('Invalid native NBT list type')
                if self.nodes + count > MAX_NODES:
                    raise ValueError('Native NBT node budget exceeded')
                return Tag(kind, (child_kind, tuple(self.value(child_kind, depth + 1) for _ in range(count))))
            width = {7: 1, 11: 4, 12: 8}[kind]
            return Tag(kind, self.read(count * width))
        raise ValueError('Unsupported native NBT tag')

    def parse(self):
        if self.byte() != 10:
            raise ValueError('Native NBT root must be a compound')
        self.text()
        result = self.value(10)
        if self.offset != len(self.data):
            raise ValueError('Trailing native NBT data')
        return result


def parse(data):
    return Reader(data).parse()


def plain(tag):
    """Produce the existing verifier's value view; never use it to infer types."""
    if tag.kind in _FORMAT:
        return struct.unpack(_FORMAT[tag.kind], tag.payload)[0]
    if tag.kind == 7:
        return tag.payload
    if tag.kind == 8:
        return tag.payload.decode('utf-8', 'surrogateescape')
    if tag.kind == 9:
        return [plain(value) for value in tag.payload[1]]
    if tag.kind == 10:
        return {name.decode('utf-8', 'surrogateescape'): plain(value) for name, value in tag.payload.items()}
    if tag.kind in (11, 12):
        fmt = '>i' if tag.kind == 11 else '>q'
        return [item[0] for item in struct.iter_unpack(fmt, tag.payload)]
    raise ValueError('Unsupported native NBT tag')


def same(first, second):
    """Compare native kinds and exact scalar/array bytes; compound order is irrelevant."""
    if not isinstance(first, Tag) or not isinstance(second, Tag) or first.kind != second.kind:
        return False
    if first.kind == 10:
        return first.payload.keys() == second.payload.keys() and all(
            same(first.payload[key], second.payload[key]) for key in first.payload)
    if first.kind == 9:
        a_kind, a_items = first.payload
        b_kind, b_items = second.payload
        return a_kind == b_kind and len(a_items) == len(b_items) and all(
            same(a, b) for a, b in zip(a_items, b_items))
    return first.payload == second.payload


def same_shape(first, second):
    """Compare tag kinds and collection shape without comparing changing values."""
    if not isinstance(first, Tag) or not isinstance(second, Tag) or first.kind != second.kind:
        return False
    if first.kind == 10:
        return first.payload.keys() == second.payload.keys() and all(
            same_shape(first.payload[key], second.payload[key]) for key in first.payload)
    if first.kind == 9:
        a_kind, a_items = first.payload
        b_kind, b_items = second.payload
        return a_kind == b_kind and len(a_items) == len(b_items) and all(
            same_shape(a, b) for a, b in zip(a_items, b_items))
    if first.kind in (7, 11, 12):
        return len(first.payload) == len(second.payload)
    return True
