"""Small bounded readers for ROM acceptance, never execute APK/native contents."""
import struct


def dex_classes(raw):
    if len(raw) < 112 or raw[:8] not in [b'dex\n' + str(v).encode() + b'\0' for v in ['035', '036', '037', '038', '039', '040']]:
        raise ValueError('Unsupported or truncated DEX header')
    u32 = lambda off: struct.unpack_from('<I', raw, off)[0]
    if u32(32) != len(raw) or u32(36) != 112 or u32(40) != 0x12345678:
        raise ValueError('Invalid DEX size/header/endianness')
    def table(count_at, off_at, width):
        count, off = u32(count_at), u32(off_at)
        if count > len(raw) // width or (count and off < 112) or off + count * width > len(raw):
            raise ValueError('Invalid DEX table bounds')
        return count, off
    strings, string_off = table(56, 60, 4)
    types, type_off = table(64, 68, 4)
    classes, class_off = table(96, 100, 32)
    definitions = set()
    for index in range(classes):
        class_idx = u32(class_off + index * 32)
        if class_idx >= types:
            raise ValueError('Invalid DEX class type index')
        string_idx = u32(type_off + class_idx * 4)
        if string_idx >= strings:
            raise ValueError('Invalid DEX descriptor index')
        offset = u32(string_off + string_idx * 4)
        for _ in range(5):
            if offset >= len(raw):
                raise ValueError('Truncated DEX string length')
            byte = raw[offset]
            offset += 1
            if byte < 128:
                break
        else:
            raise ValueError('Invalid DEX string length')
        end = raw.find(b'\0', offset)
        if end < 0:
            raise ValueError('Unterminated DEX descriptor')
        descriptor = raw[offset:end].decode('utf-8', errors='surrogatepass')
        if not descriptor.startswith('L') or not descriptor.endswith(';'):
            raise ValueError('Invalid class definition descriptor')
        definitions.add(descriptor)
    return definitions


def elf_info(raw):
    if len(raw) < 64 or raw[:7] != b'\x7fELF\x02\x01\x01':
        raise ValueError('Expected ELF64 little-endian native input')
    kind, machine = struct.unpack_from('<HH', raw, 16)
    if kind != 3 or machine != 183:
        raise ValueError('Expected AArch64 ET_DYN native input')
    offset = struct.unpack_from('<Q', raw, 32)[0]
    size, count = struct.unpack_from('<HH', raw, 54)
    if size < 56 or count == 0 or count > 256 or offset + size * count > len(raw):
        raise ValueError('Invalid ELF program headers')
    headers = [struct.unpack_from('<IIQQQQQQ', raw, offset + n * size) for n in range(count)]
    loads = [p for p in headers if p[0] == 1]
    if not loads:
        raise ValueError('ELF has no loadable segments')
    for p in loads:
        if p[2] + p[5] > len(raw) or p[7] < 16384 or p[7] & (p[7] - 1) or p[2] % p[7] != p[3] % p[7]:
            raise ValueError('ELF load segments must be bounded and 16KB aligned')
    dynamic = [p for p in headers if p[0] == 2]
    if len(dynamic) != 1 or dynamic[0][2] + dynamic[0][5] > len(raw):
        raise ValueError('Expected one bounded ELF dynamic section')
    tags = []
    for pos in range(dynamic[0][2], dynamic[0][2] + dynamic[0][5], 16):
        if pos + 16 > len(raw):
            raise ValueError('Truncated ELF dynamic entry')
        tag, value = struct.unpack_from('<QQ', raw, pos)
        if tag == 0:
            break
        tags.append((tag, value))
    strings = dict(tags)
    if 5 not in strings or 10 not in strings:
        raise ValueError('ELF dynamic string table missing')
    addr, length = strings[5], strings[10]
    mappings = [p for p in loads if p[3] <= addr and addr + length <= p[3] + p[5]]
    if len(mappings) != 1:
        raise ValueError('ELF string table is not file-backed')
    start = mappings[0][2] + addr - mappings[0][3]
    def string(at):
        if at >= length:
            raise ValueError('ELF string offset out of range')
        end = raw.find(b'\0', start + at, start + length)
        if end < 0:
            raise ValueError('Unterminated ELF dynamic string')
        return raw[start + at:end].decode('ascii')
    needed = sorted(string(v) for k, v in tags if k == 1)
    sonames = [string(v) for k, v in tags if k == 14]
    if len(sonames) > 1:
        raise ValueError('Multiple ELF SONAME entries')
    executable = any(p[0] == 3 for p in headers)
    return {'elfClass': 64, 'machine': 183, 'loadAlignment': min(p[7] for p in loads),
            'executable': executable, 'soname': sonames[0] if sonames else None, 'needed': needed}
