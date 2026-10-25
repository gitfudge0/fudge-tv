#!/usr/bin/env python3
"""Enable an explicitly named known HOME component and request it as default."""
import json
from tv_common import TV, canonical, parser, private_dir, write_private

def main():
    p = parser(__doc__)
    p.add_argument('--component', required=True, help='Original stock package/activity from your own backup')
    p.add_argument('--confirm', action='store_true', help='Required to enable the named component and request default HOME')
    args = p.parse_args()
    component = canonical(args.component)
    tv = TV(args)
    tv.check()
    current = tv.home()
    if not args.confirm:
        print('Read-only check passed. Current HOME: ' + current)
        print('Proposed HOME: ' + component + '. Repeat with --confirm to apply.')
        return
    output = private_dir('home-restore-')
    write_private(output / 'before.json', json.dumps({'model': args.model, 'sdk': args.sdk,
                  'user': args.user, 'serial': args.serial, 'home': current, 'requested': component}, indent=2))
    # Explicit component only: no broad package enable, uninstall, disable, or settings restore.
    write_private(output / 'enable.txt', tv.adb('shell', 'pm', 'enable', '--user', str(args.user), component))
    write_private(output / 'set-home.txt', tv.adb('shell', 'cmd', 'package', 'set-home-activity',
                                               '--user', str(args.user), component))
    actual = tv.home()
    write_private(output / 'resolved-home.txt', actual)
    if actual != component:
        raise RuntimeError('Requested HOME did not win resolution. Private evidence saved; stop and inspect the TV chooser.')
    print('HOME resolution verified. Test the physical Home button and reboot manually. Evidence: ' + str(output))

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        raise SystemExit(str(error))
