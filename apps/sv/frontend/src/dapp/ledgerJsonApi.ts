// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  createConfiguration,
  DefaultApi,
  RequestContext,
  ResponseContext,
  ServerConfiguration,
  wrapHttpLibrary,
} from '@canton-network/canton-json-api-v2-openapi';
import { DappSdkClient } from './dappSdkClient';

const toRequestMethod = (method: string): 'get' | 'post' | 'patch' | 'put' | 'delete' => {
  const lowered = method.toLowerCase();
  if (
    lowered === 'get' ||
    lowered === 'post' ||
    lowered === 'patch' ||
    lowered === 'put' ||
    lowered === 'delete'
  ) {
    return lowered;
  }
  throw new Error(`Unsupported CIP-103 ledgerApi method: ${method}`);
};

/** JSON Ledger API client that issues requests through CIP-103 `ledgerApi`. */
export function createLedgerJsonApi(sdkClient: DappSdkClient): DefaultApi {
  return new DefaultApi(
    createConfiguration({
      baseServer: new ServerConfiguration('http://ledger.invalid', {}),
      httpApi: wrapHttpLibrary({
        async send(request: RequestContext): Promise<ResponseContext> {
          const url = new URL(request.getUrl());
          const rawBody = request.getBody();
          const result = await sdkClient.ledgerApi({
            requestMethod: toRequestMethod(request.getHttpMethod()),
            resource: `${url.pathname}${url.search}`,
            ...(typeof rawBody === 'string' && rawBody.length > 0
              ? { body: JSON.parse(rawBody) as Record<string, unknown> }
              : {}),
          });
          return new ResponseContext(
            200,
            { 'content-type': 'application/json' },
            {
              text: async () => JSON.stringify(result),
              binary: async () => new Blob([JSON.stringify(result)]),
            }
          );
        },
      }),
    })
  );
}
