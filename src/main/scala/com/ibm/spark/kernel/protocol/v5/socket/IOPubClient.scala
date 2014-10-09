package com.ibm.spark.kernel.protocol.v5.socket

import akka.actor.{Actor, ActorRef}
import akka.zeromq.ZMQMessage
import com.ibm.spark.client.message.StreamMessage
import com.ibm.spark.kernel.protocol.v5.MessageType._
import com.ibm.spark.kernel.protocol.v5._
import com.ibm.spark.kernel.protocol.v5.content.{StreamContent, ExecuteResult}
import com.ibm.spark.utils.LogLike
import play.api.libs.json.Json
import com.ibm.spark.kernel.protocol.v5.socket.IOPubClient._
import scala.collection.concurrent.{Map, TrieMap}

object IOPubClient {
  private val senderMap: Map[UUID, ActorRef] = TrieMap[UUID, ActorRef]()
  private val callbackMap: Map[UUID, Any => Unit] = TrieMap[UUID, Any => Unit]()
}

/**
 * The client endpoint for IOPub messages specified in the IPython Kernel Spec
 * @param socketFactory A factory to create the ZeroMQ socket connection
 */
class IOPubClient(socketFactory: SocketFactory) extends Actor with LogLike {
  private val socket = socketFactory.IOPubClient(context.system, self)

  override def receive: Receive = {
    case message: ZMQMessage =>
      // convert to KernelMessage using implicits in v5
      val kernelMessage: KernelMessage = message
      val messageType: MessageType = MessageType.withName(kernelMessage.header.msg_type)

      messageType match {
        case MessageType.ExecuteResult =>
          logger.debug("Received ExecuteResult message")
          // look up callback in CallbackMap based on msg_id and invoke
          val id = kernelMessage.parentHeader.msg_id
          logger.debug("SenderMap Key set" + senderMap.keySet)
          val client = senderMap.get(id)
          logger.debug(s"id is: ${id}")
          logger.debug(s"client is: ${client}")
          client match {
            case Some(actorRef) =>
              logger.debug("Matched cased for actorRef")
              actorRef ! Json.parse(kernelMessage.contentString).as[ExecuteResult]
              senderMap.remove(id)
            case None =>
              logger.debug("Matched case for none")
              logger.debug("IOPubClient: actorRef was none")
          }

        case MessageType.Stream =>
          logger.debug("Received Stream message")
          val id = kernelMessage.parentHeader.msg_id
          val func = callbackMap.get(id)
          logger.debug(s"id is: ${id}")
          logger.debug(s"func is: ${func}")
          func match {
            case Some(f) =>
              logger.debug("Matched cased f")
              val streamContent = Json.parse(kernelMessage.contentString).as[StreamContent]
              f(streamContent.data)
            case None =>
              logger.debug("Matched case None")
              logger.debug(s"IOPubClient: no function for id ${id}")
          }

        case any =>
          logger.debug(s"Received unhandled MessageType ${any}")
      }

    case message: UUID =>
      senderMap.put(message, sender)

    case message: StreamMessage =>
      callbackMap.put(message.id, message.callback)
  }
}
