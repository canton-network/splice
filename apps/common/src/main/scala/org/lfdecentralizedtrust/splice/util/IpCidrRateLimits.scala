// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.google.common.net.InetAddresses

import java.net.InetAddress
import scala.util.Try

/** An IP network in CIDR notation, e.g. `10.0.0.0/8` or `2001:db8::/32`. A value without a prefix
  * length denotes a single host, i.e. a `/32` (IPv4) or `/128` (IPv6) network.
  *
  * @param network
  *   the network address with all host bits zeroed out, 4 bytes for IPv4 and 16 bytes for IPv6.
  */
final case class IpCidr(network: Seq[Byte], prefixLength: Int) {

  /** Whether this network fully contains `other`, which is the case if both are of the same IP
    * version, this network is not more specific than `other` and their common prefix matches.
    */
  def contains(other: IpCidr): Boolean =
    network.length == other.network.length &&
      prefixLength <= other.prefixLength &&
      IpCidr.mask(other.network, prefixLength) == network

  override def toString: String =
    s"${InetAddress.getByAddress(network.toArray).getHostAddress}/$prefixLength"
}

object IpCidr {

  def tryParse(value: String): IpCidr =
    parse(value).getOrElse(
      throw new IllegalArgumentException(
        s"'$value' is not a valid IP address or network in CIDR notation"
      )
    )

  def parse(value: String): Option[IpCidr] =
    value.split("/", -1).toSeq match {
      case Seq(address) => ofAddress(address).map(bytes => IpCidr(bytes, bytes.length * 8))
      case Seq(address, prefixLength) =>
        for {
          bytes <- ofAddress(address)
          length <- Try(prefixLength.trim.toInt).toOption
          if length >= 0 && length <= bytes.length * 8
        } yield IpCidr(mask(bytes, length), length)
      case _ => None
    }

  private def ofAddress(address: String): Option[Seq[Byte]] = {
    val trimmed = address.trim
    // must not do a DNS lookup, only IP literals are accepted
    Option
      .when(InetAddresses.isInetAddress(trimmed))(InetAddresses.forString(trimmed))
      // IPv4-mapped IPv6 addresses (e.g. ::ffff:192.0.2.1) are converted to their IPv4 address by
      // InetAddress.getByAddress, so they match the same networks as the plain IPv4 address
      .map(_.getAddress.toSeq)
  }

  /** Zeroes out all bits after `prefixLength`. */
  private[util] def mask(address: Seq[Byte], prefixLength: Int): Seq[Byte] =
    address.zipWithIndex.map { case (byte, index) =>
      val bitsToKeep = Math.min(8, Math.max(0, prefixLength - index * 8))
      (byte & (0xff << (8 - bitsToKeep))).toByte
    }
}

object IpCidrRateLimits {

  /** Returns a matcher for the given config, resolving a client IP to the limit of the most
    * specific network it is contained in.
    */
  def matchClientIp(
      config: PerAttributeRateLimitConfig
  ): String => Option[SpliceRateLimitConfig.Simple] = {
    val parsedNetworks = networks(config.attributeOverrides)
    clientIp =>
      Option
        .when(config.attributeOverrides.nonEmpty)(IpCidr.parse(clientIp))
        .flatten
        .flatMap { ip =>
          parsedNetworks.collectFirst {
            case (network, limit) if network.contains(ip) => limit
          }
        }
  }

  def tryValidate(config: PerAttributeRateLimitConfig): Unit =
    networks(config.attributeOverrides).discard

  private def networks(
      overrides: Map[String, SpliceRateLimitConfig.Simple]
  ): Seq[(IpCidr, SpliceRateLimitConfig.Simple)] =
    overrides.toSeq
      .map { case (cidr, limit) => IpCidr.tryParse(cidr) -> limit }
      // most specific network first, so that it takes precedence over the networks containing it
      .sortBy { case (network, _) => -network.prefixLength }
}
