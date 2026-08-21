// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { ContentCopy } from '@mui/icons-material';
import { Box, IconButton, Link } from '@mui/material';
import { sanitizeUrl } from '@canton-network/splice-common-frontend-utils';

import type { CopyableIdentifierSize } from './CopyableIdentifier';

interface CopyableUrlProps {
  url: string;
  size: CopyableIdentifierSize;
  maxContentLength?: number;
  'data-testid': string;
}

function abbreviateUrl(url: string, maxContentLength = 50): string {
  if (url.length <= maxContentLength) {
    return url;
  }
  return `${url.slice(0, maxContentLength)}...`;
}

const CopyableUrl: React.FC<CopyableUrlProps> = ({
  url,
  size,
  maxContentLength,
  'data-testid': testId,
}) => {
  const sanitizedUrl = sanitizeUrl(url);

  return (
    <Box sx={{ display: 'flex', alignItems: 'center', color: 'text.light' }} data-testid={testId}>
      <Link
        href={sanitizedUrl}
        target="_blank"
        color="inherit"
        underline="hover"
        sx={{
          fontFamily: 'Source Code Pro',
          fontSize: size === 'small' ? '14px' : '18px',
          fontWeight: 'medium',
          maxWidth: '100%',
          textOverflow: 'ellipsis',
          overflow: 'hidden',
        }}
        data-testid={`${testId}-link`}
      >
        {abbreviateUrl(sanitizedUrl, maxContentLength)}
      </Link>
      <IconButton
        color="secondary"
        data-testid={`${testId}-copy-button`}
        onClick={() => navigator.clipboard.writeText(sanitizedUrl)}
      >
        <ContentCopy sx={{ fontSize: size === 'small' ? '14px' : '18px' }} />
      </IconButton>
    </Box>
  );
};

export default CopyableUrl;
