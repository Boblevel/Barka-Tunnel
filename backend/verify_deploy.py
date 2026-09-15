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
        if actual not in {baseline.get(name), desired}:
            raise SystemExit('ARRÊT : source VPS modifiée depuis la vérification : ' + name)
        if name not in expected and name not in target:
            raise SystemExit('ARRÊT : fichier VPS non prévu : ' + name)
    for name in ('app/saspay.py', 'app/config.py', 'app/security.py', 'app/services.py'):
        # Version autorisée : ajout ciblé du contrôle d’expiration revendeur.
        expected_hash = '8206c420e1c9ba97f7e6605679c402f0e97fba312c369051bd1295b15746f5cd' if name == "app/services.py" else baseline[name]
        if hashlib.sha256((stage / name).read_bytes()).hexdigest() != expected_hash:
            raise SystemExit('ARRÊT : intégration SasPay différente de celle fournie : ' + name)
    print('Sources VPS et intégration SasPay vérifiées.')


if __name__ == '__main__':
    verify(Path(sys.argv[1]), Path(sys.argv[2]))
