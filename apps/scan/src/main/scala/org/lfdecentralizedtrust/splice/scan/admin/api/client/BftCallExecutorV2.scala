package org.lfdecentralizedtrust.splice.scan.admin.api.client

import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.{
  BftCallConfig,
  ScanConnections,
}

import scala.concurrent.Future

object BftCallExecutorV2 {

  def bftCall[T](
      bftConfig: BftCallConfig,
      call: SingleScanConnection => Future[T],
      connections: ScanConnections,
  ): Future[(T, List[Uri])] = eventualConsistencyBftCall(
    bftConfig,
    preCall = _ => Future.successful(true),
    bftConfigForCall = (config, _) => config,
    call,
    connections,
  )

  def eventualConsistencyBftCall[T](
      initialBftConfig: BftCallConfig,
      preCall: SingleScanConnection => Future[Boolean],
      bftConfigForCall: (BftCallConfig, Map[SingleScanConnection, Boolean]) => BftCallConfig,
      call: SingleScanConnection => Future[T],
      connections: ScanConnections,
  ): Future[(T, List[Uri])] = ???

}
