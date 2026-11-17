// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as pulumi from '@pulumi/pulumi';

/**
 * This represents a value that is an output of a resource but also has the resolved value available directly.
 * It is useful in scenarios where you need both to pass it to resources to create dependencies between them
 * and where resource creation depends on the resolved value like using it in names or conditions for resource creation.
 */
export type ResolvedOutput<T> = {
  value: T;
  output: pulumi.Output<pulumi.Unwrap<T>>;
};

export function resolvedOutput<T>(value: T): ResolvedOutput<T> {
  return {
    value,
    output: pulumi.output(value),
  };
}
