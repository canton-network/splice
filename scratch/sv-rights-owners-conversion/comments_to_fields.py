#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Line-based rewrite: move `# ...` comments on beneficiary lines into fields."""
import re, sys, json
q = json.dumps  # YAML accepts JSON double-quoted strings
out = []
for line in open(sys.argv[1]).read().splitlines():
    m = re.match(r'^(\s*)- beneficiary: ("[^"]*")\s*#\s*(.*)$', line)
    if not m:
        out.append(line); continue
    ind, party, comment = m.groups()
    out.append(f'{ind}- beneficiary: {party}')
    f = ' ' * (len(ind) + 2)
    head, _, tail = comment.partition('#')
    head, tail = head.strip(), tail.strip()
    urls = re.findall(r'https?://\S+', head)
    head = re.sub(r'https?://\S+', '', head).strip()
    cips = re.findall(r'CIP-\d{4}', head)
    label = re.sub(r'\s+', ' ', re.sub(r'CIP-\d{4}', '', head)).strip(' ,')
    if tail.startswith('escrow party_id'): role = 'escrow'
    elif tail.startswith('active party_id'): role = 'active'
    elif 'unassigned weight' in tail: role = 'unassigned'
    elif tail: sys.exit(f'unparsed role: {tail}')
    else: role = None
    out.append(f'{f}label: {q(label)}')
    if cips: out.append(f'{f}cips: [{", ".join(cips)}]')
    if role: out.append(f'{f}role: {role}')
    note = ' '.join(([tail] if tail else []) + urls)
    if note: out.append(f'{f}note: {q(note)}')
print('\n'.join(out))
