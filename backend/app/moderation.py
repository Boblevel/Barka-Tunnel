"""Independent moderation flags; never alter purchased subscription dates."""
def state(cx, device_id):
    row = cx.execute("SELECT app_blocked,suggestions_blocked FROM device_moderation WHERE device_id=?",
                     (device_id,)).fetchone()
    return {"app_blocked": bool(row and row["app_blocked"]),
            "suggestions_blocked": bool(row and row["suggestions_blocked"])}


def app_blocked(cx, device_id):
    return state(cx, device_id)["app_blocked"]
