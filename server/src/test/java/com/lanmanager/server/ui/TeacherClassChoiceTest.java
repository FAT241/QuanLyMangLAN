package com.lanmanager.server.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.lanmanager.common.protocol.json.JsonCodec;
import org.junit.jupiter.api.Test;

class TeacherClassChoiceTest {
  private JsonArray classes() {
    JsonArray rows = new JsonArray();
    rows.add(JsonCodec.object("id", 1, "name", "Buổi cũ", "type", "CLASS", "ended_at", 1000));
    rows.add(JsonCodec.object("id", 2, "name", "Buổi đang mở", "type", "EXAM", "ended_at", null));
    return rows;
  }

  @Test
  void defaultListOnlyShowsOpenClasses() {
    var choices = TeacherClassChoice.visibleClasses(classes(), false);
    assertEquals(1, choices.size());
    assertEquals(2, choices.get(0).id());
    assertTrue(choices.get(0).exam());
  }

  @Test
  void includingEndedClassesAllowsHistoryToBeSelectedAgain() {
    var choices = TeacherClassChoice.visibleClasses(classes(), true);
    assertEquals(2, choices.size());
    assertEquals(1, choices.get(0).id());
    assertTrue(choices.get(0).label().contains("đã kết thúc"));
    assertTrue(TeacherClassChoice.visibleClasses(new JsonArray(), true).isEmpty());
  }
}
