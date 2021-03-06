package com.geeksville.andropilot.gui

import android.os.Bundle
import android.widget.ArrayAdapter
import scala.collection.JavaConverters._
import com.geeksville.util.ThreadTools._
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.ViewGroup
import com.ridemission.scandroid.AndroidUtil._
import com.geeksville.andropilot.TypedResource._
import com.geeksville.andropilot.TR
import android.widget.ArrayAdapter
import com.geeksville.flight._
import java.util.LinkedList
import com.geeksville.andropilot.R
import android.view.View
import com.ridemission.scandroid.ObservableAdapter
import com.geeksville.flight.StatusText
import android.widget.BaseAdapter

class OverviewFragment extends LayoutFragment(R.layout.vehicle_overview) with AndroServiceFragment {

  private def latView = getView.findView(TR.latitude)
  private def lonView = getView.findView(TR.longitude)
  private def altView = getView.findView(TR.altitude)
  private def airspeedView = getView.findView(TR.airspeed)
  private def groundspeedView = getView.findView(TR.groundspeed)
  private def numSatView = getView.findView(TR.gps_numsats)
  private def rssiLocalView = getView.findView(TR.rssi_local)
  private def batteryView = getView.findView(TR.battery_volt)

  override def onVehicleReceive = {
    case l: Location =>
      //debug("Handling location: " + l)
      handler.post { () =>
        if (getView != null) {
          myVehicle.foreach { v =>
            val degSymbol = "\u00B0"
            latView.setText("%.4f".format(l.lat) + degSymbol)
            lonView.setText("%.4f".format(l.lon) + degSymbol)
            altView.setText("%.1f".format(v.bestAltitude) + " m")
            v.vfrHud.foreach { hud =>
              airspeedView.setText("%.1f".format(hud.airspeed) + " m/s")
              groundspeedView.setText("%.1f".format(hud.groundspeed) + " m/s")
            }
            val numSats = v.numSats.getOrElse("?")
            val hdop = v.hdop.getOrElse("?")
            numSatView.setText("%s / %s m".format(numSats, hdop))
          }
        }
      }

    case MsgSysStatusChanged =>
      handler.post { () =>
        if (getView != null) {
          myVehicle.foreach { v =>
            v.radio.foreach { n =>
              val local = n.rssi - n.noise
              val rem = n.remrssi - n.remnoise

              rssiLocalView.setText(local.toString + "/" + rem.toString)
            }
            v.batteryVoltage.foreach { n =>
              val socStr = v.batteryPercent.map { pct => " (%d%%)".format((pct * 100).toInt) }.getOrElse("")
              batteryView.setText(n.toString + "V " + socStr)
            }
          }
        }
      }
  }

}
