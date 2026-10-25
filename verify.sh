#!/bin/sh
set -eu
cd "$(dirname "$0")"
./format.sh --check
python3 -m unittest discover -s tools -p 'test_*.py'
./build.sh
