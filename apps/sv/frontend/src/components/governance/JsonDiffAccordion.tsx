// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useState } from 'react';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import { Box, Collapse, Typography } from '@mui/material';

const JSON_DIFF_FRAME_BACKGROUND = '#363636';

const JSON_DIFF_VIEWPORT_MAX_HEIGHT = '320px';

const jsonDiffMonoSx = {
  fontFamily: '"Source Code Pro", monospace',
  fontSize: '14px',
  fontWeight: 400,
  lineHeight: '26px',
  fontFeatureSettings: "'liga' off, 'clig' off",
} as const;

const DIFF = '& [data-testid="config-diffs-display"]';

const jsonSectionTitleSx = {
  color: 'common.white',
  fontFamily: 'Inter, sans-serif',
  fontSize: 12,
  fontWeight: 600,
  lineHeight: '22px',
  textTransform: 'uppercase',
  margin: 0,
  display: 'block',
} as const;

const toggleSx = {
  display: 'inline-flex',
  boxSizing: 'border-box',
  height: '28px',
  minWidth: '108px',
  padding: '2px 8px',
  alignItems: 'center',
  justifyContent: 'center',
  gap: '4px',
  borderRadius: '2px',
  border: '1px solid',
  borderColor: 'secondary.main',
  bgcolor: 'transparent',
  cursor: 'pointer',
  margin: 0,
  minHeight: 0,
  lineHeight: 0,
  flexShrink: 0,
};

const toggleLabelSx = {
  color: 'common.white',
  fontFamily: 'Inter, sans-serif',
  fontSize: 14,
  fontWeight: 400,
  lineHeight: '22px',
  margin: 0,
  display: 'block',
};

const jsonDiffFrameSx = {
  display: 'flex',
  padding: '12px 24px',
  justifyContent: 'flex-start',
  alignItems: 'stretch',
  gap: '10px',
  alignSelf: 'stretch',
  width: '100%',
  minWidth: 0,
  maxWidth: '100%',
  boxSizing: 'border-box',
  backgroundColor: JSON_DIFF_FRAME_BACKGROUND,
} as const;

const collapseSx = {
  width: '100%',
  minWidth: 0,
  maxWidth: '100%',
  alignSelf: 'stretch',
  overflow: 'hidden',
  '& .MuiCollapse-wrapperInner': {
    width: '100%',
    minWidth: 0,
    maxWidth: '100%',
  },
} as const;

const jsonDiffHeaderRowSx = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  alignSelf: 'stretch',
  width: '100%',
} as const;

const jsonDiffRootSx = {
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'stretch',
  alignSelf: 'stretch',
  width: '100%',
  minWidth: 0,
  maxWidth: '100%',
  overflow: 'hidden',
} as const;

const jsonDiffViewportSx = {
  width: '100%',
  minWidth: 0,
  maxHeight: JSON_DIFF_VIEWPORT_MAX_HEIGHT,
  overflowY: 'auto',
  overflowX: 'hidden',

  '& > div': {
    width: '100%',
    minWidth: 0,
    maxWidth: '100%',
  },

  '& [data-testid="stringify-display"], & [data-testid="config-diffs-display"]': {
    width: '100%',
  },

  '& [data-testid="stringify-display"]': {
    ...jsonDiffMonoSx,
    color: 'common.white',
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
    overflowWrap: 'break-word',
  },

  [`${DIFF} .jsondiffpatch-delta`]: {
    ...jsonDiffMonoSx,
    color: 'common.white',
    display: 'block',
    maxWidth: '100%',
    boxSizing: 'border-box',
  },

  [`${DIFF} > .jsondiffpatch-delta`]: {
    padding: 0,
  },

  [`${DIFF} .jsondiffpatch-delta pre`]: {
    ...jsonDiffMonoSx,
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
    overflowWrap: 'break-word',
  },

  // Unchanged rows inherit grey onto keys and values (do not set color on pre/property-name directly).
  [`${DIFF} .jsondiffpatch-unchanged, ${DIFF} .jsondiffpatch-movedestination`]: {
    color: 'gray',
  },

  [`${DIFF} .jsondiffpatch-delta ul, ${DIFF} ul.jsondiffpatch-delta`]: {
    listStyleType: 'none',
    padding: '0 0 0 20px',
    margin: 0,
  },

  [`${DIFF} li`]: {
    display: 'block',
  },

  [`${DIFF} .jsondiffpatch-added .jsondiffpatch-value pre::after, ${DIFF} .jsondiffpatch-modified .jsondiffpatch-right-value pre::after, ${DIFF} .jsondiffpatch-deleted .jsondiffpatch-value pre::after`]:
    {
      content: '""',
      padding: 0,
    },

  [`${DIFF} li.jsondiffpatch-added:not(:last-child) > .jsondiffpatch-value::after, ${DIFF} li.jsondiffpatch-deleted:not(:last-child) > .jsondiffpatch-value::after, ${DIFF} li.jsondiffpatch-modified:not(:last-child) > .jsondiffpatch-right-value::after`]:
    {
      content: '","',
      color: 'common.white',
      padding: 0,
    },

  [`${DIFF} .jsondiffpatch-modified .jsondiffpatch-right-value`]: {
    marginLeft: 0,
  },
  [`${DIFF} .jsondiffpatch-modified .jsondiffpatch-right-value::before`]: {
    content: '" -> "',
  },
} as const;

interface JsonToggleButtonProps {
  expanded: boolean;
  onClick: () => void;
}

const JsonToggleButton: React.FC<JsonToggleButtonProps> = ({ expanded, onClick }) => (
  <Box
    component="button"
    type="button"
    onClick={onClick}
    aria-controls="json-diff-content"
    aria-expanded={expanded}
    id="json-diff-header"
    data-testid="json-diff-toggle"
    sx={toggleSx}
  >
    <Typography component="span" variant="inherit" sx={toggleLabelSx}>
      {expanded ? 'Hide JSON' : 'Show JSON'}
    </Typography>
    <ExpandMoreIcon
      sx={{
        color: 'secondary.main',
        fontSize: 16,
        width: 16,
        height: 16,
        display: 'block',
        flexShrink: 0,
        transform: expanded ? 'rotate(180deg)' : 'none',
        transition: 'transform 0.2s',
      }}
    />
  </Box>
);

export interface JsonDiffAccordionProps {
  children: React.ReactNode;
  /** `form` — JSON label left, toggle right; `review` — toggle only. */
  variant?: 'form' | 'review';
}

export const JsonDiffAccordion: React.FC<JsonDiffAccordionProps> = ({
  children,
  variant = 'review',
}) => {
  const [expanded, setExpanded] = useState(false);
  const toggle = (
    <JsonToggleButton expanded={expanded} onClick={() => setExpanded(current => !current)} />
  );

  const content = (
    <Collapse in={expanded} sx={collapseSx} unmountOnExit={false}>
      <Box
        id="json-diff-content"
        data-testid="json-diffs-details"
        sx={{
          ...jsonDiffFrameSx,
          // Remove frame from horizontal layout when collapsed; keep in DOM for tests.
          display: expanded ? 'flex' : 'none',
        }}
      >
        <Box sx={jsonDiffViewportSx}>{children}</Box>
      </Box>
    </Collapse>
  );

  return (
    <Box sx={{ ...jsonDiffRootSx, gap: variant === 'form' ? '14px' : '16px' }}>
      {variant === 'form' ? (
        <Box sx={jsonDiffHeaderRowSx}>
          <Typography component="span" variant="inherit" sx={jsonSectionTitleSx}>
            JSON
          </Typography>
          {toggle}
        </Box>
      ) : (
        <Box sx={{ alignSelf: 'flex-start' }}>{toggle}</Box>
      )}
      {content}
    </Box>
  );
};
