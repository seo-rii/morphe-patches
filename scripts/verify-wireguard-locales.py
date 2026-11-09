#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Verify custom WireGuard translations and their compiled Android configurations."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import unicodedata
import xml.etree.ElementTree as ET
import zipfile

LOCALES = {'ko-rKR': 'ko', 'ja-rJP': 'ja', 'zh-rCN': 'zh-rCN', 'zh-rTW': 'zh-rTW',
           'es-rES': 'es', 'fr-rFR': 'fr', 'de-rDE': 'de', 'pt-rBR': 'pt-rBR'}
PREFIX = 'morphe_wireguard_'
GROUP = 'shared-youtube/strings.xml'


def resource_strings(contents):
    items = ET.fromstring(contents).findall('string')
    names = [x.attrib['name'] for x in items]
    if len(names) != len(set(names)):
        raise ValueError('Duplicate resource names')
    return {x.attrib['name']: ''.join(x.itertext()) for x in items if x.attrib['name'].startswith(PREFIX)}


def normalize(value):
    # New strings are single-line, unformatted text; Android removes these escapes.
    return unicodedata.normalize('NFC', value.replace("\\'", "'").replace('\\"', '"')).strip()


def parsed_resources(text):
    resources = {}
    current = None
    for line in text.splitlines():
        if re.match(r'\s*resource\s+', line):
            match = re.search(r'\bstring/(morphe_wireguard_[a-z0-9_]+)\s*$', line)
            current = match.group(1) if match else None
            if current:
                if current in resources:
                    raise ValueError('Duplicate compiled resource: ' + current)
                resources[current] = {}
            continue
        match = re.match(r'\s*\(([^)]*)\)\s+"(.*)"\s*$', line)
        if current and match:
            locale, value = match.groups()
            if locale in resources[current]:
                raise ValueError('Duplicate compiled locale: ' + current + '/' + locale)
            resources[current][locale] = normalize(value)
    return resources


def verify_tables(tables, expected):
    for locale, strings in expected.items():
        for key, value in strings.items():
            actual = tables.get(key, {}).get(locale)
            if actual != normalize(value):
                raise ValueError('Missing or changed localized resource: ' + key + '/' + (locale or 'default'))
    return {locale or 'default': len(strings) for locale, strings in expected.items()}


def verify(bundle, apks, aapt2):
    with zipfile.ZipFile(bundle) as archive:
        english = resource_strings(archive.read('addresources/values/' + GROUP))
        if len(english) != 60:
            raise ValueError('Expected exactly 60 English WireGuard resources')
        expected = {'': english}
        for source, target in LOCALES.items():
            translated = resource_strings(archive.read('addresources/values-' + source + '/' + GROUP))
            if translated.keys() != english.keys() or not all(v.strip() for v in translated.values()):
                raise ValueError('Incomplete WireGuard translation: ' + source)
            expected[target] = translated
    checks = []
    for apk in apks:
        process = subprocess.run([str(aapt2), 'dump', 'resources', str(apk)], check=True,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        counts = verify_tables(parsed_resources(process.stdout.decode('utf-8', errors='replace')), expected)
        checks.append({'apk': str(apk), 'apk_sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
                       'verified_resource_counts': counts, 'status': 'PASS'})
    return {'status': 'PASS', 'bundle_sha256': hashlib.sha256(bundle.read_bytes()).hexdigest(),
            'locales': LOCALES, 'strings_per_locale': len(english), 'apks': checks,
            'scope': 'Compiled resources and value equality; Android rendering NOT RUN'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--apk', type=Path, action='append', default=[])
    parser.add_argument('--aapt2', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.apk and not args.aapt2:
        parser.error('--aapt2 is required with --apk')
    report = verify(args.bundle, args.apk, args.aapt2)
    text = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.write_text(text)
    print(text, end='')


if __name__ == '__main__':
    main()
