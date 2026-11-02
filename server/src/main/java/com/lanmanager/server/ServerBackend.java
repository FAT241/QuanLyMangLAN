package com.lanmanager.server;

import com.lanmanager.common.config.ApplicationConfig;
import com.lanmanager.server.net.*;
import com.lanmanager.server.service.*;
import com.lanmanager.server.storage.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.slf4j.*;

/** Thành phần có vòng đời riêng để giao diện hoặc kiểm thử tái sử dụng. */
public final class ServerBackend implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(ServerBackend.class);
  public final MachineRegistry machines;
  public final RequestService requests = new RequestService();
  public final ConnectionEventRepository events;
  public final HttpServer http;
  public final UdpService udp;
  public final ClassroomDatabase classroomDatabase;
  public final com.lanmanager.server.account.AccountService accounts;
  public final com.lanmanager.server.classroom.ClassroomService classroom;
  private final ClassroomApi classroomApi;
  private final ExecutorService database =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "database-writer"));
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(2, r -> new Thread(r, "server-scheduler"));
  private boolean closed;
  private final com.lanmanager.server.classroom.ActivityMonitor activityMonitor;

  public ServerBackend(ApplicationConfig config) throws Exception {
    config.number("http.port", 9877, 0, 65535);
    config.number("udp.port", 9876, 0, 65535);
    config.number("offline.seconds", 10, 1, 60);
    config.number("heartbeat.seconds", 3, 1, 30);
    config.number("ping.seconds", 5, 1, 60);
    config.number("activity.seconds", 15, 5, 300);
    config.number("evidence.retention.days", 0, 0, 36500);
    classroomDatabase =
        new ClassroomDatabase(com.lanmanager.server.storage.DatabaseSettings.from(config));
    try {
      classroomDatabase.acquireServerLock();
    } catch (Exception e) {
      classroomDatabase.close();
      throw e;
    }
    events = new ConnectionEventRepository(classroomDatabase);
    accounts = new com.lanmanager.server.account.AccountService(classroomDatabase);
    classroom = new com.lanmanager.server.classroom.ClassroomService(classroomDatabase);
    machines =
        new MachineRegistry(
            System::currentTimeMillis,
            config.number("offline.seconds", 10, 1, 60) * 1000L,
            60000,
            m -> {
              long at = System.currentTimeMillis();
              LOG.info("Máy {}: {}", m.name(), m.online() ? "Đang kết nối" : "Mất kết nối");
              database.execute(
                  () -> {
                    try {
                      events.append(
                          m.id(), m.name(), m.ip(), m.online() ? "ONLINE" : "OFFLINE", at);
                    } catch (Exception e) {
                      LOG.error("Không lưu được lịch sử", e);
                    }
                  });
            });
    classroomApi =
        new ClassroomApi(
            accounts,
            classroom,
            machines,
            requests,
            classroomDatabase,
            config.resolvePath("evidence.path", "data/evidence"));
    HttpServer startedHttp = null;
    UdpService startedUdp;
    try {
      startedHttp =
          new HttpServer(
              config.number("http.port", 9877, 0, 65535),
              machines,
              requests,
              config.text("token", "lan-demo-token"),
              config.number("heartbeat.seconds", 3, 1, 30),
              classroomApi);
      startedUdp =
          new UdpService(config.number("udp.port", 9876, 0, 65535), startedHttp.port(), machines);
    } catch (Exception e) {
      if (startedHttp != null) startedHttp.close();
      database.shutdown();
      classroomDatabase.close();
      throw e;
    }
    http = startedHttp;
    udp = startedUdp;
    activityMonitor =
        new com.lanmanager.server.classroom.ActivityMonitor(classroomDatabase, machines, requests);
    var evidenceStore =
        new com.lanmanager.server.classroom.EvidenceService(
            classroomDatabase, classroom, config.resolvePath("evidence.path", "data/evidence"));
    int retentionDays = config.number("evidence.retention.days", 0, 0, 36500);
    scheduler.scheduleWithFixedDelay(
        () -> {
          try {
            evidenceStore.purgeExpired(retentionDays);
          } catch (Exception e) {
            LOG.error("Không dọn được ảnh hết hạn", e);
          }
        },
        1,
        3600,
        TimeUnit.SECONDS);
    scheduler.scheduleWithFixedDelay(
        activityMonitor::collect,
        5,
        config.number("activity.seconds", 15, 5, 300),
        TimeUnit.SECONDS);
    scheduler.scheduleAtFixedRate(
        () -> {
          try {
            machines.checkConnectionTimeouts();
            classroom.checkStudentConnections(machines);
            classroomApi.cleanup();
            requests.expirePendingRequests(machines);
            udp.expire();
          } catch (Exception e) {
            LOG.error("Lỗi kiểm tra định kỳ", e);
          }
        },
        1,
        1,
        TimeUnit.SECONDS);
    scheduler.scheduleAtFixedRate(
        udp::ping, 1, config.number("ping.seconds", 5, 1, 60), TimeUnit.SECONDS);
  }

  public synchronized void close() throws Exception {
    if (closed) return;
    closed = true;
    scheduler.shutdownNow();
    http.close();
    udp.close();
    requests.close();
    database.shutdown();
    database.awaitTermination(5, TimeUnit.SECONDS);
    events.close();
    classroomDatabase.close();
  }
}
