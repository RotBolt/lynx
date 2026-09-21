import Foundation
import SQLite3
import UIKit
import DummyShared

final class AppDelegate: UIResponder, UIApplicationDelegate {
    private static let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
    var window: UIWindow?
    private var timer: Timer?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let label = UILabel(frame: UIScreen.main.bounds)
        label.textAlignment = .center
        label.numberOfLines = 0
        label.text = "Lynx Dummy App\nHTTP/1.1 · HTTP/2 · WebSocket"
        let controller = UIViewController()
        controller.view.backgroundColor = .systemBackground
        controller.view.addSubview(label)
        label.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = controller
        window?.makeKeyAndVisible()

        // Touch the shared KMP framework so the iOS fixture is built against
        // the same common module as the Android target.
        _ = FixtureMetadata.shared.name()
        runScenario()
        timer = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { [weak self] _ in
            self?.runScenario()
        }
        return true
    }

    private func runScenario() {
        let http1 = URL(string: "http://httpbin.org/get")!
        let http2 = URL(string: "https://jsonplaceholder.typicode.com/todos/1")!
        request(http1, kind: "HTTP_1_1")
        request(http2, kind: "HTTP_2")
        let task = URLSession.shared.webSocketTask(with: URL(string: "wss://ws.postman-echo.com/raw")!)
        task.resume()
        task.send(.string("lynx-dummy-ping")) { [weak self] error in
            if let error {
                self?.save(kind: "WEBSOCKET", status: nil, body: nil, error: error.localizedDescription)
                return
            }
            task.receive { [weak self] result in
                switch result {
                case .success(let message):
                    if case .string(let text) = message {
                        self?.save(kind: "WEBSOCKET", status: 101, body: text, error: nil)
                    }
                case .failure(let error):
                    self?.save(kind: "WEBSOCKET", status: nil, body: nil, error: error.localizedDescription)
                }
                task.cancel(with: .normalClosure, reason: nil)
            }
        }
    }

    private func request(_ url: URL, kind: String) {
        URLSession.shared.dataTask(with: url) { [weak self] data, response, error in
            self?.save(
                kind: kind,
                status: (response as? HTTPURLResponse)?.statusCode,
                body: data.flatMap { String(data: $0, encoding: .utf8) },
                error: error?.localizedDescription
            )
        }.resume()
    }

    private func save(kind: String, status: Int?, body: String?, error: String?) {
        let path = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("dummyapp.db").path
        var db: OpaquePointer?
        guard sqlite3_open(path, &db) == SQLITE_OK else { return }
        defer { sqlite3_close(db) }
        sqlite3_exec(db, """
            CREATE TABLE IF NOT EXISTS network_events (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              transport TEXT NOT NULL,
              method TEXT NOT NULL,
              url TEXT NOT NULL,
              status INTEGER,
              request_body TEXT,
              response_body TEXT,
              error TEXT,
              started_at INTEGER NOT NULL,
              completed_at INTEGER NOT NULL
            )
            """, nil, nil, nil)
        let sql = "INSERT INTO network_events (transport, method, url, status, response_body, error, started_at, completed_at) VALUES (?, 'GET', ?, ?, ?, ?, ?, ?)"
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(statement) }
        sqlite3_bind_text(statement, 1, kind, -1, Self.sqliteTransient)
        sqlite3_bind_text(statement, 2, "fixture", -1, Self.sqliteTransient)
        if let status { sqlite3_bind_int(statement, 3, Int32(status)) } else { sqlite3_bind_null(statement, 3) }
        if let body { sqlite3_bind_text(statement, 4, body, -1, Self.sqliteTransient) } else { sqlite3_bind_null(statement, 4) }
        if let error { sqlite3_bind_text(statement, 5, error, -1, Self.sqliteTransient) } else { sqlite3_bind_null(statement, 5) }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        sqlite3_bind_int64(statement, 6, now)
        sqlite3_bind_int64(statement, 7, now)
        sqlite3_step(statement)
    }
}

UIApplicationMain(CommandLine.argc, CommandLine.unsafeArgv, nil, NSStringFromClass(AppDelegate.self))
