#!/usr/bin/env -S uv run --script
# /// script
# dependencies = ["pyyaml"]
# ///

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Convert approved-sv-id-values.yaml (after comments_to_fields.py) to SvRightOwner infos.

Escrow shape: OperatorAssignment += escrowedRewardWeight : Int (<= rewardWeight, DSO-governed).
  No coupons are created for the escrowed weight; it is released by a DSO vote once a milestone is reached.
  Coupons: beneficiaries in order from rewardWeight - escrowedRewardWeight, remainder to defaultBeneficiary.
"""
import sys, json, yaml, collections
d = yaml.safe_load(open(sys.argv[1]))
num = lambda x: int(str(x).replace('_', ''))
short = lambda p: p  # keep full party ids
svp = lambda op: f'<{op} SV party>'
ESCROW = {'escrow', 'unassigned'}

owners = collections.OrderedDict()
def owner(name):
    return owners.setdefault(name, {'parts': [], 'assignments': {}, 'self': False})

for sv in d['approvedSvIdentities']:
    op, W, so = sv['name'], num(sv['rewardWeightBps']), sv['svRightsOwnerName']
    groups = collections.OrderedDict()
    for b in sv.get('extraBeneficiaries', []):
        groups.setdefault(b['svRightsOwnerName'], []).append(b)
    groups.setdefault(so, [])
    other = sum(num(b['weight']) for o, bs in groups.items() if o != so for b in bs)
    for o, bs in groups.items():
        rec = owner(o)
        isSelf = o == so
        rec['self'] |= isSelf
        esc = [b for b in bs if b.get('role') in ESCROW]
        act = [b for b in bs if b.get('role') not in ESCROW]
        rec['parts'] += bs
        rec['assignments'][op] = dict(
            rewardWeight=W - other if isSelf else sum(num(b['weight']) for b in bs),
            escrowedRewardWeight=sum(num(b['weight']) for b in esc),
            beneficiaries=[{'beneficiary': short(b['beneficiary']), 'weight': num(b['weight'])} for b in act],
            _isSelf=isSelf)

def description(parts):
    seen, out = set(), []
    for b in parts:
        s = b.get('label', '')
        if b.get('cips'): s += f" ({', '.join(b['cips'])})"
        if 'http' in b.get('note', ''): s += ' ' + ' '.join(w for w in b['note'].split() if w.startswith('http'))
        if s and s not in seen: seen.add(s); out.append(s)
    return '; '.join(out)

result = {}
for name, rec in owners.items():
    actives = {a['beneficiary'] for x in rec['assignments'].values() for a in x['beneficiaries']}
    if rec['self']:
        selfOp = next(op for op, x in rec['assignments'].items() if x['_isSelf'])
        rop = svp(selfOp)
    elif len(actives) == 1:
        rop = next(iter(actives))
    else:
        rop = None  # the owner has not provided a party yet
    assigns = {}
    for op, x in rec['assignments'].items():
        # Remainder goes to defaultBeneficiary: drop trailing entries paying it.
        dflt = svp(op) if x['_isSelf'] else rop
        bs = list(x['beneficiaries'])
        while bs and bs[-1]['beneficiary'] == dflt: bs.pop()
        x = {k: v for k, v in x.items() if not k.startswith('_')} | {'beneficiaries': bs, 'defaultBeneficiary': dflt}
        assert dflt is not None or x['rewardWeight'] - x['escrowedRewardWeight'] - sum(b['weight'] for b in bs) <= 0, (name, op)
        assigns[op] = x
    result[name] = {'rightOwnerParty': rop, 'description': description(rec['parts']), 'operatorAssignments': assigns}

# --- check: coupons per (operator, party) equal today's weightDistributionForSv
def today(sv):
    rem, acc = num(sv['rewardWeightBps']), collections.Counter()
    for b in sv.get('extraBeneficiaries', []):
        if b.get('role') in ESCROW: rem -= num(b['weight']); continue  # escrow: no coupon
        if rem <= 0: break
        w = min(num(b['weight']), rem); acc[short(b['beneficiary'])] += w; rem -= w
    if rem > 0: acc[svp(sv['name'])] += rem
    return acc
def new(a):
    rem, acc = a['rewardWeight'] - a['escrowedRewardWeight'], collections.Counter()
    for b in a['beneficiaries']:
        w = min(b['weight'], rem); acc[b['beneficiary']] += w; rem -= w
    if rem > 0 and a['defaultBeneficiary'] is not None: acc[a['defaultBeneficiary']] += rem
    return acc
for sv in d['approvedSvIdentities']:
    got = sum((new(a) for o in result.values() for op, a in o['operatorAssignments'].items() if op == sv['name']), collections.Counter())
    exp = today(sv)
    # self owners pay their remainder to the SV party: identical by construction of rop
    assert +got == +exp, (sv['name'], got - exp, exp - got)
keys = [(n, op) for n, o in result.items() for op in o['operatorAssignments']]  # SvRewardState keys
assert len(keys) == len(set(keys))
for o in result.values():
    for a in o['operatorAssignments'].values():
        assert 0 <= a['escrowedRewardWeight'] <= a['rewardWeight'] and len(a['beneficiaries']) <= 20
print(f"OK: {len(result)} owners, {len({op for _, op in keys})} operators, {len(keys)} assignments, coupons match today for all operators", file=sys.stderr)

# --- check: every config beneficiary entry stays at its current operator, under its owner
cfg = collections.Counter()
for sv in d['approvedSvIdentities']:
    for b in sv.get('extraBeneficiaries', []):
        party = 'ESCROW' if b.get('role') in ESCROW else short(b['beneficiary'])
        cfg[(sv['name'], b['svRightsOwnerName'], party)] += num(b['weight'])
out = collections.Counter()
for n, o in result.items():
    for op, a in o['operatorAssignments'].items():
        if a['escrowedRewardWeight']: out[(op, n, 'ESCROW')] += a['escrowedRewardWeight']
        for b in a['beneficiaries']:
            out[(op, n, b['beneficiary'])] += b['weight']
        rest = a['rewardWeight'] - a['escrowedRewardWeight'] - sum(b['weight'] for b in a['beneficiaries'])
        if rest > 0 and a['defaultBeneficiary'] != svp(op):  # trimmed trailing entries
            out[(op, n, a['defaultBeneficiary'])] += rest
moved = {k: (cfg[k], out[k]) for k in cfg.keys() | out.keys() if cfg[k] != out[k]}
assert not moved, moved
print(f"OK: all {len(cfg)} (operator, owner, party) entries keep their operator and weight", file=sys.stderr)

assert {op for _, op in keys} == {sv['name'] for sv in d['approvedSvIdentities']}, 'operator set changed'
print("OK: operators unchanged; escrowed weight in total:",
      sum(a['escrowedRewardWeight'] for o in result.values() for a in o['operatorAssignments'].values()), file=sys.stderr)


def dump(result):
    """One line per operator assignment, so that diffs stay readable."""
    lines, items = ['{'], list(result.items())
    for i, (n, o) in enumerate(items):
        lines += [f'  {json.dumps(n)}: {{', f'    "rightOwnerParty": {json.dumps(o["rightOwnerParty"])},',
                  f'    "description": {json.dumps(o["description"])},', '    "operatorAssignments": {']
        oas = list(o['operatorAssignments'].items())
        lines += [f'      {json.dumps(op)}: {json.dumps(a)}' + (',' if j < len(oas) - 1 else '') for j, (op, a) in enumerate(oas)]
        lines += ['    }', '  }' + (',' if i < len(items) - 1 else '')]
    return '\n'.join(lines + ['}'])


print(dump(result))
