import importlib.util
import hashlib
import json
from pathlib import Path
import shutil
import pytest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('verify_deploy', ROOT / 'verify_deploy.py')
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)

def test_previous_release_can_upgrade_and_unknown_edit_is_rejected(tmp_path):
    live = tmp_path / 'live'
    stage = tmp_path / 'stage'
    shutil.copytree(ROOT / 'app', live / 'app')
    shutil.copytree(ROOT / 'app', stage / 'app')
    baseline = json.loads((ROOT / 'deploy_baseline.json').read_text())
    # CI shallow clones need no historical Git objects for this regression.
    previous = b'# previously deployed panel\n'
    (live / 'app/admin_panel.py').write_bytes(previous)
    baseline['app/admin_panel.py'].append(hashlib.sha256(previous).hexdigest())
    (stage / 'deploy_baseline.json').write_text(json.dumps(baseline))
    guard.verify(live, stage)
    panel = live / 'app/admin_panel.py'
    panel.write_text(panel.read_text() + '\n# modification inconnue\n')
    with pytest.raises(SystemExit, match='source VPS modifiée'):
        guard.verify(live, stage)

def test_redeployment_is_allowed_but_unknown_file_is_not(tmp_path):
    shutil.copytree(ROOT / 'app', tmp_path / 'app')
    guard.verify(tmp_path, ROOT)
    (tmp_path / 'app/unexpected.py').write_text('print(1)')
    with pytest.raises(SystemExit):guard.verify(tmp_path, ROOT)
