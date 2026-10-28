from test_panel_management import env, filtered
import pytest
from fastapi import HTTPException


def test_all_reseller_codes_are_scoped_and_confirmed(env):
    db, services, ops, rs, pm, a, b = env
    rs.generate_reseller_test(a['id'])
    used = rs.generate_reseller_subscription(a['id'], '24h')
    services.redeem_activation_code('lot14-client-device', used)
    other = rs.generate_reseller_test(b['id'])
    admin = services.issue_activation_code('LOT14-ADMIN', '24h')
    body = filtered(pm, status='all')
    preview = pm.filtered_codes(body, a['id'])
    assert preview['count'] == 2
    with pytest.raises(HTTPException):
        pm.filtered_codes(body, a['id'], delete=True)
    with pytest.raises(HTTPException):
        pm.filtered_codes(body)
    result = pm.filtered_codes(body.model_copy(update={'fingerprint': preview['fingerprint']}), a['id'], delete=True)
    assert result['deleted'] == 2
    assert rs.list_reseller_codes(a['id']) == []
    assert rs.list_reseller_codes(b['id'])[0]['code'] == other
    assert any(row['code'] == admin for row in ops.list_activation_codes())
    assert services.access_state('lot14-client-device')['remaining_seconds'] == 0


def test_all_with_query_keeps_unmatched_codes(env):
    _, _, _, rs, pm, a, _ = env
    rs.generate_reseller_test(a['id'])
    keep = rs.generate_reseller_subscription(a['id'], '1m')
    body = filtered(pm, status='all', query='test')
    preview = pm.filtered_codes(body, a['id'])
    assert preview['count'] == 1
    pm.filtered_codes(body.model_copy(update={'fingerprint': preview['fingerprint']}), a['id'], delete=True)
    assert rs.list_reseller_codes(a['id'])[0]['code'] == keep
