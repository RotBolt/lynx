#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

/*
 * Feasibility-only device-local relay. It accepts a proxy connection on guest
 * loopback, proves its inode belongs to the selected debuggable package, and
 * only then forwards raw bytes to the specified host proxy. Unknown ownership
 * is rejected here; production pass-through requires the later authenticated
 * metadata protocol so the host never MITMs an unknown connection.
 */

static volatile sig_atomic_t keep_running = 1;

static void stop(int signal_number) { (void)signal_number; keep_running = 0; }

static bool valid_package(const char *value) {
    if (!value || !*value) return false;
    for (const char *c = value; *c; c++) {
        if (!( (*c >= 'a' && *c <= 'z') || (*c >= 'A' && *c <= 'Z') ||
               (*c >= '0' && *c <= '9') || *c == '.' || *c == '_' )) return false;
    }
    return true;
}

static bool package_owns_inode(const char *package_name, pid_t pid, unsigned long long wanted) {
    char command[256];
    snprintf(command, sizeof(command), "/system/bin/run-as %s ls -l /proc/%d/fd 2>/dev/null", package_name, pid);
    FILE *listing = popen(command, "r");
    if (!listing) return false;
    char line[512]; bool found = false;
    while (fgets(line, sizeof(line), listing)) {
        const char *socket = strstr(line, "socket:[");
        unsigned long long inode = 0;
        if (socket && sscanf(socket, "socket:[%llu]", &inode) == 1 && inode == wanted) { found = true; break; }
    }
    (void)pclose(listing);
    return found;
}

static bool decode_ipv4_little_endian(const char *hex, char *output, size_t size) {
    if (strlen(hex) != 8) return false;
    unsigned long raw = strtoul(hex, NULL, 16);
    uint8_t bytes[4] = { (uint8_t)raw, (uint8_t)(raw >> 8), (uint8_t)(raw >> 16), (uint8_t)(raw >> 24) };
    return inet_ntop(AF_INET, bytes, output, size) != NULL;
}

static bool table_has_owned_loopback_socket(const char *path, bool ipv6_table, const char *package_name, pid_t pid, uint16_t relay_port) {
    FILE *table = fopen(path, "r");
    if (!table) return false;
    char line[768]; (void)fgets(line, sizeof(line), table);
    bool found = false;
    while (!found && fgets(line, sizeof(line), table)) {
        char local[65] = {0}, remote[65] = {0}, remote_host[INET_ADDRSTRLEN] = {0};
        unsigned remote_port = 0; unsigned long long inode = 0;
        int fields = sscanf(line, " %*u: %64[^:]:%*x %64[^:]:%x %*x %*s %*s %*s %*u %*u %llu", local, remote, &remote_port, &inode);
        (void)local;
        if (fields != 4 || remote_port != relay_port) continue;
        bool decoded = ipv6_table ? (strlen(remote) == 32 && decode_ipv4_little_endian(remote + 24, remote_host, sizeof(remote_host))) :
            decode_ipv4_little_endian(remote, remote_host, sizeof(remote_host));
        if (decoded &&
            strcmp(remote_host, "127.0.0.1") == 0 && package_owns_inode(package_name, pid, inode)) found = true;
    }
    fclose(table); return found;
}

static bool connection_is_owned(const char *package_name, pid_t pid, uint16_t relay_port) {
    return table_has_owned_loopback_socket("/proc/net/tcp", false, package_name, pid, relay_port) ||
        table_has_owned_loopback_socket("/proc/net/tcp6", true, package_name, pid, relay_port);
}

static int connect_upstream(const char *host, uint16_t port) {
    struct addrinfo hints = {0}, *results = NULL;
    hints.ai_socktype = SOCK_STREAM; hints.ai_family = AF_UNSPEC;
    char service[6]; snprintf(service, sizeof(service), "%u", port);
    if (getaddrinfo(host, service, &hints, &results) != 0) return -1;
    int upstream = -1;
    for (struct addrinfo *candidate = results; candidate; candidate = candidate->ai_next) {
        upstream = socket(candidate->ai_family, candidate->ai_socktype, candidate->ai_protocol);
        if (upstream >= 0 && connect(upstream, candidate->ai_addr, candidate->ai_addrlen) == 0) break;
        if (upstream >= 0) close(upstream);
        upstream = -1;
    }
    freeaddrinfo(results); return upstream;
}

static void pump(int left, int right) {
    struct pollfd fds[2] = {{left, POLLIN, 0}, {right, POLLIN, 0}};
    uint8_t buffer[16384];
    while (poll(fds, 2, -1) > 0) {
        for (int i = 0; i < 2; i++) {
            if (!(fds[i].revents & (POLLIN | POLLHUP))) continue;
            ssize_t count = read(fds[i].fd, buffer, sizeof(buffer));
            if (count <= 0) return;
            int destination = i == 0 ? right : left;
            for (ssize_t written = 0; written < count;) {
                ssize_t result = write(destination, buffer + written, (size_t)(count - written));
                if (result <= 0) return;
                written += result;
            }
        }
    }
}

static void usage(const char *program) {
    fprintf(stderr, "Usage: %s --package <package> --pid <pid> --listen-port <port> --upstream-host <host> --upstream-port <port>\n", program);
}

int main(int argc, char **argv) {
    if (argc != 11) { usage(argv[0]); return 2; }
    const char *package_name = NULL, *upstream_host = NULL;
    long pid = 0, listen_port = 0, upstream_port = 0;
    for (int i = 1; i < argc; i += 2) {
        if (!strcmp(argv[i], "--package")) package_name = argv[i + 1];
        else if (!strcmp(argv[i], "--pid")) pid = strtol(argv[i + 1], NULL, 10);
        else if (!strcmp(argv[i], "--listen-port")) listen_port = strtol(argv[i + 1], NULL, 10);
        else if (!strcmp(argv[i], "--upstream-host")) upstream_host = argv[i + 1];
        else if (!strcmp(argv[i], "--upstream-port")) upstream_port = strtol(argv[i + 1], NULL, 10);
        else { usage(argv[0]); return 2; }
    }
    if (!valid_package(package_name) || !upstream_host || pid <= 0 || listen_port < 1 || listen_port > 65535 || upstream_port < 1 || upstream_port > 65535) {
        usage(argv[0]); return 2;
    }
    int server = socket(AF_INET, SOCK_STREAM, 0);
    int reuse = 1; (void)setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    struct sockaddr_in address = {0}; address.sin_family = AF_INET; address.sin_port = htons((uint16_t)listen_port); address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (server < 0 || bind(server, (struct sockaddr *)&address, sizeof(address)) != 0 || listen(server, 16) != 0) return 1;
    signal(SIGINT, stop); signal(SIGTERM, stop); signal(SIGCHLD, SIG_IGN);
    printf("{\"schema_version\":\"lynx.owner-probe.v1\",\"type\":\"relay_ready\",\"listen_port\":%ld,\"payload_accessed\":false}\n", listen_port); fflush(stdout);
    while (keep_running) {
        int client = accept(server, NULL, NULL);
        if (client < 0) { if (errno == EINTR) continue; break; }
        /* Keep the connection open briefly so the feasibility harness can
         * inspect the exact device tuple before admission. */
        usleep(1000000);
        bool owned = connection_is_owned(package_name, (pid_t)pid, (uint16_t)listen_port);
        printf("{\"schema_version\":\"lynx.owner-probe.v1\",\"type\":\"relay_admission\",\"decision\":\"%s\",\"payload_accessed\":false}\n", owned ? "verified_target" : "unknown"); fflush(stdout);
        if (!owned) { close(client); continue; }
        int upstream = connect_upstream(upstream_host, (uint16_t)upstream_port);
        if (upstream < 0) { close(client); continue; }
        pid_t worker = fork();
        if (worker == 0) { close(server); pump(client, upstream); close(client); close(upstream); _exit(0); }
        close(client); close(upstream);
    }
    close(server); return 0;
}
