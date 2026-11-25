package com.example

import com.example.data.CommandFrame
import com.example.data.CommandValidationResult
import com.example.data.ProtocolFrameParser
import com.example.data.TelemetryFrame
import com.example.network.WirelessConnectionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias locales ES3 (TI3042) para validación de protocolo,
 * parser defensivo de tramas JSON, redacción de tokens y backoff exponencial.
 */
class ExampleUnitTest {

  @Test
  fun commandValidation_acceptsValidAndRejectsOutOfRangeOrUnknown() {
    assertTrue(CommandFrame.validate("SET_BARRERA", 90, "MANUAL", 12) is CommandValidationResult.Valid)
    assertTrue(CommandFrame.validate("SET_BARRERA", 0, "MANUAL", 12) is CommandValidationResult.Valid)
    assertTrue(CommandFrame.validate("SET_BARRERA", 180, "MANUAL", 12) is CommandValidationResult.Invalid)
    assertTrue(CommandFrame.validate("SET_UMBRAL", 25, "AUTO", 12) is CommandValidationResult.Valid)
    assertTrue(CommandFrame.validate("SET_UMBRAL", 300, "AUTO", 12) is CommandValidationResult.Invalid)
    assertTrue(CommandFrame.validate("FLUSH_BUFFER", -1, "AUTO", 12) is CommandValidationResult.Valid)
    assertTrue(CommandFrame.validate("CMD_INEXISTENTE", 0, "AUTO", 12) is CommandValidationResult.Invalid)
  }

  @Test
  fun exponentialBackoff_follows2sTo30sCap() {
    assertEquals(2_000L, WirelessConnectionManager.calculateExponentialBackoffMs(1))
    assertEquals(4_000L, WirelessConnectionManager.calculateExponentialBackoffMs(2))
    assertEquals(8_000L, WirelessConnectionManager.calculateExponentialBackoffMs(3))
    assertEquals(16_000L, WirelessConnectionManager.calculateExponentialBackoffMs(4))
    assertEquals(30_000L, WirelessConnectionManager.calculateExponentialBackoffMs(5))
    assertEquals(30_000L, WirelessConnectionManager.calculateExponentialBackoffMs(8))
  }

  @Test
  fun telemetryCrc_isDeterministic() {
    val crc1 = TelemetryFrame.computeTelemetryCrc(8, "OCUPADO", 0, "AUTO", 11, 12)
    val crc2 = TelemetryFrame.computeTelemetryCrc(8, "OCUPADO", 0, "AUTO", 11, 12)
    assertEquals(crc1, crc2)
    assertTrue(crc1.startsWith("OK-"))
  }

  @Test
  fun tokenRedaction_masksSensitiveValues() {
    val raw = "{\"type\":\"CMD\",\"cmd\":\"SET_BARRERA\",\"val\":90,\"token\":\"INA-1234567890AB\"}"
    val redacted = ProtocolFrameParser.redactSensitiveFields(raw)
    assertTrue(redacted.contains("\"token\":\"INA-****\""))
    assertTrue(!redacted.contains("1234567890AB"))
  }
}
