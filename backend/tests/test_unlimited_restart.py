from test_custom_subscriptions import env


def test_restart_repairs_unlimited_and_preserves_finite_and_revoked_access(tmp_path):
    db,svc,rs,ops,_,_=env(tmp_path)
    unlimited=svc.issue_activation_code('MANUAL:unlimited-restart','unlimited')
    finite=svc.issue_activation_code('MANUAL:finite-restart','24h')
    svc.redeem_activation_code('unlimited-restart-device',unlimited)
    svc.redeem_activation_code('finite-restart-device',finite)
    with db.transaction() as cx:
        cx.execute("UPDATE activation_codes SET applied_until=redeemed_at WHERE plan_id='unlimited'")
        before=dict(cx.execute("SELECT * FROM activation_codes WHERE plan_id='24h'").fetchone())
    assert next(x for x in ops.list_activation_codes() if x['plan_id']=='unlimited')['status']=='redeemed'
    for _ in range(3):db.init_db()
    row=next(x for x in ops.list_activation_codes() if x['plan_id']=='unlimited')
    assert row['status']=='redeemed' and row['expires_at'] is None
    assert svc.access_state('unlimited-restart-device')['unlimited']
    with db.connect() as cx:assert dict(cx.execute("SELECT * FROM activation_codes WHERE plan_id='24h'").fetchone())==before
    ops.revoke_activation_code(unlimited);db.init_db()
    assert not svc.access_state('unlimited-restart-device')['allowed']
    assert next(x for x in ops.list_activation_codes() if x['plan_id']=='unlimited')['status']=='revoked'
    ops.reactivate_activation_code(unlimited)
    assert svc.access_state('unlimited-restart-device')['unlimited']
