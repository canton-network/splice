// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

const EVENT = 'splice:auth-expired';

export const fireAuthExpired = (): void => {
  window.dispatchEvent(new Event(EVENT));
};

export const onAuthExpired = (handler: () => void): (() => void) => {
  window.addEventListener(EVENT, handler);
  return () => window.removeEventListener(EVENT, handler);
};
