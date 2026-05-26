// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { fireAuthExpired } from './authExpiry';

interface SetHeaderParam {
  setHeaderParam(key: string, value: string): void;
}
interface WithHttpStatus {
  httpStatusCode: number;
}
export class BaseApiMiddleware<
  RequestContext extends SetHeaderParam,
  ResponseContext extends WithHttpStatus,
> {
  readonly token: string | undefined;

  async pre(context: RequestContext): Promise<RequestContext> {
    if (!this.token) {
      throw new Error('Request issued before access token was set');
    }
    context.setHeaderParam('Authorization', `Bearer ${this.token}`);
    return context;
  }
  post(context: ResponseContext): Promise<ResponseContext> {
    if (context.httpStatusCode === 401) {
      fireAuthExpired();
    }
    return Promise.resolve(context);
  }
  constructor(accessToken: string | undefined) {
    this.token = accessToken;
  }
}
