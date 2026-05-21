// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { http, HttpHandler, HttpResponse } from 'msw';
import { Mock, vi } from 'vitest';

export const requestMocks: {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  createTransferOffer: Mock<(request: any) => Promise<any>>;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  createTransferViaTokenStandard: Mock<(request: any) => Promise<any>>;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  createTransferPreapproval: Mock<() => Promise<any>>;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  transferPreapprovalSend: Mock<(request: any) => Promise<any>>;
} = {
  createTransferOffer: vi.fn(),
  createTransferViaTokenStandard: vi.fn(),
  createTransferPreapproval: vi.fn(),
  transferPreapprovalSend: vi.fn(),
};

// We need to separate the api endpoints that use vitest to avoid compatibility errors with msw.
export const buildTransferOfferMock = (walletUrl: string): HttpHandler[] => [
  http.post(`${walletUrl}/v0/wallet/transfer-offers`, async ({ request }) => {
    await requestMocks.createTransferOffer(await request.json());
    return HttpResponse.json({});
  }),

  http.post(`${walletUrl}/v0/wallet/token-standard/transfers`, async ({ request }) => {
    await requestMocks.createTransferViaTokenStandard(await request.json());
    return HttpResponse.json({});
  }),

  http.post(`${walletUrl}/v0/wallet/transfer-preapproval`, async () => {
    await requestMocks.createTransferPreapproval();
    return HttpResponse.json({});
  }),

  http.post(`${walletUrl}/v0/wallet/transfer-preapproval/send`, async ({ request }) => {
    await requestMocks.transferPreapprovalSend(await request.json());
    return HttpResponse.json({});
  }),
];
