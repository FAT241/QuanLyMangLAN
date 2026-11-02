package com.lanmanager.server.net;

import com.google.gson.*;
import com.lanmanager.common.protocol.http.HttpMessage;
import com.lanmanager.common.protocol.json.JsonCodec;
import com.lanmanager.server.account.AccountService;
import com.lanmanager.server.classroom.ClassroomService;
import com.lanmanager.server.service.MachineRegistry;
import java.util.*;

/** Định tuyến nghiệp vụ, không tự đọc socket hoặc viết SQL. */
public final class ClassroomApi {
  public record Response(int status, JsonObject body, Map<String, String> headers) {
    public Response(int status, JsonObject body) {
      this(status, body, Map.of());
    }
  }

  private static final Set<String> PATHS =
      Set.of(
          "exams/start",
          "exams/submit",
          "history/page",
          "students/page",
          "rules/add",
          "rules/enabled",
          "accounts/list",
          "accounts/enabled",
          "accounts/password",
          "login",
          "me",
          "students/status",
          "monitor/control",
          "evidence/save",
          "evidence/read",
          "logout",
          "accounts/create",
          "classes/list",
          "classes/start",
          "classes/end",
          "classes/delete",
          "students/join",
          "students/leave",
          "activities/record",
          "violations/flag",
          "violations/review",
          "errors/record",
          "classes/report",
          "monitor/request",
          "monitor/result");
  private final AccountService accounts;
  private final ClassroomService classroom;
  private final MachineRegistry machines;
  private final com.lanmanager.server.classroom.MonitoringService monitoring;
  private final com.lanmanager.server.classroom.ClassroomReportService reports;
  private final com.lanmanager.server.classroom.ExamService exams;
  private final com.lanmanager.server.classroom.HistoryQueryService history;
  private final com.lanmanager.server.classroom.AlertRuleService rules;

  public ClassroomApi(
      AccountService accounts,
      ClassroomService classroom,
      MachineRegistry machines,
      com.lanmanager.server.service.RequestService requests,
      com.lanmanager.server.storage.ClassroomDatabase database,
      java.nio.file.Path evidenceDirectory) {
    this.accounts = accounts;
    this.classroom = classroom;
    this.machines = machines;
    reports = new com.lanmanager.server.classroom.ClassroomReportService(database, classroom);
    exams = new com.lanmanager.server.classroom.ExamService(database, classroom);
    history = new com.lanmanager.server.classroom.HistoryQueryService(database, classroom);
    rules = new com.lanmanager.server.classroom.AlertRuleService(database, classroom);
    monitoring =
        new com.lanmanager.server.classroom.MonitoringService(
            classroom, machines, requests, database, evidenceDirectory);
  }

  public boolean handles(String path) {
    return path.startsWith("/api/v1/classroom/");
  }

  public void cleanup() {
    monitoring.cleanup();
  }

  public Response handle(String method, String path, HttpMessage request, String ip)
      throws Exception {
    String route = path.substring("/api/v1/classroom/".length());
    if (!PATHS.contains(route)) return new Response(404, error("NOT_FOUND", "Không có đường dẫn"));
    if (!method.equals("POST"))
      return new Response(405, error("METHOD_NOT_ALLOWED", "Dùng POST"), Map.of("Allow", "POST"));
    if (!request
        .headers()
        .getOrDefault("content-type", "")
        .split(";", 2)[0]
        .equalsIgnoreCase("application/json"))
      return new Response(415, error("JSON_REQUIRED", "Cần application/json"));
    JsonObject body = request.json();
    if (route.equals("login"))
      return new Response(
          200,
          JsonCodec.object(
              "accessToken",
              accounts.login(
                  JsonCodec.requiredText(body, "username", 50),
                  JsonCodec.requiredText(body, "password", 200)),
              "expiresInSeconds",
              28800));
    String accessToken = request.headers().get("x-user-token");
    var user = accounts.authenticate(accessToken);
    long id;
    switch (route) {
      case "students/page" -> {
        return new Response(
            200,
            reports.studentsPage(
                user,
                number(body, "classId"),
                body.has("beforeId") ? number(body, "beforeId") : Long.MAX_VALUE));
      }
      case "accounts/list" -> {
        return new Response(200, JsonCodec.object("accounts", accounts.listAccounts(user)));
      }
      case "accounts/enabled" -> {
        accounts.setEnabled(
            user, number(body, "accountId"), JsonCodec.requiredLong(body, "enabled", 0, 1) == 1);
        return ok();
      }
      case "accounts/password" -> {
        accounts.resetPassword(
            user, number(body, "accountId"), JsonCodec.requiredText(body, "password", 200));
        return ok();
      }
      case "exams/start" -> {
        return new Response(200, exams.start(user, number(body, "studentSessionId")));
      }
      case "exams/submit" -> {
        return new Response(200, exams.submit(user, number(body, "studentSessionId")));
      }
      case "history/page" -> {
        return new Response(200, history.page(user, body));
      }
      case "rules/add" -> {
        return new Response(
            201,
            JsonCodec.object(
                "id",
                rules.add(
                    user,
                    number(body, "classId"),
                    JsonCodec.requiredText(body, "kind", 20),
                    JsonCodec.requiredText(body, "pattern", 200))));
      }
      case "rules/enabled" -> {
        rules.setEnabled(
            user, number(body, "ruleId"), JsonCodec.requiredLong(body, "enabled", 0, 1) == 1);
        return ok();
      }
      case "me" -> {
        return new Response(
            200,
            JsonCodec.object(
                "id",
                user.id(),
                "username",
                user.username(),
                "fullName",
                user.fullName(),
                "role",
                user.role()));
      }
      case "students/status" -> {
        return new Response(200, classroom.studentStatus(user));
      }
      case "evidence/save" -> {
        return new Response(
            201,
            JsonCodec.object(
                "id",
                monitoring.saveEvidence(
                    user,
                    number(body, "violationId"),
                    JsonCodec.requiredText(body, "requestId", 100))));
      }
      case "evidence/read" -> {
        return new Response(200, monitoring.readEvidence(user, number(body, "evidenceId")));
      }
      case "monitor/control" -> {
        if (!body.has("confirmed")
            || !body.get("confirmed").isJsonPrimitive()
            || !body.getAsJsonPrimitive("confirmed").isBoolean()
            || !body.get("confirmed").getAsBoolean())
          throw new IllegalArgumentException("Cần xác nhận điều khiển");
        String type = JsonCodec.requiredText(body, "type", 50);
        if (!Set.of("LOCK_SCREEN", "TERMINATE_PROCESS").contains(type))
          throw new IllegalArgumentException("Lệnh điều khiển không hợp lệ");
        return new Response(
            201,
            JsonCodec.object(
                "requestId",
                monitoring.request(
                    user,
                    number(body, "studentSessionId"),
                    type,
                    body.getAsJsonObject("payload"))));
      }
      case "monitor/request" -> {
        if (!Set.of("GET_SCREENSHOT", "GET_PROCESSES", "GET_WEB_HISTORY")
            .contains(JsonCodec.requiredText(body, "type", 50)))
          throw new IllegalArgumentException("Lệnh theo dõi không hợp lệ");
        return new Response(
            201,
            JsonCodec.object(
                "requestId",
                monitoring.request(
                    user,
                    number(body, "studentSessionId"),
                    JsonCodec.requiredText(body, "type", 50),
                    body.has("payload") ? body.getAsJsonObject("payload") : JsonCodec.object())));
      }
      case "monitor/result" -> {
        return new Response(
            200, monitoring.result(user, JsonCodec.requiredText(body, "requestId", 100)));
      }
      case "logout" -> {
        classroom.signOutStudent(user);
        accounts.logout(accessToken);
        return new Response(200, JsonCodec.object("ok", true));
      }
      case "accounts/create" -> {
        AccountService.requireTeacher(user);
        id =
            accounts.createAccount(
                JsonCodec.requiredText(body, "username", 50),
                JsonCodec.requiredText(body, "password", 200),
                JsonCodec.requiredText(body, "fullName", 200),
                JsonCodec.requiredText(body, "role", 10));
      }
      case "classes/list" -> {
        return new Response(200, JsonCodec.object("classes", classroom.listClasses(user)));
      }
      case "classes/start" ->
          id =
              classroom.startClass(
                  user,
                  JsonCodec.requiredText(body, "name", 200),
                  JsonCodec.requiredText(body, "type", 10));
      case "classes/end" -> {
        classroom.endClass(user, number(body, "classId"));
        return ok();
      }
      case "classes/delete" -> {
        AccountService.requireTeacher(user);
        if (!body.has("confirmed")
            || !body.get("confirmed").isJsonPrimitive()
            || !body.getAsJsonPrimitive("confirmed").isBoolean()
            || !body.get("confirmed").getAsBoolean())
          throw new IllegalArgumentException("Cần xác nhận xóa buổi");
        classroom.deleteClass(user, number(body, "classId"));
        return ok();
      }
      case "students/join" ->
          id =
              classroom.joinClass(
                  user,
                  number(body, "classId"),
                  machines.authenticate(request.headers().get("x-lan-session"), ip));
      case "students/leave" -> {
        classroom.leaveClass(user, number(body, "studentSessionId"));
        return ok();
      }
      case "activities/record" ->
          id = classroom.recordActivity(user, number(body, "studentSessionId"), body);
      case "violations/flag" ->
          id =
              classroom.flagViolation(
                  user,
                  number(body, "studentSessionId"),
                  JsonCodec.requiredText(body, "details", 8000),
                  JsonCodec.requiredLong(body, "occurredAt", 0, Long.MAX_VALUE));
      case "violations/review" -> {
        classroom.reviewViolation(
            user,
            number(body, "violationId"),
            JsonCodec.requiredText(body, "status", 20),
            (int) JsonCodec.requiredLong(body, "points", 0, 100),
            JsonCodec.requiredText(body, "note", 2000));
        return ok();
      }
      case "errors/record" -> {
        if (!body.has("resolved")
            || !body.get("resolved").isJsonPrimitive()
            || !body.getAsJsonPrimitive("resolved").isBoolean())
          throw new IllegalArgumentException("Thiếu resolved");
        classroom.recordError(
            user,
            number(body, "studentSessionId"),
            JsonCodec.requiredText(body, "code", 60),
            JsonCodec.requiredText(body, "message", 2000),
            body.get("resolved").getAsBoolean());
        return ok();
      }
      case "classes/report" -> {
        return new Response(200, classroom.report(user, number(body, "classId")));
      }
      default -> throw new IllegalStateException("Chưa xử lý đường dẫn");
    }
    return new Response(201, JsonCodec.object("id", id));
  }

  private static long number(JsonObject body, String key) {
    return JsonCodec.requiredLong(body, key, 1, Long.MAX_VALUE);
  }

  private static Response ok() {
    return new Response(200, JsonCodec.object("ok", true));
  }

  private static JsonObject error(String code, String message) {
    return JsonCodec.object("error", JsonCodec.object("code", code, "message", message));
  }
}
