"""Small valid binary manifests for upload/version regression tests."""
import io
import struct
import zipfile


def manifest_bytes(version=144, name='2.3.5.4', package='com.barkatunnel.app', split='', utf8=True, required_split=''):
    strings = ['manifest', 'package', package, 'versionCode', 'versionName', name,
               'http://schemas.android.com/apk/res/android', 'uses-sdk', 'minSdkVersion', 'split', split,
               'application', 'isSplitRequired', 'requiredSplitTypes', 'abi', 'meta-data', 'name',
               'com.android.vending.splits.required', 'value']
    encoded = bytearray()
    offsets = []
    for item in strings:
        offsets.append(len(encoded))
        if utf8:
            raw = item.encode('utf-8')
            encoded += bytes([len(item), len(raw)]) + raw + b'\0'
        else:
            encoded += struct.pack('<H', len(item)) + item.encode('utf-16le') + b'\0\0'
    while len(encoded) % 4:
        encoded += b'\0'
    start = 28 + 4 * len(strings)
    pool = struct.pack('<HHIIIIII', 1, 28, start + len(encoded), len(strings), 0, 0x100 if utf8 else 0, start, 0)
    pool += struct.pack('<' + 'I' * len(offsets), *offsets) + encoded
    def attr(ns, key, value, typ=3):
        return struct.pack('<IIIHBBI', ns, key, value if typ == 3 else 0xffffffff, 8, 0, typ, value)
    def element(tag, attrs):
        return struct.pack('<HHIII', 0x102, 16, 36 + len(attrs), 1, 0xffffffff) + struct.pack('<IIHHHHHH', 0xffffffff, tag, 20, 20, len(attrs)//20, 0, 0, 0) + attrs
    root = attr(0xffffffff, 1, 2) + attr(6, 3, version, 0x10) + attr(6, 4, 5)
    if split:
        root += attr(0xffffffff, 9, 10)
    chunks = pool + element(0, root) + element(7, attr(6, 8, 23, 0x10))
    if required_split == 'application':
        chunks += element(11, attr(6, 12, 1, 0x12))
    elif required_split == 'types':
        chunks += element(11, attr(6, 13, 14))
    elif required_split == 'metadata':
        chunks += element(11, b'') + element(15, attr(6, 16, 17) + attr(6, 18, 1, 0x12))
    return struct.pack('<HHI', 3, 8, 8 + len(chunks)) + chunks


def apk_bytes(size=64, **kwargs):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w') as archive:
        archive.writestr('AndroidManifest.xml', manifest_bytes(**kwargs))
        archive.writestr('classes.dex', bytes(size))
    return buf.getvalue()
