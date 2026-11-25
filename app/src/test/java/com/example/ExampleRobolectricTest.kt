package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ParsedIncomingFrame
import com.example.data.ProtocolFrameParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("INACAP Barrera IoT", appName)
  }

  @Test
  fun `parse telemetry ack err and corrupt frames safely`() {
    val tel = ProtocolFrameParser.parseLine(
      """{"type":"TELEMETRY","dist":8,"estado":"OCUPADO","barrera":0,"modo":"AUTO","plazasLibres":11,"capacidadTotal":12,"crc":"OK-4B1E","buffered":true,"seq":7,"age_s":30,"uptime_s":90}"""
    )
    assertTrue(tel is ParsedIncomingFrame.Telemetry)
    val t = (tel as ParsedIncomingFrame.Telemetry).frame
    assertEquals(8, t.dist)
    assertEquals("OCUPADO", t.estado)
    assertEquals(0, t.barrera)
    assertEquals(11, t.plazasLibres)
    assertTrue(t.buffered)
    assertEquals(7L, t.seq)
    assertEquals(30L, t.ageSec)

    val ack = ProtocolFrameParser.parseLine("""{"status":"ACK","cmd":"SET_BARRERA","reqId":"A1","msg":"OK"}""")
    assertTrue(ack is ParsedIncomingFrame.Ack)
    assertEquals("SET_BARRERA", (ack as ParsedIncomingFrame.Ack).frame.cmd)

    val err = ProtocolFrameParser.parseLine("""{"status":"ERR","code":"OUT_OF_RANGE","reqId":"B2","msg":"Fuera"}""")
    assertTrue(err is ParsedIncomingFrame.Err)
    assertEquals("OUT_OF_RANGE", (err as ParsedIncomingFrame.Err).frame.code)

    val corrupt = ProtocolFrameParser.parseLine("""{"type":"TELEMETRY","dist":15""")
    assertTrue(corrupt is ParsedIncomingFrame.Corrupt)
  }
}
