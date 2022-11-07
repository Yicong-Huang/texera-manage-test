package edu.uci.ics.amber.engine.architecture.logging

import com.google.common.collect.Queues
import edu.uci.ics.amber.engine.architecture.logging.storage.DeterminantLogStorage
import edu.uci.ics.amber.engine.architecture.logging.storage.DeterminantLogStorage.DeterminantLogWriter
import edu.uci.ics.amber.engine.architecture.messaginglayer.NetworkCommunicationActor
import edu.uci.ics.amber.engine.architecture.messaginglayer.NetworkCommunicationActor.SendRequest
import edu.uci.ics.amber.engine.common.AmberUtils

import java.util
import java.util.concurrent.CompletableFuture
import scala.collection.JavaConverters._
import scala.util.control.Breaks.{break, breakable}

class AsyncLogWriter(
    networkCommunicationActor: NetworkCommunicationActor.NetworkSenderActorRef,
    writer: DeterminantLogWriter
) extends Thread {
  private val drained = new util.ArrayList[Either[InMemDeterminant, SendRequest]]()
  private val writerQueue =
    Queues.newLinkedBlockingQueue[Either[InMemDeterminant, SendRequest]]()
  @volatile private var stopped = false
  private val logInterval =
    AmberUtils.amberConfig.getLong("fault-tolerance.log-flush-interval-ms")
  private val gracefullyStopped = new CompletableFuture[Unit]()

  def putDeterminants(determinants: Array[InMemDeterminant]): Unit = {
    determinants.foreach(x => {
      writerQueue.put(Left(x))
    })
  }

  def putOutput(output: SendRequest): Unit = {
    writerQueue.put(Right(output))
  }

  def terminate(): Unit = {
    stopped = true
    interrupt()
    gracefullyStopped.get()
  }

  override def run(): Unit = {
    breakable {
      while (!stopped) {
        try {
          if (logInterval > 0) {
            Thread.sleep(logInterval)
          }
          if (writerQueue.drainTo(drained) == 0) {
            drained.add(writerQueue.take())
          }
        } catch {
          case t: InterruptedException =>
        }
        val drainedScala = drained.asScala
        drainedScala
          .filter(_.isLeft)
          .map(_.left.get)
          .foreach(x => writer.writeLogRecord(x))
        writer.flush()
        drainedScala.filter(_.isRight).foreach(x => networkCommunicationActor ! x.right.get)
        drained.clear()
      }
    }
    writer.close()
    gracefullyStopped.complete()
  }

}