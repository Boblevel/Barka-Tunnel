import pytest
from app.vpn_profiles import validate_vless_proxy


def test_certificate_pin_validation():
    validate_vless_proxy({'pinned_cert_sha256': 'ab' * 32})
    validate_vless_proxy({'pinned_cert_sha256': ':'.join(['AB'] * 32)})
    validate_vless_proxy({})
    for value in ('invalid', 'ab' * 31, ':'.join(['ab'] * 33), 42):
        with pytest.raises(ValueError):
            validate_vless_proxy({'pinned_cert_sha256': value})
