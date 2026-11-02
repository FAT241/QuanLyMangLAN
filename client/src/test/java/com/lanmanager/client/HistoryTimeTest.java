package com.lanmanager.client;

import static org.junit.jupiter.api.Assertions.*;

import com.lanmanager.client.collector.BrowserHistoryReader;
import org.junit.jupiter.api.Test;

class HistoryTimeTest {
  @Test
  void chromeEpoch() {
    assertEquals(0, BrowserHistoryReader.epochMillis(11644473600000000L));
    assertEquals(
        1700000000000L,
        BrowserHistoryReader.epochMillis((1700000000000L + 11644473600000L) * 1000));
  }
}
