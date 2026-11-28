import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).parent
spec=importlib.util.spec_from_file_location('deploy53',ROOT/'lot53_install_backend.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
PATCHES=json.loads((ROOT/'lot53_backend_patch.json').read_text())

class DeployTests(unittest.TestCase):
    def test_known_sections_are_idempotent_and_preserve_unrelated_code(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'app').mkdir()
            for name in module.FILES:
                source=(ROOT.parent/'backend/app'/name).read_text()
                for item in reversed(PATCHES):
                    if item['file']==name:
                        self.assertIn(item['new'],source)
                        source=source.replace(item['new'],item['old'][0],1)
                (root/'app'/name).write_text(source)
            result=module.merge(root,PATCHES)
            for name,source in result.items():
                (root/'app'/name).write_text(source+'\n# unrelated local customization\n')
            second=module.merge(root,PATCHES)
            for name,source in second.items():
                self.assertEqual(source,result[name]+'\n# unrelated local customization\n')

    def test_unknown_section_rejects_before_writing_any_file(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'app').mkdir()
            path=root/'app/admin_ops.py';path.write_text('def list_redeem_codes(): return []\n')
            with self.assertRaisesRegex(ValueError,'Source différente'):
                module.merge(root,PATCHES)
            self.assertEqual(path.read_text(),'def list_redeem_codes(): return []\n')

    def test_unapproved_path_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError,'non autorisé'):
                module.merge(Path(folder),[dict(file='../.env',old=['x'],new='y')])

    def test_only_requested_live_files_are_targeted(self):
        self.assertEqual(set(p['file'] for p in PATCHES),module.FILES)
        self.assertNotIn('db.py',module.FILES)
        self.assertNotIn('saspay.py',module.FILES)
        self.assertNotIn('vpn_profiles.py',module.FILES)


class RollbackTests(unittest.TestCase):
    def test_failed_health_restores_only_changed_sources(self):
        import hashlib
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)/'backend';(root/'app').mkdir(parents=True)
            for name in ('.env','docker-compose.yml','Dockerfile'): (root/name).write_text('preserve')
            before={}
            for name in module.FILES:
                source=(ROOT.parent/'backend/app'/name).read_text()
                for item in reversed(PATCHES):
                    if item['file']==name: source=source.replace(item['new'],item['old'][0],1)
                before[name]=source;(root/'app'/name).write_text(source)
            (root/'app/unrelated.py').write_text('VALUE = 123\n')
            (root/'data').mkdir();(root/'data/database.sqlite').write_bytes(b'customer data untouched')
            hashes={n:hashlib.sha256(s.encode()).hexdigest() for n,s in before.items()}
            with patch.object(module,'run',side_effect=[json.dumps(hashes),'']) as run, patch.object(module,'restart',side_effect=RuntimeError('unhealthy')):
                with self.assertRaisesRegex(RuntimeError,'unhealthy'):
                    module.install(ROOT/'lot53_backend_patch.json',root=root)
                self.assertEqual(run.call_count,2)
            for name,source in before.items(): self.assertEqual((root/'app'/name).read_text(),source)
            self.assertEqual((root/'app/unrelated.py').read_text(),'VALUE = 123\n')
            self.assertEqual((root/'data/database.sqlite').read_bytes(),b'customer data untouched')
            self.assertEqual((root/'.env').read_text(),'preserve')

if __name__=='__main__':unittest.main()
