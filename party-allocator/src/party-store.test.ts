// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import * as crypto from "node:crypto";
import { mkdtemp, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { PartyStore } from "./party-store.js";

describe("PartyStore", () => {
  let dir: string;
  beforeEach(async () => {
    dir = await mkdtemp(`${tmpdir()}/party-store-`);
  });
  afterEach(async () => {
    await rm(dir, { recursive: true, force: true });
  });

  it("starts at 0 in an empty or missing directory", async () => {
    expect(await new PartyStore(`${dir}/keys`).load()).toEqual({
      nextIndex: 0,
      pending: [],
    });
  });

  it("resumes after the highest key and returns unfinished parties", async () => {
    const store = new PartyStore(dir);
    for (const i of [0, 1, 2, 3]) {
      await store.markPending(i);
      await store.loadOrCreateKeyPair(i);
    }
    await store.markDone(0);
    await store.markDone(2);
    // Leftover from a write interrupted by a crash.
    await writeFile(`${dir}/7_priv.key.tmp`, "");
    expect(await new PartyStore(dir).load()).toEqual({
      nextIndex: 4,
      pending: [1, 3],
    });
  });

  it("reuses an existing key", async () => {
    const store = new PartyStore(dir);
    const first = await store.loadOrCreateKeyPair(5);
    expect(first.existed).toBe(false);
    const second = await store.loadOrCreateKeyPair(5);
    expect(second.existed).toBe(true);
    expect(second.keyPair.publicKey.equals(first.keyPair.publicKey)).toBe(true);
    expect(second.keyPair.privateKey).toBe(first.keyPair.privateKey);
    const signature = crypto.sign(
      null,
      Buffer.from("hash"),
      second.keyPair.privateKey,
    );
    const publicKey = crypto.createPublicKey({
      key: second.keyPair.publicKey,
      format: "der",
      type: "spki",
    });
    expect(crypto.verify(null, Buffer.from("hash"), publicKey, signature)).toBe(
      true,
    );
    expect((await readdir(dir)).sort()).toEqual(["5_priv.key", "5_pub.key"]);
  });

  it("creates a new key if only the public key was written", async () => {
    const store = new PartyStore(dir);
    await writeFile(`${dir}/6_pub.key`, "partial");
    const { keyPair, existed } = await store.loadOrCreateKeyPair(6);
    expect(existed).toBe(false);
    expect(keyPair.publicKey.toString()).not.toBe("partial");
  });
});
