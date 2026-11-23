import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import lot43_publish_video as video


class VideoRouteTest(unittest.TestCase):
    def test_exact_route_preserves_original(self):
        result = video.updated_config(video.BASELINE)
        self.assertEqual(result.count(video.LOCATION), 1)
        self.assertEqual(result.replace(video.LOCATION, ''), video.BASELINE)
        self.assertEqual(video.updated_config(result), result)

    def test_drift_refused(self):
        for old in ('8085', 'api.rhaffservice.com', 'proxy_http_version 1.1'):
            with self.subTest(old=old), self.assertRaises(ValueError):
                video.updated_config(video.BASELINE.replace(old, 'changed'))

    def test_reload_failure_restores_original(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            media = directory / 'video.mp4'
            media.write_bytes(b'test media')
            conf = directory / 'nginx.conf'
            conf.write_text(video.BASELINE)
            backup = directory / 'backup'
            backup.mkdir()
            lock = (directory / 'lock').open('w')
            with patch.object(video, 'CONF', conf), patch.object(video, 'TARGET', directory/'static'/video.NAME), patch.object(video, 'SHA', video.digest(media)), patch.object(video.os, 'geteuid', return_value=0), patch.object(video.os, 'chown'), patch.object(video.tempfile, 'mkdtemp', return_value=str(backup)), patch.object(video, 'open', side_effect=lambda p, m: lock if str(p).startswith('/run/lock/') else Path(p).open(m)), patch.object(video, 'run', side_effect=[None, None, RuntimeError('reload failed'), None, None]) as run:
                with self.assertRaises(RuntimeError):
                    video.publish(media)
                self.assertEqual(conf.read_text(), video.BASELINE)
                self.assertEqual((backup / conf.name).read_text(), video.BASELINE)
                self.assertEqual(run.call_args.args[0], ['systemctl', 'reload', 'nginx'])

    def test_existing_modified_route_refused(self):
        config = video.updated_config(video.BASELINE).replace('default_type video/mp4;', 'default_type text/html;')
        with self.assertRaises(ValueError):
            video.updated_config(config)

    def test_blank_lines_preserved(self):
        config = '\n' + video.BASELINE + '\n'
        self.assertEqual(video.updated_config(config).replace(video.LOCATION, ''), config)

    def test_nonroot_refused_before_commands(self):
        with patch.object(video.os, 'geteuid', return_value=1), patch.object(video, 'run') as run:
            with self.assertRaises(PermissionError):
                video.publish(Path('missing'))
            run.assert_not_called()

    def test_wrong_digest_refused_before_commands(self):
        with patch.object(video.os, 'geteuid', return_value=0), patch.object(video, 'digest', return_value='incorrect'), patch.object(video, 'run') as run:
            with self.assertRaises(ValueError):
                video.publish(Path('fixture'))
            run.assert_not_called()

    def test_partial_response_requires_expected_media(self):
        with tempfile.TemporaryDirectory() as root, patch.object(video, 'run'):
            directory = Path(root)
            media = directory / video.NAME
            media.write_bytes(bytes(range(64)))
            body = directory / 'range.bin'
            body.write_bytes(bytes(range(32)))
            header = directory / 'headers.txt'
            for status, mime in [('200 OK', 'video/mp4'), ('206 Partial Content', 'text/html')]:
                header.write_text(f'HTTP/1.1 {status}\nContent-Type: {mime}\n')
                with self.assertRaises(RuntimeError):
                    video.verify_local(media, directory)
            header.write_text('HTTP/1.1 206 Partial Content\nContent-Type: video/mp4\n')
            video.verify_local(media, directory)
            body.write_bytes(b'wrong')
            with self.assertRaises(RuntimeError):
                video.verify_local(media, directory)
