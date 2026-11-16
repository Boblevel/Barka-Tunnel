"""Read only the bounded binary Android manifest; never execute uploaded APKs."""
import struct
import zipfile

ANDROID_NS = 'http://schemas.android.com/apk/res/android'


def manifest_info(data):
    def u16(p):
        return struct.unpack_from('<H', data, p)[0]

    def u32(p):
        return struct.unpack_from('<I', data, p)[0]

    if len(data) < 8 or u16(0) != 3 or u32(4) != len(data):
        raise ValueError('Manifest Android binaire invalide.')
    strings = []
    info = {}
    p = u16(2)
    while p < len(data):
        kind, header, size = struct.unpack_from('<HHI', data, p)
        end = p + size
        if header < 8 or size < header or end > len(data):
            raise ValueError('Manifest Android tronqué.')
        if kind == 1:
            if header < 28:
                raise ValueError('Table de chaînes invalide.')
            count, flags, start = u32(p + 8), u32(p + 16), u32(p + 20)
            if count > 100000 or header + count * 4 > start or start > size:
                raise ValueError('Table de chaînes invalide.')
            strings = []
            for i in range(count):
                pos = p + start + u32(p + header + i * 4)
                if not p + start <= pos < end:
                    raise ValueError('Chaîne hors limites.')
                if flags & 0x100:
                    # UTF-8 stores UTF-16 character count, then byte count.
                    for _ in range(2):
                        length = data[pos]
                        pos += 1
                        if length & 0x80:
                            length = ((length & 0x7f) << 8) | data[pos]
                            pos += 1
                    stop = pos + length
                    if stop >= end or data[stop] != 0:
                        raise ValueError('Chaîne UTF-8 invalide.')
                    strings.append(data[pos:stop].decode('utf-8'))
                else:
                    length = u16(pos)
                    pos += 2
                    if length & 0x8000:
                        length = ((length & 0x7fff) << 16) | u16(pos)
                        pos += 2
                    stop = pos + length * 2
                    if stop + 2 > end or u16(stop) != 0:
                        raise ValueError('Chaîne UTF-16 invalide.')
                    strings.append(data[pos:stop].decode('utf-16le'))
        elif kind == 0x102:
            ext = p + header
            if ext + 20 > end:
                raise ValueError('Élément Android invalide.')
            tag = strings[u32(ext + 4)]
            attr_start, attr_size, count = u16(ext + 8), u16(ext + 10), u16(ext + 12)
            if attr_size < 20 or ext + attr_start + attr_size * count > end:
                raise ValueError('Attribut Android invalide.')
            attrs = {}
            for i in range(count):
                a = ext + attr_start + attr_size * i
                ns, name, raw = u32(a), strings[u32(a + 4)], u32(a + 8)
                namespace = '' if ns == 0xffffffff else strings[ns]
                value_type, value = data[a + 15], u32(a + 16)
                value = strings[raw] if raw != 0xffffffff else strings[value] if value_type == 3 else value
                attrs[(namespace, name)] = value
            if tag in ('manifest', 'application') and (
                    attrs.get((ANDROID_NS, 'isSplitRequired')) or
                    attrs.get((ANDROID_NS, 'requiredSplitTypes'))):
                raise ValueError('APK incomplet : fournissez un APK universel complet.')
            if (tag == 'meta-data' and attrs.get((ANDROID_NS, 'name')) == 'com.android.vending.splits.required'
                    and attrs.get((ANDROID_NS, 'value'))):
                raise ValueError('APK incomplet : fournissez un APK universel complet.')
            if tag == 'manifest':
                if info:
                    raise ValueError('Manifest dupliqué.')
                if attrs.get(('', 'split')) or attrs.get((ANDROID_NS, 'isSplitRequired')):
                    raise ValueError('APK fractionné : fournissez un APK universel complet.')
                info = {'package_name': attrs.get(('', 'package')),
                        'version_code': int(attrs.get((ANDROID_NS, 'versionCode'), 0)),
                        'version_name': str(attrs.get((ANDROID_NS, 'versionName'), ''))}
                if attrs.get((ANDROID_NS, 'versionCodeMajor'), 0):
                    raise ValueError('Code de version non pris en charge.')
            elif tag == 'uses-sdk':
                info['min_sdk'] = int(attrs.get((ANDROID_NS, 'minSdkVersion'), 1))
        p = end
    if info.get('package_name') != 'com.barkatunnel.app' or info.get('version_code', 0) < 1:
        raise ValueError('Cet APK ne correspond pas à Barka Tunnel.')
    return info


def read_apk_metadata(path):
    try:
        with zipfile.ZipFile(path) as archive:
            entry = archive.getinfo('AndroidManifest.xml')
            if entry.file_size > 1024 * 1024:
                raise ValueError('Manifest trop volumineux.')
            return manifest_info(archive.read(entry))
    except (KeyError, IndexError, struct.error, UnicodeError, zipfile.BadZipFile) as error:
        raise ValueError('APK ou manifest Android invalide.') from error
