package edu.uci.ics.amber.engine.architecture.common

import akka.actor.{Actor, ActorLogging, ActorRef, Stash}
import com.softwaremill.macwire.wire
import edu.uci.ics.amber.engine.architecture.messaginglayer.NetworkSenderActor.{
  NetworkSenderActorRef,
  QueryActorRef,
  RegisterActorRef
}
import edu.uci.ics.amber.engine.architecture.messaginglayer.{
  ControlInputPort,
  ControlOutputPort,
  NetworkSenderActor
}
import edu.uci.ics.amber.engine.common.amberexception.WorkflowRuntimeException
import edu.uci.ics.amber.engine.common.ambertag.neo.VirtualIdentity.ActorVirtualIdentity
import edu.uci.ics.amber.error.WorkflowRuntimeError

abstract class WorkflowActor(identifier: ActorVirtualIdentity)
    extends Actor
    with ActorLogging
    with Stash {

  val networkSenderActor: NetworkSenderActorRef = NetworkSenderActorRef(
    context.actorOf(NetworkSenderActor.props())
  )
  lazy val controlInputPort: ControlInputPort = wire[ControlInputPort]
  lazy val controlOutputPort: ControlOutputPort = wire[ControlOutputPort]

  def routeActorRefRelatedMessages: Receive = {
    case QueryActorRef(id, replyTo) =>
      if (replyTo.contains(networkSenderActor.ref)) {
        context.parent ! QueryActorRef(id, replyTo)
      } else {
        // we direct this message to the NetworkSenderActor
        // because it has the VirtualIdentityToActorRef for each actor.
        networkSenderActor ! QueryActorRef(id, replyTo)
      }
    case RegisterActorRef(id, ref) =>
      throw WorkflowRuntimeException(
        WorkflowRuntimeError(
          "workflow actor should never receive register actor ref message",
          identifier.toString,
          Map.empty
        )
      )
  }
}