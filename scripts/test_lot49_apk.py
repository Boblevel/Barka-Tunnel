import fcntl
from pathlib import Path
import tempfile
import unittest
import zipfile

import lot49_install_apk as deploy


class ApkPublicationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.old = self.root / 'BarkaTunnel.apk'
        self.new = self.root / 'incoming.apk'
        for path, content in [(self.old, b'old'), (self.new, b'new')]:
            with zipfile.ZipFile(path, 'w') as z:
                z.writestr('AndroidManifest.xml', content)
        self.old.chmod(0o644)
        self.old_bytes = self.old.read_bytes()
        self.sha = deploy.digest(self.new)
        self.calls = []
        self.backups = self.root / 'backups'
        self.lock = self.root / 'lock'

    def verify(self, expected):
        self.calls.append(expected)
        self.assertEqual(deploy.digest(self.old), expected)

    def publish(self, **kwargs):
        return deploy.publish(self.new, kwargs.pop('expected', self.sha),
                              target=self.old, backups=self.backups, lock=self.lock,
                              verify=kwargs.pop('verify', self.verify), **kwargs)

    def test_replaces_only_apk_and_keeps_verified_backup(self):
        unrelated = self.root / 'database.db'
        unrelated.write_bytes(b'clients and quotas unchanged')
        result = self.publish()
        self.assertEqual(deploy.digest(self.old), self.sha)
        self.assertEqual((Path(result['backup']) / 'BarkaTunnel.apk').read_bytes(), self.old_bytes)
        self.assertEqual(self.old.stat().st_mode & 0o777, 0o644)
        self.assertEqual(unrelated.read_bytes(), b'clients and quotas unchanged')
        self.assertEqual(len(self.calls), 2)

    def test_repeat_is_idempotent(self):
        self.publish()
        backups = list(self.backups.iterdir())
        self.assertTrue(self.publish()['already_published'])
        self.assertEqual(list(self.backups.iterdir()), backups)

    def test_wrong_checksum_preserves_existing_apk(self):
        with self.assertRaises(ValueError):
            self.publish(expected='0' * 64)
        self.assertEqual(self.old.read_bytes(), self.old_bytes)
        self.assertFalse(self.backups.exists())
        self.assertFalse(self.calls)

    def test_wrong_live_path_is_rejected_before_any_replacement(self):
        def reject(_):
            raise ValueError('Existing HTTPS response is not this file')
        with self.assertRaises(ValueError):
            self.publish(verify=reject)
        self.assertEqual(self.old.read_bytes(), self.old_bytes)
        self.assertFalse(self.backups.exists())

    def test_failed_new_http_verification_restores_old_apk(self):
        def reject_new(expected):
            self.verify(expected)
            if expected == self.sha:
                raise ValueError('HTTPS verification failed')
        with self.assertRaises(ValueError):
            self.publish(verify=reject_new)
        self.assertEqual(self.old.read_bytes(), self.old_bytes)
        self.assertEqual(len(list(self.backups.iterdir())), 1)
        self.assertEqual(list(self.root.glob('.BarkaTunnel-lot49-*')), [])

    def test_existing_deployment_lock_prevents_replacement(self):
        with self.lock.open('w') as guard:
            fcntl.flock(guard, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaises(BlockingIOError):
                self.publish()
        self.assertEqual(self.old.read_bytes(), self.old_bytes)

    def test_symlink_target_is_rejected(self):
        real = self.root / 'other.apk'
        self.old.rename(real)
        self.old.symlink_to(real)
        with self.assertRaises(ValueError):
            self.publish()
        self.assertEqual(real.read_bytes(), self.old_bytes)

    def test_non_apk_archive_is_rejected(self):
        with zipfile.ZipFile(self.new, 'w') as z:
            z.writestr('error.html', 'not an apk')
        with self.assertRaises(ValueError):
            self.publish(expected=deploy.digest(self.new))
        self.assertEqual(self.old.read_bytes(), self.old_bytes)


if __name__ == '__main__':
    unittest.main()
