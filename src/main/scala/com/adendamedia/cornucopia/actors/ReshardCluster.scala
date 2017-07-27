package com.adendamedia.cornucopia.actors

import akka.actor.SupervisorStrategy.Escalate
import akka.actor.SupervisorStrategy.{Escalate, Restart}
import akka.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, OneForOneStrategy, Props, Terminated}
import akka.pattern.pipe
import akka.actor.Status.{Failure, Success}
import com.adendamedia.cornucopia.redis.{ClusterOperations, ReshardTableNew}
import com.adendamedia.cornucopia.CornucopiaException._
import com.adendamedia.cornucopia.ConfigNew.ReshardClusterConfig
import Overseer.{GotReshardTable, OverseerCommand, ReshardWithNewMaster}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import com.lambdaworks.redis.RedisURI
import com.lambdaworks.redis.cluster.models.partitions.RedisClusterNode

object ReshardClusterSupervisor {
  def props(computeReshardTableMaker: ActorRefFactory => ActorRef)
           (implicit clusterOperations: ClusterOperations, config: ReshardClusterConfig): Props =
    Props(new ReshardClusterSupervisor(computeReshardTableMaker))

  val name = "reshardClusterSupervisor"

  case object Retry
}

/**
  * Supervises the resharding of the cluster
  *
  * @param computeReshardTableMaker Factory method for creating child actor
  * @param clusterOperations Singleton containing Redis cluster operations
  */
class ReshardClusterSupervisor(computeReshardTableMaker: ActorRefFactory => ActorRef)
                              (implicit clusterOperations: ClusterOperations, config: ReshardClusterConfig)
  extends Actor with ActorLogging {

  import ReshardTableNew.ReshardTableException
  import Overseer._
  import ReshardClusterSupervisor._

  val getRedisSourceNodesProps = GetRedisSourceNodes.props(computeReshardTableMaker)
  val getRedisSourceNodes = context.actorOf(getRedisSourceNodesProps, GetRedisSourceNodes.name)

  context.watch(getRedisSourceNodes)

  override def supervisorStrategy = OneForOneStrategy(config.maxNrRetries) {
    case _: FailedOverseerCommand => Restart
    case _: ReshardTableException =>
      self ! Retry
      Restart
  }

  override def receive: Receive = accepting

  private def accepting: Receive = {
    case reshard: ReshardWithNewMaster =>
      log.info(s"Resharding with new master ${reshard.uri}")
      getRedisSourceNodes ! reshard
      context.become(resharding(reshard, sender))
    case reshard: ReshardWithoutRetiredMaster =>
      log.info(s"Resharding without retired master ${reshard.uri}")
      getRedisSourceNodes ! reshard
      context.become(resharding(reshard, sender))
  }

  private def resharding(reshard: Reshard, ref: ActorRef): Receive = {
    case Retry =>
      log.info(s"Retrying to reshard cluster")
      getRedisSourceNodes ! reshard
    case Terminated =>
      // TODO: publish message to event bus
      context.become(accepting)
    case table: GotReshardTable =>
      ref ! table
      context.become(accepting)
  }

}

object GetRedisSourceNodes {
  def props(computeReshardTableMaker: ActorRefFactory => ActorRef)
           (implicit clusterOperations: ClusterOperations, config: ReshardClusterConfig): Props =
    Props(new GetRedisSourceNodes(computeReshardTableMaker))

  val name = "getRedisSourceNodes"

  case class SourceNodes(nodes: List[RedisClusterNode])
  case class TargetNodesAndSourceNode(targetNodes: List[RedisClusterNode], sourceNode: RedisClusterNode,
                                      retiredMasterUri: RedisURI)
}

class GetRedisSourceNodes(computeReshardTableMaker: ActorRefFactory => ActorRef)
                         (implicit clusterOperations: ClusterOperations, config: ReshardClusterConfig)
  extends Actor with ActorLogging {

  import Overseer._
  import GetRedisSourceNodes._
  import ReshardTableNew.ReshardTableException
  import akka.pattern.pipe

  implicit val ec: ExecutionContext = config.executionContext

  // Escalate this error because it might mean that we need to get the source nodes again
  override def supervisorStrategy = OneForOneStrategy() {
    case e: ReshardTableException => Escalate
  }

  val computeReshardTable = computeReshardTableMaker(context)

  override def receive: Receive = {
    case reshard: ReshardWithNewMaster =>
      getRedisSourceNodes(reshard)
      context.become(gettingReshardTable(sender))
    case reshard: ReshardWithoutRetiredMaster =>
      getRedisTargetNodesAndRetiredNode(reshard)
      context.become(gettingReshardTable(sender))
  }

  private def gettingReshardTable(ref: ActorRef): Receive = {
    case table: GotReshardTable =>
      ref ! table
      context.unbecome()
  }

  private def getRedisTargetNodesAndRetiredNode(reshard: ReshardWithoutRetiredMaster) = {
    var uri = reshard.uri
    clusterOperations.getRedisTargetNodesAndRetiredNode(uri) map { case (ts: List[RedisClusterNode], s: RedisClusterNode) =>
      TargetNodesAndSourceNode(ts, s, uri)
    } pipeTo computeReshardTable
  }

  private def getRedisSourceNodes(reshard: ReshardWithNewMaster) = {
    val uri = reshard.uri
    clusterOperations.getRedisSourceNodes(uri) map { sourceNodes =>
      (reshard.uri, SourceNodes(sourceNodes))
    } pipeTo computeReshardTable
  }

}

object ComputeReshardTable {
  def props(implicit reshardTable: ReshardTableNew, config: ReshardClusterConfig): Props =
    Props(new ComputeReshardTable)

  val name = "computeReshardTable"
}

class ComputeReshardTable(implicit reshardTable: ReshardTableNew, config: ReshardClusterConfig)
  extends Actor with ActorLogging {

  import GetRedisSourceNodes._
  import ReshardTableNew.ReshardTableType

  override def receive: Receive = {
    case (uri: RedisURI, sourceNodes: SourceNodes) =>
      log.info(s"Computing reshard table to add new master ${uri.toURI}")
      computeReshardTable(uri, sourceNodes, sender)
    case TargetNodesAndSourceNode(targetNodes: List[RedisClusterNode], retiredNode: RedisClusterNode, uri: RedisURI) =>
      val oldMasterUri = retiredNode.getUri
      log.info(s"Computing reshard table to remove an old master $uri")
      computeReshardTablePrime(oldMasterUri, targetNodes, retiredNode, sender)
  }

  def computeReshardTable(uri: RedisURI, sourceNodes: SourceNodes, ref: ActorRef) = {
    implicit val expectedTotalNumberSlots: Int = config.expectedTotalNumberSlots
    val table: ReshardTableType = reshardTable.computeReshardTable(sourceNodes.nodes)
    ref ! GotReshardTable(table)
  }

  private def computeReshardTablePrime(oldRedisUri: RedisURI, targetNodes: List[RedisClusterNode],
                                       retiredNode: RedisClusterNode, ref: ActorRef) = {
    implicit val expectedTotalNumberSlots: Int = config.expectedTotalNumberSlots
    val table: ReshardTableType = reshardTable.computeReshardTablePrime(retiredNode, targetNodes)
    ref ! GotReshardTable(table)
  }

}

