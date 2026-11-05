// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { http, HttpHandler, HttpResponse } from 'msw';

// Since msw 3, the CORS preflight requests that jsdom sends before cross-origin API calls are
// intercepted as requests of their own. Answer all of them so that the actual request follows.
export const corsPreflightHandler: HttpHandler = http.options(
  '*',
  () =>
    new HttpResponse(null, {
      status: 204,
      headers: {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': '*',
        'Access-Control-Allow-Headers': '*',
      },
    })
);
