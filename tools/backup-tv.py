#!/usr/bin/env python3
"""Read-only snapshot. Does not back up app data, DRM keys, or streaming logins."""
import json
from tv_common import TV, parser, private_dir, write_private

def main():
    args = parser(__doc__).parse_args()
    tv = TV(args)
    tv.check()
    output = private_dir('tv-backup-')
    commands = {
        'properties.txt': ['getprop'],
        'packages-all.txt': ['pm', 'list', 'packages', '-f', '--user', str(args.user)],
        'packages-disabled.txt': ['pm', 'list', 'packages', '-d', '--user', str(args.user)],
        'packages-system.txt': ['pm', 'list', 'packages', '-s', '--user', str(args.user)],
        'packages-user.txt': ['pm', 'list', 'packages', '-3', '--user', str(args.user)],
    }
    for namespace in ('global', 'secure', 'system'):
        commands['settings-' + namespace + '.txt'] = ['settings', '--user', str(args.user), 'list', namespace]
    # Write identity first so partial backups remain attributable to the right TV.
    write_private(output / 'identity.json', json.dumps({
        'model': args.model, 'sdk': args.sdk, 'user': args.user, 'serial': args.serial,
        'original_home': tv.home()}, indent=2))
    for filename, command in commands.items():
        write_private(output / filename, tv.adb('shell', *command))
    tv.check()
    write_private(output / 'COMPLETE', 'Read-only inventory finished; no device settings changed.')
    print('Private backup saved: ' + str(output))

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        raise SystemExit(str(error))
