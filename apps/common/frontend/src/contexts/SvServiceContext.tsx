// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as openapi from '@canton-network/sv-openapi';
import { useUserState } from '@canton-network/splice-common-frontend';
import {
  BaseApiMiddleware,
  OpenAPILoggingMiddleware,
} from '@canton-network/splice-common-frontend-utils';
import React, { useContext, useMemo } from 'react';
import {
  GetDsoInfoResponse,
  Middleware,
  RequestContext,
  ResponseContext,
  ServerConfiguration,
} from '@canton-network/sv-openapi';

const SvContext = React.createContext<SvClient | undefined>(undefined);

export interface SVProps {
  url: string;
}

export interface SvClient {
  getDsoInfo: () => Promise<GetDsoInfoResponse>;
}

class ApiMiddleware
  extends BaseApiMiddleware<RequestContext, ResponseContext>
  implements Middleware {}

export const SvClientProvider: React.FC<React.PropsWithChildren<SVProps>> = ({ url, children }) => {
  const { userAccessToken } = useUserState();

  const friendlyClient: SvClient | undefined = useMemo(() => {
    const configuration = openapi.createConfiguration({
      baseServer: new ServerConfiguration(url, {}),
      promiseMiddleware: [new ApiMiddleware(userAccessToken), new OpenAPILoggingMiddleware('sv')],
    });
    const svClient = new openapi.SvApi(configuration);

    return {
      getDsoInfo: async (): Promise<GetDsoInfoResponse> => {
        return await svClient.getDsoInfoV1();
      },
    };
  }, [url, userAccessToken]);

  return <SvContext.Provider value={friendlyClient}>{children}</SvContext.Provider>;
};

export const useSvClient: () => SvClient = () => {
  const client = useContext<SvClient | undefined>(SvContext);
  if (!client) {
    throw new Error('SV client not initialized');
  }
  return client;
};
