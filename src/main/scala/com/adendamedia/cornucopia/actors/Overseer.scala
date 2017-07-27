package com.adendamedia.cornucopia.actors

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{Actor, ActorLogging, ActorRef, ActorRefFactory, OneForOneStrategy, Props, Terminated}
import com.adendamedia.cornucopia.CornucopiaException._
import com.adendamedia.cornucopia.redis.ClusterOperations
import com.adendamedia.cornucopia.redis.ReshardTableNew.ReshardTableType
import com.adendamedia.cornucopia.Config
import com.adendamedia.cornucopia.redis.ClusterOperations.{RedisUriToNodeId, ClusterConnectionsType}
import com.lambdaworks.redis.RedisURI

import scala.concurrent.duration._

object Overseer {
  def props(joinRedisNodeSupervisorMaker: ActorRefFactory => ActorRef,
            reshardClusterSupervisorMaker: ActorRefFactory => ActorRef,
            clusterConnectionsSupervisorMaker: ActorRefFactory => ActorRef,
            clusterReadySupervisorMaker: ActorRefFactory => ActorRef,
            migrateSlotSupervisorMaker: ActorRefFactory => ActorRef,
            replicatePoorestMasterSupervisorMaker: ActorRefFactory => ActorRef,
            failoverSupervisorMaker: ActorRefFactory => ActorRef)
           (implicit clusterOperations: ClusterOperations): Props =
    Props(new Overseer(joinRedisNodeSupervisorMaker, reshardClusterSupervisorMaker, clusterConnectionsSupervisorMaker,
      clusterReadySupervisorMaker, migrateSlotSupervisorMaker, replicatePoorestMasterSupervisorMaker,
      failoverSupervisorMaker)
    )

  val name = "overseer"

  trait OverseerCommand

  trait NodeAddedEvent {
    val uri: RedisURI
  }

  trait JoinNode extends OverseerCommand {
    val redisURI: RedisURI
  }

  case class JoinMasterNode(redisURI: RedisURI) extends JoinNode
  case class JoinSlaveNode(redisURI: RedisURI) extends JoinNode

  case class MasterNodeAdded(uri: RedisURI) extends NodeAddedEvent
  case class SlaveNodeAdded(uri: RedisURI) extends NodeAddedEvent

  trait NodeJoinedEvent {
    val uri: RedisURI
  }
  case class MasterNodeJoined(uri: RedisURI) extends NodeJoinedEvent
  case class SlaveNodeJoined(uri: RedisURI) extends NodeJoinedEvent

  trait Reshard extends OverseerCommand
  case class ReshardWithNewMaster(uri: RedisURI) extends Reshard
  case class ReshardWithoutRetiredMaster(uri: RedisURI) extends Reshard

  case class GetClusterConnections(newRedisUri: RedisURI) extends OverseerCommand
  case class GotClusterConnections(connections: (ClusterOperations.ClusterConnectionsType,ClusterOperations.RedisUriToNodeId))

  case class GotReshardTable(reshardTable: ReshardTableType)

  case class KillChild(command: OverseerCommand, reason: Option[Throwable] = None)

  case class WaitForClusterToBeReady(connections: ClusterOperations.ClusterConnectionsType) extends OverseerCommand
  case object ClusterIsReady
  case object ClusterNotReady

  case class MigrateSlotsForNewMaster(newMasterUri: RedisURI, connections: ClusterOperations.ClusterConnectionsType,
                                      redisUriToNodeId: RedisUriToNodeId,
                                      reshardTable: ReshardTableType) extends OverseerCommand

  case class MigrateSlotsWithoutRetiredMaster(retiredMasterUri: RedisURI, connections: ClusterOperations.ClusterConnectionsType,
                                              redisUriToNodeId: RedisUriToNodeId,
                                              reshardTable: ReshardTableType) extends OverseerCommand

  case class JobCompleted(job: OverseerCommand)

  case object Reset extends OverseerCommand

  case class ValidateConnections(msg: GetClusterConnections, connections: (ClusterConnectionsType, RedisUriToNodeId)) extends OverseerCommand
  case object ClusterConnectionsValid
  case object ClusterConnectionsInvalid

  case class ReplicatePoorestMasterUsingSlave(slaveUri: RedisURI,
                                              connections: ClusterOperations.ClusterConnectionsType,
                                              redisUriToNodeId: RedisUriToNodeId) extends OverseerCommand
  case class ReplicateMaster(slaveUri: RedisURI, masterNodeId: ClusterOperations.NodeId,
                             connections: ClusterConnectionsType, redisUriToNodeId: RedisUriToNodeId,
                             ref: ActorRef) extends OverseerCommand
  case class ReplicatedMaster(newSlaveUri: RedisURI)

  trait FailoverCommand extends OverseerCommand {
    val uri: RedisURI
  }
  /**
    * Used when removing a master
     * @param uri The URI of the redis node that should become a master if necessary
    */
  case class FailoverMaster(uri: RedisURI) extends FailoverCommand
  /**
    * Used when removing a slave
    * @param uri The URI of the redis node that should become a slave if necessary
    */
  case class FailoverSlave(uri: RedisURI) extends FailoverCommand

  case object FailoverComplete
}

/**
  * The overseer subscribes to Redis commands that have been published by the dispatcher. This actor is the parent
  * actor of all actors that process Redis cluster commands. Cluster commands include adding and removing nodes. The
  * overseer subscribes to the Shutdown message, whereby after receiving this message, it will restart its children.
  */
class Overseer(joinRedisNodeSupervisorMaker: ActorRefFactory => ActorRef,
               reshardClusterSupervisorMaker: ActorRefFactory => ActorRef,
               clusterConnectionsSupervisorMaker: ActorRefFactory => ActorRef,
               clusterReadySupervisorMaker: ActorRefFactory => ActorRef,
               migrateSlotSupervisorMaker: ActorRefFactory => ActorRef,
               replicatePoorestMasterSupervisorMaker: ActorRefFactory => ActorRef,
               failoverSupervisorMaker: ActorRefFactory => ActorRef)
              (implicit clusterOperations: ClusterOperations) extends Actor with ActorLogging {
  import MessageBus.{AddNode, AddMaster, AddSlave, Shutdown, FailedAddingMasterRedisNode, RemoveMaster}
  import Overseer._
  import ClusterOperations.ClusterConnectionsType

  import context.dispatcher

  val joinRedisNodeSupervisor: ActorRef = joinRedisNodeSupervisorMaker(context)
  val reshardClusterSupervisor: ActorRef = reshardClusterSupervisorMaker(context)
  val clusterConnectionsSupervisor: ActorRef = clusterConnectionsSupervisorMaker(context)
  val clusterReadySupervisor: ActorRef = clusterReadySupervisorMaker(context)
  val migrateSlotsSupervisor: ActorRef = migrateSlotSupervisorMaker(context)
  val replicatePoorestMasterSupervisor: ActorRef = replicatePoorestMasterSupervisorMaker(context)
  val failoverSupervisor: ActorRef = failoverSupervisorMaker(context)

  context.system.eventStream.subscribe(self, classOf[AddNode])
  context.system.eventStream.subscribe(self, classOf[Shutdown])

  override def supervisorStrategy = OneForOneStrategy() {
    case e: FailedAddingRedisNodeException =>
      log.error(s"${e.message}: Restarting child actor")
      context.system.eventStream.publish(FailedAddingMasterRedisNode(e.message))
      Restart
  }

  override def receive: Receive = acceptingCommands

  private def acceptingCommands: Receive = {
    case m: AddMaster =>
      log.debug(s"Received message AddMaster(${m.uri})")
      joinRedisNodeSupervisor ! JoinMasterNode(m.uri)
      context.become(joiningNode(m.uri))
    case s: AddSlave =>
      log.debug(s"Received message AddSlave(${s.uri})")
      joinRedisNodeSupervisor ! JoinSlaveNode(s.uri)
      context.become(joiningNode(s.uri))
    case rm: RemoveMaster =>
      log.debug(s"Received message RemoveMaster(${rm.uri})")
      failoverSupervisor ! rm
      context.become(failingOverForRemovingMaster(rm.uri))
  }

  private def failingOverForRemovingMaster(uri: RedisURI): Receive = {
    case FailoverComplete =>
      log.info(s"Failover completed successfully, $uri is now a master node")
      clusterConnectionsSupervisor ! GetClusterConnections(uri)
      reshardClusterSupervisor ! ReshardWithoutRetiredMaster(uri)
      context.become(computingReshardTableForRemovingMaster(uri))
  }

  private def computingReshardTableForRemovingMaster(retiredMaster: RedisURI,
                                                     clusterConnections: Option[(ClusterConnectionsType, RedisUriToNodeId)] = None,
                                                     reshardTable: Option[ReshardTableType] = None): Receive = {
    case GotClusterConnections(connections) =>
      log.info(s"Got cluster connections for removing retired master $retiredMaster")
      reshardTable match {
        case Some(table) =>
          val cmd = MigrateSlotsWithoutRetiredMaster(retiredMaster, connections._1, connections._2, table)
          migrateSlotsSupervisor ! cmd
          context.become(migratingSlotsWithoutRetiredMaster(cmd))
        case None =>
          context.become(computingReshardTableForRemovingMaster(retiredMaster, Some(connections), None))
      }
    case GotReshardTable(table) =>
      log.info(s"Got reshard table for removing retired master $retiredMaster")
      clusterConnections match {
        case Some(connections) =>
          val cmd = MigrateSlotsWithoutRetiredMaster(retiredMaster, connections._1, connections._2, table)
          migrateSlotsSupervisor ! cmd
          context.become(migratingSlotsWithoutRetiredMaster(cmd))
        case None =>
          context.become(computingReshardTableForRemovingMaster(retiredMaster, None, Some(table)))
      }
  }

  private def reshardingClusterWithoutRetiredMaster(uri: RedisURI, reshardTable: Option[ReshardTableType] = None,
                                                    clusterConnections: Option[(ClusterConnectionsType, RedisUriToNodeId)] = None): Receive = {
    case _ =>
  }

  private def joiningNode(uri: RedisURI): Receive = {
    case masterNodeJoined: MasterNodeJoined =>
      log.info(s"Master Redis node ${masterNodeJoined.uri} successfully joined")
      reshardClusterSupervisor ! ReshardWithNewMaster(uri)
      clusterConnectionsSupervisor ! GetClusterConnections(uri)
      context.become(reshardingWithNewMaster(uri))
    case slaveNodeJoined: SlaveNodeJoined =>
      log.info(s"Slave Redis node ${slaveNodeJoined.uri} successfully joined")
      clusterConnectionsSupervisor ! GetClusterConnections(uri)
      context.become(addingSlaveNode(slaveNodeJoined.uri))
  }

  private def addingSlaveNode(uri: RedisURI,
                              clusterConnections: Option[(ClusterConnectionsType, RedisUriToNodeId)] = None): Receive = {
    case GotClusterConnections(connections) =>
      log.info(s"Got cluster connections")
      val masterConnections = connections._1
      val redisUriToNodeId = connections._2
      replicatePoorestMasterSupervisor ! ReplicatePoorestMasterUsingSlave(uri, masterConnections, redisUriToNodeId)
      context.become(addingSlaveNode(uri, Some(connections)))
    case ReplicatedMaster(slaveUri) =>
      log.info(s"Successfully replicated master node by new slave node $uri")
      context.system.eventStream.publish(SlaveNodeAdded(slaveUri))
      context.become(acceptingCommands)
  }

  /**
    * Computing the reshard table and getting the cluster connections is done concurrently
    * @param uri The Redis URI of the new master being added
    * @param reshardTable The compute reshard table
    * @param clusterConnections The connections to the master nodes
    */
  private def reshardingWithNewMaster(uri: RedisURI, reshardTable: Option[ReshardTableType] = None,
                                      clusterConnections: Option[(ClusterConnectionsType, RedisUriToNodeId)] = None): Receive = {
    case reshard: ReshardWithNewMaster =>
      log.info(s"ReshardWithNewMaster $uri") // TODO: I can't remember why this should be here
    case GotClusterConnections(connections) =>
      log.info(s"Got cluster connections")
      reshardTable match {
        case Some(table) =>
          if (connectionsAreValidForAddingNewMaster(table, connections, uri)) {
            clusterReadySupervisor ! WaitForClusterToBeReady(connections._1)
            context.become(waitingForClusterToBeReadyForNewMaster(uri, table, connections))
          }
          else {
            log.warning(s"Redis connections are not valid")
            context.system.scheduler.scheduleOnce(1 seconds) {
              clusterConnectionsSupervisor ! GetClusterConnections
            }
            context.become(reshardingWithNewMaster(uri, Some(table), None)) // discard invalid connections
          }
        case None =>
          context.become(reshardingWithNewMaster(uri, None, Some(connections)))
      }
    case GotReshardTable(table) =>
      log.info(s"Got rehard table")
      clusterConnections match {
        case Some(connections) =>
          if (connectionsAreValidForAddingNewMaster(table, connections, uri)) {
            clusterReadySupervisor ! WaitForClusterToBeReady(connections._1)
            context.become(waitingForClusterToBeReadyForNewMaster(uri, table, connections))
          }
          else {
            log.warning(s"Redis connections are not valid")
            context.system.scheduler.scheduleOnce(1 seconds) {
              clusterConnectionsSupervisor ! GetClusterConnections
            }
            context.become(reshardingWithNewMaster(uri, Some(table), None)) // discard invalid connections
          }
        case None =>
          context.become(reshardingWithNewMaster(uri, Some(table), None))
      }
  }

  private def connectionsAreValidForAddingNewMaster(table: ReshardTableType,
                                                    connections: (ClusterConnectionsType, RedisUriToNodeId),
                                                    newRedisUri: RedisURI) = {
    // Since we validated the number of slots in the reshard table, we can be sure that it has all the master NodeId's in it
    val numNodes = table.keys.count(_ => true)
    val numConnections = connections._1.keys.count(_ != connections._2(newRedisUri.toString))
    numNodes == numConnections
  }

  private def waitingForClusterToBeReadyForNewMaster(uri: RedisURI, reshardTable: ReshardTableType,
                                                     connections: (ClusterConnectionsType, RedisUriToNodeId)): Receive = {
    case ClusterIsReady =>
      log.info(s"Cluster is ready, migrating slots")
      val msg = MigrateSlotsForNewMaster(uri, connections._1, connections._2, reshardTable)
      migrateSlotsSupervisor ! msg
      context.become(migratingSlotsForNewMaster(msg))
  }

  private def migratingSlotsForNewMaster(overseerCommand: OverseerCommand): Receive = {
    case JobCompleted(job: MigrateSlotsForNewMaster) =>
      log.info(s"Successfully added master node ${job.newMasterUri.toURI}")
      context.system.eventStream.publish(MasterNodeAdded(job.newMasterUri))
      context.become(acceptingCommands)
  }

  private def migratingSlotsWithoutRetiredMaster(overseerCommand: OverseerCommand): Receive = {
    case _ =>
  }

}
