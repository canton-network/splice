// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import CopyableIdentifier from './CopyableIdentifier';
import type { CopyableIdentifierSize } from './CopyableIdentifier';

interface MemberIdentifierProps {
  partyId: string;
  isYou: boolean;
  size: CopyableIdentifierSize;
  'data-testid': string;
}

const MemberIdentifier: React.FC<MemberIdentifierProps> = ({
  partyId,
  isYou,
  size,
  'data-testid': testId,
}) => (
  <CopyableIdentifier
    value={partyId}
    copyValue={partyId}
    badge={isYou ? 'You' : undefined}
    size={size}
    data-testid={testId}
  />
);

export default MemberIdentifier;
