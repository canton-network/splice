// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { http, HttpHandler, HttpResponse } from 'msw';
import { LookupTransferPreapprovalByPartyResponse } from '@lfdecentralizedtrust/scan-openapi';
import {
  GetAmuletRulesProxyResponse,
  GetOpenAndIssuingMiningRoundsProxyResponse,
  LookupEntryByPartyResponse,
} from '@lfdecentralizedtrust/scan-proxy-openapi';
import { ListTransferOffersResponse } from '@lfdecentralizedtrust/wallet-external-openapi';
import {
  GetBalanceResponse,
  ListMintingDelegationsResponse,
  ListMintingDelegationProposalsResponse,
  ListTransactionsResponse,
  UserStatusResponse,
} from '@lfdecentralizedtrust/wallet-openapi';
import {
  MintingDelegation,
  MintingDelegationProposal,
} from '@daml.js/splice-wallet/lib/Splice/Wallet/MintingDelegation/module';

import {
  aliceEntry,
  alicePartyId,
  amuletRules,
  bobPartyId,
  bobTransferPreapproval,
  miningRounds,
  nameServiceEntries,
} from '../constants';
import {
  mockMintingDelegations,
  mockMintingDelegationProposals,
  mockDelegationHostedStatus,
  mockProposalHostedStatus,
} from '../delegation-constants';
import { mkContract } from '../contract';

export const buildWalletMock = (walletUrl: string): HttpHandler[] => [
  http.get(`${walletUrl}/v0/wallet/user-status`, () => {
    return HttpResponse.json<UserStatusResponse>({
      party_id: alicePartyId,
      user_onboarded: true,
      user_wallet_installed: true,
      has_featured_app_right: false,
    });
  }),
  http.get(
    `${walletUrl}/v0/scan-proxy/featured-apps/alice__wallet__user%3A%3A12201d5aa725ec9491490fd860e86f849358604f6fd387053771cafb90384a94c3e2`,
    () => {
      return HttpResponse.json({ featured_app_right: null });
    }
  ),

  http.get(`${walletUrl}/v0/scan-proxy/open-and-issuing-mining-rounds`, () => {
    return HttpResponse.json<GetOpenAndIssuingMiningRoundsProxyResponse>(miningRounds);
  }),

  http.get(`${walletUrl}/v0/scan-proxy/ans-entries`, () => {
    return HttpResponse.json({
      entries: nameServiceEntries,
    });
  }),

  http.get(`${walletUrl}/v0/scan-proxy/ans-entries/by-party/:party`, ({ params }) => {
    const { party } = params;
    if (
      party ===
      'alice__wallet__user::12201d5aa725ec9491490fd860e86f849358604f6fd387053771cafb90384a94c3e2'
    ) {
      return HttpResponse.json<LookupEntryByPartyResponse>({
        entry: aliceEntry,
      });
    } else {
      return HttpResponse.json({}, { status: 404 });
    }
  }),

  http.get(`${walletUrl}/v0/scan-proxy/ans-entries/by-name/:name`, () => {
    return HttpResponse.json({}, { status: 404 });
  }),

  http.get(`${walletUrl}/v0/sample`, () => {
    return HttpResponse.json({});
  }),

  http.get(`${walletUrl}/v0/scan-proxy/transfer-preapprovals/by-party/:party`, ({ params }) => {
    const { party } = params;
    if (party === 'bob::preapproval') {
      return HttpResponse.json<LookupTransferPreapprovalByPartyResponse>({
        transfer_preapproval: bobTransferPreapproval,
      });
    }
    return HttpResponse.json({}, { status: 404 });
  }),

  http.get(`${walletUrl}/v0/scan-proxy/transfer-preapprovals/by-party`, () => {
    // The by-party request above seems to not match for an empty party string
    return HttpResponse.json({}, { status: 404 });
  }),

  http.get(`${walletUrl}/v0/wallet/balance`, () => {
    return HttpResponse.json<GetBalanceResponse>({
      round: 18,
      effective_unlocked_qty: '778.9353119400',
      effective_locked_qty: '0.0000000000',
      total_holding_fees: '0.0646880600',
    });
  }),

  http.post(`${walletUrl}/v0/wallet/transactions`, () => {
    return HttpResponse.json<ListTransactionsResponse>({
      items: [
        {
          transaction_type: 'balance_change',
          transaction_subtype: {
            template_id:
              '#splice-amulet:Splice.AmuletTransferInstruction:AmuletTransferInstruction',
            choice: 'TransferInstruction_Withdraw',
          },
          event_id: '#u4:0',
          date: new Date('2025-05-21T12:14:12Z'),
          receivers: [{ party: alicePartyId, amount: '0.0' }],
          transfer_instruction_cid:
            '009a97ffdf201d323d12a428187d9118d985678c37c6c1081f848269943f0da8bbca1112207e4b3e9a65879126e8b8103714f0144e1e0218fa98fb5231c63be74a0bb40402',

          // the openapi generator seems to generate a garbage type so there are a bunch of non-sense fields we need to fill in
          sender: { party: '', amount: '' },
          holding_fees: '',
          app_rewards_used: '',
          validator_rewards_used: '',
          sv_rewards_used: '',
          development_fund_coupons_used: '',
          details: '',
        },
        // incoming
        {
          transaction_type: 'transfer',
          transaction_subtype: {
            template_id: '#splice-amulet:Splice.ExternalPartyAmuletRules:ExternalPartyAmuletRules',
            choice: 'TransferFactory_Transfer',
          },
          event_id: '#u3:0',
          date: new Date('2025-05-21T12:14:12Z'),
          sender: { party: bobPartyId, amount: '-42.0' },
          receivers: [{ party: alicePartyId, amount: '0.0' }],
          holding_fees: '0.0',
          app_rewards_used: '0.0',
          validator_rewards_used: '0.0',
          sv_rewards_used: '0.0',
          details: '',
          transfer_instruction_cid:
            '009a97ffdf201d323d12a428187d9118d985678c37c6c1081f848269943f0da8bbca1112207e4b3e9a65879126e8b8103714f0144e1e0218fa98fb5231c63be74a0bb40402',
          transfer_instruction_receiver: alicePartyId,
          transfer_instruction_amount: '10.0',
          description: 'test transfer',
          development_fund_coupons_used: '0.0',
        },
        {
          transaction_type: 'transfer',
          transaction_subtype: {
            template_id:
              '#splice-amulet:Splice.AmuletTransferInstruction:AmuletTransferInstruction',
            choice: 'TransferInstruction_Accept',
          },
          event_id: '#u2:0',
          date: new Date('2025-05-21T12:12:12Z'),
          sender: { party: alicePartyId, amount: '23.0' },
          receivers: [],
          holding_fees: '0.0',
          app_rewards_used: '0.0',
          validator_rewards_used: '0.0',
          sv_rewards_used: '0.0',
          development_fund_coupons_used: '0.0',
          details: '',
          transfer_instruction_cid:
            '009a97ffdf201d323d12a428187d9118d985678c37c6c1081f848269943f0da8bbca1112207e4b3e9a65879126e8b8103714f0144e1e0218fa98fb5231c63be74a0bb40401',
        },
        // outgoing
        {
          transaction_type: 'transfer',
          transaction_subtype: {
            template_id: '#splice-amulet:Splice.ExternalPartyAmuletRules:ExternalPartyAmuletRules',
            choice: 'TransferFactory_Transfer',
          },
          event_id: '#u1:0',
          date: new Date('2025-05-21T12:10:12Z'),
          sender: { party: alicePartyId, amount: '-42.0' },
          receivers: [],
          holding_fees: '0.0',
          app_rewards_used: '0.0',
          validator_rewards_used: '0.0',
          sv_rewards_used: '0.0',
          development_fund_coupons_used: '0.0',
          details: '',
          transfer_instruction_cid:
            '009a97ffdf201d323d12a428187d9118d985678c37c6c1081f848269943f0da8bbca1112207e4b3e9a65879126e8b8103714f0144e1e0218fa98fb5231c63be74a0bb40401',
          transfer_instruction_receiver: bobPartyId,
          transfer_instruction_amount: '10.0',
          description: 'test transfer',
        },
      ],
    });
  }),

  http.get(`${walletUrl}/v0/wallet/transfer-offers`, () => {
    return HttpResponse.json<ListTransferOffersResponse>({ offers: [] });
  }),

  http.get(`${walletUrl}/v0/scan-proxy/amulet-rules`, () => {
    return HttpResponse.json<GetAmuletRulesProxyResponse>(amuletRules);
  }),
  http.get(`${walletUrl}/v0/scan-proxy/unclaimed-development-fund-coupons`, () => {
    return HttpResponse.json({
      unclaimed_development_fund_coupons: [],
    });
  }),
  http.get(`${walletUrl}/v0/wallet/development-fund-coupons`, () => {
    return HttpResponse.json({
      active_development_fund_coupons: [],
    });
  }),
  http.get(`${walletUrl}/v0/wallet/development-fund-coupons/history`, () => {
    return HttpResponse.json({
      development_fund_coupon_history: [],
    });
  }),

  http.get(`${walletUrl}/v0/scan-proxy/featured-apps/:party`, () => {
    return HttpResponse.json({}, { status: 404 });
  }),

  http.get(`${walletUrl}/v0/wallet/minting-delegations`, () => {
    return HttpResponse.json<ListMintingDelegationsResponse>({
      delegations: mockMintingDelegations.map((delegation, index) => ({
        contract: mkContract(MintingDelegation, delegation),
        beneficiary_hosted: mockDelegationHostedStatus[index],
      })),
    });
  }),

  http.get(`${walletUrl}/v0/wallet/minting-delegation-proposals`, () => {
    return HttpResponse.json<ListMintingDelegationProposalsResponse>({
      proposals: mockMintingDelegationProposals.map((proposal, index) => ({
        contract: mkContract(MintingDelegationProposal, proposal),
        beneficiary_hosted: mockProposalHostedStatus[index],
      })),
    });
  }),

  http.post(`${walletUrl}/v0/wallet/minting-delegations/:cid/reject`, () => {
    return new HttpResponse(null, { status: 200 });
  }),

  http.post(`${walletUrl}/v0/wallet/minting-delegation-proposals/:cid/accept`, () => {
    return new HttpResponse(null, { status: 200 });
  }),

  http.post(`${walletUrl}/v0/wallet/minting-delegation-proposals/:cid/reject`, () => {
    return new HttpResponse(null, { status: 200 });
  }),
];
