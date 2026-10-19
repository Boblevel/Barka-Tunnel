"""Read-only guard: reject live source drift before replacing any backend file."""
import hashlib
import json
import sys
from pathlib import Path


def verify(live: Path, stage: Path) -> None:
    baseline = json.loads((stage / 'deploy_baseline.json').read_text())
    present = {str(p.relative_to(live)) for p in (live / 'app').rglob('*.py')}
    expected = set(baseline)
    target = {str(p.relative_to(stage)) for p in (stage / 'app').rglob('*.py')}
    for name in sorted(present | expected | target):
        a, b = live / name, stage / name
        if a.is_symlink() or b.is_symlink():
            raise SystemExit('ARRÊT : lien symbolique inattendu : ' + name)
        actual = hashlib.sha256(a.read_bytes()).hexdigest() if a.is_file() else None
        desired = hashlib.sha256(b.read_bytes()).hexdigest() if b.is_file() else None
        approved = baseline.get(name, [None])
        if not isinstance(approved, list):
            approved = [approved]
        if actual not in [*approved, desired]:
            raise SystemExit('ARRÊT : source VPS modifiée depuis la vérification : ' + name)
        if name not in expected and name not in target:
            raise SystemExit('ARRÊT : fichier VPS non prévu : ' + name)
    for name in ('app/saspay.py', 'app/config.py', 'app/security.py', 'app/services.py'):
        # Version autorisée : activation immédiate après confirmation SasPay.
        expected_hash = 'afd138f54274cdfe587d695ef352453105edde9a085a54ebe9a4e1adc5c785d6' if name == "app/services.py" else baseline[name]
        approved_target = expected_hash if isinstance(expected_hash, list) else [expected_hash]
        if hashlib.sha256((stage / name).read_bytes()).hexdigest() not in approved_target:
            raise SystemExit('ARRÊT : intégration SasPay différente de celle fournie : ' + name)
    print('Sources VPS et intégration SasPay vérifiées.')


if __name__ == '__main__':
    verify(Path(sys.argv[1]), Path(sys.argv[2]))
