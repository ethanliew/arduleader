package com.geeksville.flight

import com.geeksville.mavlink.HeartbeatMonitor
import org.mavlink.messages.ardupilotmega._
import org.mavlink.messages.MAVLinkMessage
import com.geeksville.akka.MockAkka
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.collection.mutable.ArrayBuffer
import com.geeksville.util.Throttled
import com.geeksville.akka.EventStream
import org.mavlink.messages.MAV_TYPE
import com.geeksville.akka.Cancellable
import org.mavlink.messages.MAV_DATA_STREAM
import org.mavlink.messages.MAV_MISSION_RESULT
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.HashSet
import com.geeksville.mavlink.MavlinkEventBus
import com.geeksville.mavlink.MavlinkStream
import com.geeksville.util.ThrottledActor
import com.geeksville.mavlink.MavlinkConstants

/**
 * An endpoint client that talks to a vehicle (adds message retries etc...)
 */
class VehicleClient extends HeartbeatMonitor with VehicleSimulator with HeartbeatSender with MavlinkConstants {

  case class RetryExpired(ctx: RetryContext)

  private val retries = HashSet[RetryContext]()

  override def systemId = 253 // We always claim to be a ground controller (FIXME, find a better way to pick a number)

  override def onReceive = mReceive.orElse(super.onReceive)

  private def mReceive: Receiver = {

    case RetryExpired(ctx) =>
      ctx.doRetry()
  }

  override def postStop() {
    // Close off any retry timers
    retries.toList.foreach(_.close())

    super.postStop()
  }

  case class RetryContext(val retryPacket: MAVLinkMessage, val expectedResponse: Class[_]) {
    val numRetries = 10
    var retriesLeft = numRetries
    val retryInterval = 1000
    var retryTimer: Option[Cancellable] = None

    sendPacket()

    def close() {
      //log.debug("Closing " + this)
      retryTimer.foreach(_.cancel())
      retries.remove(this)
    }

    /**
     * Return true if we handled it
     */
    def handleRetryReply[T <: MAVLinkMessage](reply: T) = {
      if (reply.getClass == expectedResponse) {
        // Success!
        log.debug("Success for " + this)
        close()
        true
      } else
        false
    }

    /**
     * Subclasses can do something more elaborate if they want
     */
    protected def handleFailure() {}

    private def sendPacket() {
      retriesLeft -= 1
      sendMavlink(retryPacket)
      retryTimer = Some(MockAkka.scheduler.scheduleOnce(retryInterval milliseconds, VehicleClient.this, RetryExpired(this)))
    }

    def doRetry() {
      if (retriesLeft > 0) {
        log.debug(System.currentTimeMillis + " Retry expired on " + this + " trying again...")
        sendPacket()
      } else {
        log.error("No more retries, giving up: " + retryPacket)
        handleFailure()
        close()
      }
    }
  }

  /**
   * Send a packet that expects a certain packet type in response, if the response doesn't arrive, then retry
   */
  protected def sendWithRetry(msg: MAVLinkMessage, expected: Class[_]) {
    retries.add(RetryContext(msg, expected))
  }

  /**
   * Send a packet that expects a certain packet type in response, if the response doesn't arrive, then retry
   */
  protected def sendWithRetry(msg: MAVLinkMessage, expected: Class[_], onFailure: () => Unit) {
    val c = new RetryContext(msg, expected) {
      override def handleFailure() { onFailure() }
    }
    retries.add(c)
  }

  /**
   * Check to see if this satisfies our retry reply requirement, if it does and it isn't a dup return the message, else None
   */
  protected def checkRetryReply[T <: MAVLinkMessage](reply: T): Option[T] = {
    val numHandled = retries.count(_.handleRetryReply(reply))
    if (numHandled > 0) {
      Some(reply)
    } else
      None
  }

  /**
   * Turn streaming on or off (and if USB is crummy on this machine, turn it on real slow)
   */
  protected[flight] def setStreamEnable(enabled: Boolean) {

    log.info("Setting stream enable: " + enabled)

    val defaultFreq = 1
    val interestingStreams = Seq(MAV_DATA_STREAM.MAV_DATA_STREAM_RAW_SENSORS -> defaultFreq,
      MAV_DATA_STREAM.MAV_DATA_STREAM_EXTENDED_STATUS -> defaultFreq,
      MAV_DATA_STREAM.MAV_DATA_STREAM_RC_CHANNELS -> 2,
      MAV_DATA_STREAM.MAV_DATA_STREAM_POSITION -> defaultFreq,
      MAV_DATA_STREAM.MAV_DATA_STREAM_EXTRA1 -> 10, // faster AHRS display use a bigger #
      MAV_DATA_STREAM.MAV_DATA_STREAM_EXTRA2 -> defaultFreq,
      MAV_DATA_STREAM.MAV_DATA_STREAM_EXTRA3 -> defaultFreq)

    interestingStreams.foreach {
      case (id, freqHz) =>
        val f = if (VehicleClient.isUsbBusted) 1 else freqHz
        sendMavlink(requestDataStream(id, f, enabled))
        sendMavlink(requestDataStream(id, f, enabled))
    }
  }
}

object VehicleClient {
  /**
   * Some android clients don't have working USB and therefore have very limited bandwidth.  This nasty global allows the android builds to change 'common' behavior.
   */
  var isUsbBusted = false
}
