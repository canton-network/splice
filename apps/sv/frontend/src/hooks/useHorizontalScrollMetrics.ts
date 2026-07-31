// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { RefObject, useLayoutEffect, useState } from 'react';

export interface ScrollMetrics {
  canScroll: boolean;
  thumbWidthPercent: number;
  thumbLeftPercent: number;
}

const emptyMetrics: ScrollMetrics = {
  canScroll: false,
  thumbWidthPercent: 100,
  thumbLeftPercent: 0,
};

export const computeScrollMetrics = (el: HTMLElement): ScrollMetrics => {
  const { scrollLeft, scrollWidth, clientWidth } = el;
  if (scrollWidth <= clientWidth + 1) {
    return emptyMetrics;
  }

  const thumbWidthPercent = Math.max((clientWidth / scrollWidth) * 100, 8);
  const maxLeft = 100 - thumbWidthPercent;
  const scrollableDistance = scrollWidth - clientWidth;
  const thumbLeftPercent = scrollableDistance > 0 ? (scrollLeft / scrollableDistance) * maxLeft : 0;

  return { canScroll: true, thumbWidthPercent, thumbLeftPercent };
};

export const useHorizontalScrollMetrics = (
  scrollRef: RefObject<HTMLElement | null>,
  deps: unknown[] = []
): ScrollMetrics => {
  const [metrics, setMetrics] = useState<ScrollMetrics>(emptyMetrics);

  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (!el) return;

    const update = () => setMetrics(computeScrollMetrics(el));

    update();
    const observer = new ResizeObserver(update);
    observer.observe(el);
    el.addEventListener('scroll', update, { passive: true });

    return () => {
      observer.disconnect();
      el.removeEventListener('scroll', update);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return metrics;
};
