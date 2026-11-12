// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.environment.DarResource
import com.google.protobuf.ByteString
import java.io.{InputStream, OutputStream}
import java.net.{JarURLConnection, URL}
import java.nio.file.{Files, Paths}
import scala.util.Using

sealed trait UploadablePackage {
  def packageId: String
  def resourcePath: String

  /** The size of the DAR file in bytes. */
  def darSizeBytes: Long

  protected def resourceUrl: URL = Option(
    getClass.getClassLoader.getResource(resourcePath)
  ).getOrElse(
    throw new IllegalStateException(
      s"Failed to load [$resourcePath] from classpath"
    )
  )

  def inputStream(): InputStream = Option(
    getClass.getClassLoader.getResourceAsStream(resourcePath)
  ) match {
    case Some(is) => is
    case None =>
      throw new IllegalStateException(
        s"Failed to load [$resourcePath] from classpath"
      )
  }
}

object UploadablePackage {
  def fromResource(resource: DarResource): UploadablePackage = {
    new UploadablePackage {
      override val packageId = resource.packageId
      override val resourcePath = resource.path

      override def darSizeBytes: Long = {
        val url = resourceUrl
        val size =
          // getContentLengthLong would also work for file: URLs, but before
          // JDK 26 it leaves a FileInputStream open there
          // (https://bugs.openjdk.org/browse/JDK-8367561).
          if (url.getProtocol == "file") Files.size(Paths.get(url.toURI))
          else
            url.openConnection() match {
              case jar: JarURLConnection =>
                // reads the entry size from the jar's central directory; an
                // uncached JarFile is only released by closing a stream, so
                // close it here
                try jar.getContentLengthLong
                finally if (!jar.getUseCaches) jar.getJarFile.close()
              case other => other.getContentLengthLong
            }
        if (size >= 0) size
        else Using.resource(inputStream())(_.transferTo(OutputStream.nullOutputStream()))
      }
    }
  }
  def fromByteString(name: String, bs: ByteString): UploadablePackage = {
    val pkgId = DarUtil.readPackageId(name, bs.newInput)
    (
      new UploadablePackage {
        override def packageId = pkgId
        override def resourcePath = name
        override def darSizeBytes = bs.size.toLong
        override def inputStream() = bs.newInput
      }
    )
  }

  /** Drops repeated uploads of the same DAR: same main package id and
    * same byte size. Sizes are only read for package ids that occur
    * more than once.
    */
  def distinct(dars: Seq[UploadablePackage]): Seq[UploadablePackage] = {
    val repeatedIds =
      dars.groupMapReduce(_.packageId)(_ => false)((_, _) => true)
    dars.distinctBy(dar =>
      (dar.packageId, if (repeatedIds(dar.packageId)) dar.darSizeBytes else 0L)
    )
  }
}
