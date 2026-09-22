import Foundation
import SQLite3
import UIKit
import SampleShared

final class AppDelegate: UIResponder, UIApplicationDelegate {
    private static let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
    var window: UIWindow?
    private var webSocketTask: URLSessionWebSocketTask?
    private var statusLabel: UILabel?
    private var websocketStartButton: UIButton?
    private var websocketCloseButton: UIButton?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let controller = UIViewController()
        controller.view.backgroundColor = .systemBackground
        let label = UILabel()
        label.textAlignment = .center
        label.numberOfLines = 0
        label.text = "Lynx Sample App\nNo requests run automatically"
        statusLabel = label

        let http1 = button("HTTP/1.1 Call", #selector(callHttp1))
        let http2 = button("HTTP/2 Call", #selector(callHttp2))
        let wsStart = button("WebSocket Start", #selector(startWebSocket))
        let wsClose = button("WebSocket Close", #selector(closeWebSocket))
        wsClose.isEnabled = false
        websocketStartButton = wsStart
        websocketCloseButton = wsClose

        let stack = UIStackView(arrangedSubviews: [label, http1, http2, wsStart, wsClose])
        stack.axis = .vertical
        stack.spacing = 18
        stack.translatesAutoresizingMaskIntoConstraints = false
        controller.view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: controller.view.safeAreaLayoutGuide.trailingAnchor, constant: -24),
            stack.centerYAnchor.constraint(equalTo: controller.view.centerYAnchor),
        ])
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = controller
        window?.makeKeyAndVisible()

        // Link the sample app against the same shared KMP module as Android.
        _ = SampleMetadata.shared.name()
        return true
    }

    @objc private func callHttp1() {
        request(URL(string: "http://httpbin.org/get?source=lynx-sample-http1")!, kind: "HTTP_1_1")
    }

    @objc private func callHttp2() {
        request(URL(string: "https://jsonplaceholder.typicode.com/todos/1?source=lynx-sample-http2")!, kind: "HTTP_2")
    }

    @objc private func startWebSocket() {
        guard webSocketTask == nil else { return }
        let websocket = URL(string: "wss://ws.postman-echo.com/raw")!
        let task = URLSession.shared.webSocketTask(with: websocket)
        webSocketTask = task
        websocketStartButton?.isEnabled = false
        websocketCloseButton?.isEnabled = true
        task.resume()
        task.send(.string("lynx-sample-ping")) { [weak self] error in
            if let error {
                self?.save(kind: "WEBSOCKET", url: websocket.absoluteString, status: nil, body: nil, error: error.localizedDescription)
                DispatchQueue.main.async { self?.finishWebSocket(error: error.localizedDescription) }
                return
            }
            task.receive { [weak self] result in
                switch result {
                case .success(let message):
                    if case .string(let text) = message {
                        self?.save(kind: "WEBSOCKET", url: websocket.absoluteString, status: 101, body: text, error: nil)
                        DispatchQueue.main.async { self?.statusLabel?.text = "WebSocket: echoed \(text)" }
                    }
                case .failure(let error):
                    self?.save(kind: "WEBSOCKET", url: websocket.absoluteString, status: nil, body: nil, error: error.localizedDescription)
                    DispatchQueue.main.async { self?.finishWebSocket(error: error.localizedDescription) }
                }
            }
        }
    }

    @objc private func closeWebSocket() {
        webSocketTask?.cancel(with: .normalClosure, reason: nil)
        webSocketTask = nil
        websocketStartButton?.isEnabled = true
        websocketCloseButton?.isEnabled = false
        statusLabel?.text = "WebSocket closed"
    }

    private func finishWebSocket(error: String) {
        statusLabel?.text = "WebSocket failed: \(error)"
        webSocketTask = nil
        websocketStartButton?.isEnabled = true
        websocketCloseButton?.isEnabled = false
    }

    private func request(_ url: URL, kind: String) {
        statusLabel?.text = "\(kind): request in progress…"
        URLSession.shared.dataTask(with: url) { [weak self] data, response, error in
            self?.save(
                kind: kind,
                url: url.absoluteString,
                status: (response as? HTTPURLResponse)?.statusCode,
                body: data.flatMap { String(data: $0, encoding: .utf8) },
                error: error?.localizedDescription
            )
            DispatchQueue.main.async {
                if let error {
                    self?.statusLabel?.text = "\(kind): failed — \(error.localizedDescription)"
                } else {
                    let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                    let body = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
                    self?.statusLabel?.text = "\(kind): HTTP \(status)\n\(body)"
                }
            }
        }.resume()
    }

    private func button(_ title: String, _ action: Selector) -> UIButton {
        let result = UIButton(type: .system)
        result.setTitle(title, for: .normal)
        result.titleLabel?.font = .systemFont(ofSize: 18, weight: .medium)
        result.addTarget(self, action: action, for: .touchUpInside)
        return result
    }

    private func save(kind: String, url: String, status: Int?, body: String?, error: String?) {
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
        sqlite3_bind_text(statement, 2, url, -1, Self.sqliteTransient)
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
