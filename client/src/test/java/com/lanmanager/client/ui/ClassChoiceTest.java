package com.lanmanager.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.lanmanager.common.protocol.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ClassChoiceTest {
  @Test
  void studentResponseWithoutEndedAtStillShowsOpenClasses() {
    JsonObject response =
        JsonCodec.parse(
            """
            {"classes":[
              {"id":12,"name":"Thực hành mạng","type":"CLASS","started_at":1000},
              {"id":13,"name":"Kiểm tra giữa kỳ","type":"EXAM","started_at":2000}
            ]}
            """
                .getBytes(StandardCharsets.UTF_8));
    var choices = ClassChoice.openClasses(response.getAsJsonArray("classes"));
    assertEquals(2, choices.size());
    assertEquals(new ClassChoice(12, "Thực hành mạng", false), choices.get(0));
    assertEquals(new ClassChoice(13, "Kiểm tra giữa kỳ", true), choices.get(1));
  }

  @Test
  void explicitNullMeansOpenAndEndedClassesAreNotOffered() {
    JsonArray rows = new JsonArray();
    rows.add(JsonCodec.object("id", 1, "name", "Buổi đang mở", "type", "CLASS", "ended_at", null));
    rows.add(JsonCodec.object("id", 2, "name", "Buổi đã đóng", "type", "EXAM", "ended_at", 3000));
    assertEquals(
        java.util.List.of(new ClassChoice(1, "Buổi đang mở", false)),
        ClassChoice.openClasses(rows));
    assertTrue(ClassChoice.openClasses(new JsonArray()).isEmpty());
  }

  @Test
  void incompleteClassDataHasAnUnderstandableErrorInsteadOfNullPointerException() {
    JsonArray rows = new JsonArray();
    rows.add(JsonCodec.object("id", 1, "type", "CLASS"));
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> ClassChoice.openClasses(rows));
    assertTrue(error.getMessage().contains("Dữ liệu buổi học chưa đầy đủ"));
  }
}
