"""Release publication must remain atomic and repeatable; no network is used."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('kashi-release.sh').resolve()
MARKER = 'app-id: com.dirk.kalshiodds.kashi'
MOCK = r'''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
p=Path(os.environ['MOCK_RELEASE']); a=sys.argv[1:]
with open(os.environ['MOCK_CALLS'],'a') as out: out.write(json.dumps(a)+'\n')
if a[:2]==['release','create']:
    p.write_text(json.dumps({'draft':True,'body':a[a.index('--notes')+1],'target':a[a.index('--target')+1]}))
elif a[:2]==['release','view']:
    if not p.exists(): sys.exit(1)
    d=json.loads(p.read_text()); field=a[a.index('--json')+1]
    print({'isDraft':str(d['draft']).lower(),'body':('wrong marker' if os.environ.get('BAD_MARKER') else d['body']),
        'isPrerelease':'false','targetCommitish':d['target'],'assets':'DipHunter-debug.apk'}[field])
elif a[:2]==['release','edit']:
    d=json.loads(p.read_text());d['draft']=False;p.write_text(json.dumps(d))
else: sys.exit(2)
'''

class ReleaseTest(unittest.TestCase):
    def run_release(self, bad=False, twice=False):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            gh = root/'gh'; gh.write_text(MOCK); gh.chmod(0o755)
            apk = root/'DipHunter-debug.apk'; apk.write_bytes(b'tested-apk')
            calls = root/'calls'; state = root/'release'
            env = dict(os.environ, PATH=f'{root}:{os.environ["PATH"]}', MOCK_RELEASE=str(state), MOCK_CALLS=str(calls))
            if bad: env['BAD_MARKER']='1'
            args = ['bash', str(SCRIPT), 'v0.3.76-debug', str(apk), 'abc123', 'Kashi 0.3.76', 'Test notes']
            result = subprocess.run(args, env=env, capture_output=True, text=True)
            if twice:
                self.assertEqual(0, result.returncode, result.stderr)
                result = subprocess.run(args, env=env, capture_output=True, text=True)
            return result, [json.loads(line) for line in calls.read_text().splitlines()], json.loads(state.read_text())

    def test_upload_as_draft_before_publishing(self):
        result, calls, release = self.run_release()
        self.assertEqual(0, result.returncode, result.stderr)
        create = next(c for c in calls if c[:2] == ['release','create'])
        self.assertIn('--draft', create)
        self.assertEqual(MARKER, release['body'].splitlines()[0])
        self.assertFalse(release['draft'])
        self.assertLess(calls.index(create), next(i for i,c in enumerate(calls) if c[:2] == ['release','edit']))

    def test_bad_identity_never_becomes_visible(self):
        result, calls, release = self.run_release(bad=True)
        self.assertNotEqual(0, result.returncode)
        self.assertTrue(release['draft'])
        self.assertFalse(any(c[:2] == ['release','edit'] for c in calls))

    def test_rerun_does_not_replace_existing_apk(self):
        result, calls, release = self.run_release(twice=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, sum(c[:2] == ['release','create'] for c in calls))
        self.assertFalse(release['draft'])

if __name__ == '__main__':
    unittest.main()
