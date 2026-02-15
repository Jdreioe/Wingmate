#include <QDebug>
#include <QGuiApplication>
#include <QProcess>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QThread>
#include <QUrl>

int main(int argc, char *argv[]) {
  QGuiApplication app(argc, argv);

  app.setApplicationName("Wingmate");
  app.setOrganizationName("io.github.jdreioe");
  app.setApplicationDisplayName("Wingmate");

  // Start Kotlin bridge server in a separate process
  QProcess *bridgeProcess = new QProcess(&app);

  // Use the fat JAR that contains all dependencies
  QString fatJarPath = QString(SHARED_JAR_PATH)
                           .replace("shared/build/libs/shared-jvm.jar",
                                    "linuxApp/build/libs/linuxApp-all.jar");

  // Allow override via environment variable
  QString envJar = QString::fromUtf8(qgetenv("WINGMATE_LINUXAPP_JAR"));
  if (!envJar.isEmpty()) {
    fatJarPath = envJar;
  }

  // Start the bridge server using the fat JAR
  QStringList javaArgs;
  javaArgs << "-jar" << fatJarPath;

  bridgeProcess->setProcessChannelMode(QProcess::ForwardedChannels);
  bridgeProcess->start("java", javaArgs);
  bridgeProcess->waitForStarted(3000);

  if (bridgeProcess->state() != QProcess::Running) {
    qCritical() << "Failed to start Kotlin bridge server";
    qCritical() << "Error:" << bridgeProcess->errorString();
    return -1;
  }

  qInfo() << "Kotlin bridge server started";

  // Wait a moment for server to be ready
  QThread::sleep(1);

  QQmlApplicationEngine engine;

  // Expose the API URL to QML
  engine.rootContext()->setContextProperty("apiUrl", "http://localhost:8765");

  // Load main QML file
  const QUrl url(QStringLiteral("qrc:/main.qml"));
  QObject::connect(
      &engine, &QQmlApplicationEngine::objectCreated, &app,
      [url](QObject *obj, const QUrl &objUrl) {
        if (!obj && url == objUrl) {
          qCritical() << "Failed to load QML file:" << url;
          QCoreApplication::exit(-1);
        }
      },
      Qt::QueuedConnection);

  engine.load(url);

  if (engine.rootObjects().isEmpty()) {
    qCritical() << "No root objects created";
    bridgeProcess->kill();
    return -1;
  }

  qInfo() << "Wingmate KDE started successfully";

  // Cleanup signal connection
  QObject::connect(&app, &QCoreApplication::aboutToQuit, [bridgeProcess]() {
    if (bridgeProcess) {
      qInfo() << "Stopping Kotlin bridge server...";
      bridgeProcess->terminate();
      if (!bridgeProcess->waitForFinished(2000)) {
        bridgeProcess->kill();
      }
    }
  });

  int result = app.exec();

  delete bridgeProcess;
  return result;
}
