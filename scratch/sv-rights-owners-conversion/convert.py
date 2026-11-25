#!/usr/bin/env -S uv run --script
# /// script
# dependencies = ["pyyaml"]
# ///

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

"""Convert approved-sv-id-values.yaml with `svRightsOwnerName` (configs#413) into the argument of
`DsoRules_MigrateToOnLedgerSvRightOwners`, and check that every SV node mints the same coupons as today.

Usage: ./convert.py approved-sv-id-values.DevNet.yaml > sv-right-owners.DevNet.json
"""
import collections, json, re, sys
from decimal import Decimal, ROUND_CEILING
import yaml

text = open(sys.argv[1]).read()
svs = yaml.safe_load(text)['approvedSvIdentities']
num = lambda x: int(str(x).replace('_', ''))
party = lambda name: f'{name}::1220' + '0' * 64  # the yaml has no SV and DSO party ids
DSO = party('DSO')

# PyYAML drops comments, so read the `# <label> # <role>` comment of every beneficiary line, in file order.
comments = iter(re.findall(r'- beneficiary: "[^"]*"\s*(?:#(.*))?$', text, re.M))
for sv in svs:
    sv['weight'], sv['party'], sv['entries'] = num(sv['rewardWeightBps']), party(sv['name']), []
    for b in sv.get('extraBeneficiaries') or []:
        label, _, role = next(comments).partition('#')
        sv['entries'].append(dict(owner=b['svRightsOwnerName'], party=b['beneficiary'], weight=num(b['weight']),
                                  label=label.strip(), role=role.strip()))

def is_escrow(e):
    return e['role'].startswith('escrow party_id') or 'unassigned weight' in e['role']

def ratio(weight, active_weight):
    """Rounded up, so that the mint floors it back to `weight`."""
    return (Decimal(weight) / Decimal(active_weight)).quantize(Decimal('1e-10'), ROUND_CEILING)

def convert(owner):
    entries_at = lambda sv: [e for e in sv['entries'] if e['owner'] == owner]
    nodes = [sv for sv in svs if sv['svRightsOwnerName'] == owner or entries_at(sv)]
    own_nodes = [sv for sv in nodes if sv['svRightsOwnerName'] == owner]
    actives = [e['party'] for sv in nodes for e in entries_at(sv) if not is_escrow(e)]
    # The SV party of its own SV node, else its last active party, else `dso` until a vote sets one.
    owner_party = own_nodes[0]['party'] if own_nodes else actives[-1] if actives else DSO

    def assignment(sv):
        entries = entries_at(sv)
        if sv['svRightsOwnerName'] == owner:
            total, default = sv['weight'] - sum(e['weight'] for e in sv['entries'] if e['owner'] != owner), sv['party']
        else:
            total, default = sum(e['weight'] for e in entries), owner_party
        escrow = [e for e in entries if is_escrow(e)]
        active = [e for e in entries if not is_escrow(e)]
        active_weight = total - sum(e['weight'] for e in escrow)
        weights = collections.Counter()
        for e in active:
            if e['party'] != default:
                weights[e['party']] += e['weight']
        return {'svNodeName': sv['name'], 'rewardWeight': total,
                'escrowBeneficiaries': [{'beneficiary': e['party'], 'rewardWeight': e['weight']} for e in escrow],
                'beneficiaries': [[p, {'rewardRatio': str(ratio(w, active_weight))}] for p, w in sorted(weights.items())],
                'defaultBeneficiary': default}

    return {'rightOwnerParty': owner_party,
            'description': '; '.join(dict.fromkeys(e['label'] for sv in nodes for e in entries_at(sv) if e['label'])),
            # An owner has at most one assignment per SV node, so the SV node's name is a unique id at migration.
            'svNodeAssignments': {sv['name']: assignment(sv) for sv in nodes}}

owners = sorted({o for sv in svs for o in [sv['svRightsOwnerName']] + [e['owner'] for e in sv['entries']]})
result = {o: convert(o) for o in owners}

# --- Checks.

def check_valid(name, info):
    """`svRightOwnerInfoChecks`."""
    assignments = list(info['svNodeAssignments'].values())
    nodes = [a['svNodeName'] for a in assignments]
    assert nodes and len(nodes) == len(set(nodes)), name
    for a in assignments:
        ratios = [Decimal(i['rewardRatio']) for _, i in a['beneficiaries']]
        escrow = a['escrowBeneficiaries']
        assert a['rewardWeight'] >= 0 and all(r > 0 for r in ratios) and sum(ratios) <= 1 and len(ratios) <= 20, name
        assert all(e['rewardWeight'] > 0 for e in escrow) and sum(e['rewardWeight'] for e in escrow) <= a['rewardWeight'], name
        assert len(escrow) <= 20, name

def coupons_today(sv):
    """`SvUtil.weightDistributionForSv`: beneficiaries in order, the rest to the SV party."""
    rest, coupons = sv['weight'], collections.Counter()
    for e in sv['entries']:
        w = min(e['weight'], rest)
        coupons[e['party']] += w
        rest -= w
    coupons[sv['party']] += rest
    return +coupons

def coupons_new(a):
    """`svRewardCouponWeights`: the escrow weights, the beneficiaries' floored ratios of the rest, the remainder to the
    default beneficiary."""
    coupons = collections.Counter()
    for e in a['escrowBeneficiaries']:
        coupons[e['beneficiary']] += e['rewardWeight']
    active_weight = a['rewardWeight'] - sum(e['rewardWeight'] for e in a['escrowBeneficiaries'])
    rest = active_weight
    for p, info in a['beneficiaries']:
        w = int(active_weight * Decimal(info['rewardRatio']))
        coupons[p] += w
        rest -= w
    coupons[a['defaultBeneficiary']] += rest
    return +coupons

for name, info in result.items():
    check_valid(name, info)
for sv in svs:
    here = [a for info in result.values() for a in info['svNodeAssignments'].values() if a['svNodeName'] == sv['name']]
    assert sum(a['rewardWeight'] for a in here) == sv['weight'], f"Total weight changed at {sv['name']}"
    got = sum((coupons_new(a) for a in here), collections.Counter())
    assert got == coupons_today(sv), f"Coupons changed at {sv['name']}: {got} != {coupons_today(sv)}"
print(f"OK: {len(result)} owners, {sum(len(i['svNodeAssignments']) for i in result.values())} assignments, {len(svs)} SV nodes;"
      " total weight and coupons per SV node and party equal today", file=sys.stderr)

json.dump(result, sys.stdout, indent=2)
print()
