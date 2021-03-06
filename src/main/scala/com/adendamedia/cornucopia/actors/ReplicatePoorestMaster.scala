package com.adendamedia.cornucopia.actors

import com.adendamedia.cornucopia.Config.ReplicatePoorestMasterConfig
import com.adendamedia.cornucopia.redis.ClusterOperations
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props}
import akka.actor.SupervisorStrategy.Restart
import akka.pattern.pipe
import Overseer.ReplicatePoorestMasterCommand

import scala.concurrent.ExecutionContext

object ReplicatePoorestMasterSupervisor {
  def props(implicit config: ReplicatePoorestMasterConfig,
            clusterOperations: ClusterOperations): Props = Props(new ReplicatePoorestMasterSupervisor)

  val name = "replicatePoorestMasterSupervisor"

  case object Retry
}

class ReplicatePoorestMasterSupervisor[C <: ReplicatePoorestMasterCommand](implicit config: ReplicatePoorestMasterConfig,
                                                                           clusterOperations: ClusterOperations)
  extends CornucopiaSupervisor[ReplicatePoorestMasterCommand] {

  import Overseer._
  import ClusterOperations._
  import ReplicatePoorestMasterSupervisor._

  override def receive: Receive = accepting

  private val findPoorestMaster: ActorRef = context.actorOf(FindPoorestMaster.props, FindPoorestMaster.name)

  override def supervisorStrategy = OneForOneStrategy(config.maxNrRetries) {
    case e: CornucopiaFindPoorestMasterException =>
      log.error(s"Failed to find poorest master: {}", e)
      self ! Retry
      Restart
  }

  override def accepting: Receive = {
    case msg: ReplicatePoorestMasterUsingSlave =>
      log.info(s"Received message to replicate poorest master with redis node ${msg.slaveUri}")
      findPoorestMaster ! msg
      context.become(processing(msg, sender))
    case msg: ReplicatePoorestRemainingMasterUsingSlave =>
      log.info(s"Received message to replicate poorest remaining master with slave node ${msg.slaveUri}")
      findPoorestMaster ! msg
      context.become(processing(msg, sender))
  }

  override def processing[D <: ReplicatePoorestMasterCommand](command: D, ref: ActorRef): Receive = {
    case msg: ReplicatedMaster =>
      log.info(s"Successfully replicated master with new slave: ${msg.newSlaveUri}")
      ref ! msg
      context.unbecome()
    case Retry =>
      log.info(s"Retrying to replicate poorest master")
      findPoorestMaster ! command
  }
}

object FindPoorestMaster {
  def props(implicit config: ReplicatePoorestMasterConfig,
            clusterOperations: ClusterOperations): Props = Props(new FindPoorestMaster)

  val name = "findPoorestMaster"
}

class FindPoorestMaster(implicit config: ReplicatePoorestMasterConfig,
                        clusterOperations: ClusterOperations) extends Actor with ActorLogging {
  import Overseer._

  val replicatePoorestMaster = context.actorOf(ReplicatePoorestMaster.props, ReplicatePoorestMaster.name)

  override def receive: Receive = {
    case msg: ReplicatePoorestMasterUsingSlave =>
      findPoorestMaster(msg, sender)
    case msg: ReplicatePoorestRemainingMasterUsingSlave =>
      findPoorestRemainingMaster(msg, sender)
    case kill: KillChild =>
      val e = kill.reason.getOrElse(new Exception)
      throw e
  }

  private def findPoorestRemainingMaster(msg: ReplicatePoorestRemainingMasterUsingSlave, supervisor: ActorRef) = {
    implicit val executionContext: ExecutionContext = config.executionContext
    val excludedMasters = msg.excludedMasters
    clusterOperations.findPoorestRemainingMaster(excludedMasters) map { poorestMaster =>
      log.info(s"Found poorest remaining master to replicate: $poorestMaster")
      ReplicateMaster(msg.slaveUri, poorestMaster, supervisor)
    } recover {
      case e => self ! KillChild(msg, Some(e))
    } pipeTo replicatePoorestMaster
  }

  private def findPoorestMaster(msg: ReplicatePoorestMasterUsingSlave, supervisor: ActorRef) = {
    implicit val executionContext: ExecutionContext = config.executionContext
    clusterOperations.findPoorestMaster map { poorestMaster =>
      log.info(s"Found poorest master to replicate: $poorestMaster")
      ReplicateMaster(msg.slaveUri, poorestMaster, supervisor)
    } recover {
      case e => self ! KillChild(msg, Some(e))
    } pipeTo replicatePoorestMaster
  }

}

object ReplicatePoorestMaster {
  def props(implicit config: ReplicatePoorestMasterConfig,
            clusterOperations: ClusterOperations): Props = Props(new ReplicatePoorestMaster)

  val name = "replicatePoorestMaster"
}

class ReplicatePoorestMaster(implicit config: ReplicatePoorestMasterConfig,
                             clusterOperations: ClusterOperations) extends Actor with ActorLogging {
  import Overseer._

  override def receive: Receive = {
    case msg: ReplicateMaster =>
      replicateMaster(msg)
    case kill: KillChild =>
      val e = kill.reason.getOrElse(new Exception)
      throw e
  }

  private def replicateMaster(msg: ReplicateMaster) = {
    val supervisor = msg.ref
    implicit val executionContext: ExecutionContext = config.executionContext
    clusterOperations.replicateMaster(msg.slaveUri, msg.masterNodeId) map { _ =>
      ReplicatedMaster(msg.slaveUri)
    } recover {
      case e => self ! KillChild(msg, Some(e))
    } pipeTo supervisor
  }

}
