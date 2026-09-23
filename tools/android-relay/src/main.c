#include "relay_protocol.h"
#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static volatile sig_atomic_t running = 1;
static void stop_relay(int signal_number) { (void)signal_number; running = 0; }

bool lynx_relay_valid_package(const char *value) {
    if (!value || !*value) return false;
    for (const char *cursor = value; *cursor; cursor++) {
        if (!( (*cursor >= 'a' && *cursor <= 'z') || (*cursor >= 'A' && *cursor <= 'Z') ||
               (*cursor >= '0' && *cursor <= '9') || *cursor == '.' || *cursor == '_' )) return false;
    }
    return true;
}

static bool package_owns_inode(const char *package_name, pid_t pid, unsigned long long wanted) {
    char command[256];
    int written = snprintf(command, sizeof(command), "/system/bin/run-as %s ls -l /proc/%d/fd 2>/dev/null", package_name, pid);
    if (written < 0 || (size_t)written >= sizeof(command)) return false;
    FILE *listing = popen(command, "r");
    if (!listing) return false;
    char line[512]; bool found = false;
    while (fgets(line, sizeof(line), listing)) {
        unsigned long long inode = 0;
        const char *socket = strstr(line, "socket:[");
        if (socket && sscanf(socket, "socket:[%llu]", &inode) == 1 && inode == wanted) { found = true; break; }
    }
    (void)pclose(listing);
    return found;
}

static bool package_any_process_owns_inode(const char *package_name, pid_t preferred, unsigned long long wanted) {
    if (preferred > 0 && package_owns_inode(package_name, preferred, wanted)) return true;
    char command[256];
    int written = snprintf(command, sizeof(command), "/system/bin/pidof %s 2>/dev/null", package_name);
    if (written < 0 || (size_t)written >= sizeof(command)) return false;
    FILE *listing = popen(command, "r");
    if (!listing) return false;
    char output[256] = {0};
    bool found = fgets(output, sizeof(output), listing) != NULL;
    (void)pclose(listing);
    if (!found) return false;
    char *cursor = output;
    while (*cursor) {
        char *end = NULL; long pid = strtol(cursor, &end, 10);
        if (end != cursor && pid > 0 && package_owns_inode(package_name, (pid_t)pid, wanted)) return true;
        if (!end || end == cursor) break;
        cursor = end;
        while (*cursor == ' ') cursor++;
    }
    return false;
}

static bool decode_ipv4_little_endian(const char *hex, char *output, size_t size) {
    if (!hex || strlen(hex) != 8) return false;
    unsigned long raw = strtoul(hex, NULL, 16);
    uint8_t bytes[4] = {(uint8_t)raw, (uint8_t)(raw >> 8), (uint8_t)(raw >> 16), (uint8_t)(raw >> 24)};
    return inet_ntop(AF_INET, bytes, output, size) != NULL;
}

static bool socket_table_has_relay_peer(const char *path, bool ipv6_table, const lynx_relay_config *config, unsigned long long *inode) {
    FILE *table = fopen(path, "r");
    if (!table) return false;
    char line[768]; (void)fgets(line, sizeof(line), table); bool found = false;
    while (!found && fgets(line, sizeof(line), table)) {
        char remote[65] = {0}, host[INET_ADDRSTRLEN] = {0};
        unsigned remote_port = 0; unsigned long long candidate_inode = 0;
        int fields = sscanf(line, " %*u: %*64[^:]:%*x %64[^:]:%x %*x %*s %*s %*s %*u %*u %llu", remote, &remote_port, &candidate_inode);
        if (fields != 3 || remote_port != config->listen_port) continue;
        bool decoded = ipv6_table ? strlen(remote) == 32 && decode_ipv4_little_endian(remote + 24, host, sizeof(host))
                                  : decode_ipv4_little_endian(remote, host, sizeof(host));
        if (decoded && strcmp(host, "127.0.0.1") == 0 && package_any_process_owns_inode(config->package_name, config->pid, candidate_inode)) {
            *inode = candidate_inode; found = true;
        }
    }
    fclose(table); return found;
}

bool lynx_relay_socket_owned(const lynx_relay_config *config, unsigned long long *inode) {
    if (!config || !inode || config->pid <= 0 || !lynx_relay_valid_package(config->package_name)) return false;
    return socket_table_has_relay_peer("/proc/net/tcp", false, config, inode) ||
           socket_table_has_relay_peer("/proc/net/tcp6", true, config, inode);
}

int lynx_relay_connect(const char *host, uint16_t port) {
    struct addrinfo hints = {0}, *results = NULL; hints.ai_socktype = SOCK_STREAM; hints.ai_family = AF_UNSPEC;
    char service[6]; snprintf(service, sizeof(service), "%u", port);
    if (!host || getaddrinfo(host, service, &hints, &results) != 0) return -1;
    int socket_fd = -1;
    for (struct addrinfo *candidate = results; candidate; candidate = candidate->ai_next) {
        socket_fd = socket(candidate->ai_family, candidate->ai_socktype, candidate->ai_protocol);
        if (socket_fd >= 0 && connect(socket_fd, candidate->ai_addr, candidate->ai_addrlen) == 0) break;
        if (socket_fd >= 0) close(socket_fd); socket_fd = -1;
    }
    freeaddrinfo(results); return socket_fd;
}

static ssize_t read_proxy_headers(int fd, char *buffer, size_t capacity) {
    size_t length = 0;
    while (length + 1 < capacity && length < LYNX_RELAY_MAX_HEADER_BYTES) {
        ssize_t count = recv(fd, buffer + length, 1, 0);
        if (count <= 0) return count;
        length += (size_t)count; buffer[length] = '\0';
        if (length >= 4 && memcmp(buffer + length - 4, "\r\n\r\n", 4) == 0) return (ssize_t)length;
    }
    return -2;
}

static bool parse_authority(const char *url, char *host, size_t host_size, uint16_t *port) {
    if (!url || !*url) return false;
    const char *colon = strrchr(url, ':'); const char *end = colon ? colon : url + strlen(url);
    if (end == url || (size_t)(end - url) >= host_size) return false;
    memcpy(host, url, (size_t)(end - url)); host[end - url] = '\0';
    long parsed = colon ? strtol(colon + 1, NULL, 10) : 443;
    if (parsed < 1 || parsed > UINT16_MAX) return false;
    *port = (uint16_t)parsed; return true;
}

static void pump(int left, int right) {
    struct pollfd descriptors[2] = {{left, POLLIN, 0}, {right, POLLIN, 0}}; uint8_t buffer[16384];
    while (poll(descriptors, 2, -1) > 0) for (int index = 0; index < 2; index++) {
        if (!(descriptors[index].revents & (POLLIN | POLLHUP | POLLERR))) continue;
        int source = descriptors[index].fd, destination = index == 0 ? right : left;
        ssize_t count = recv(source, buffer, sizeof(buffer), 0); if (count <= 0) return;
        for (ssize_t offset = 0; offset < count;) { ssize_t written = send(destination, buffer + offset, (size_t)(count - offset), 0); if (written <= 0) return; offset += written; }
    }
}

static bool line_method_url(const char *headers, char *method, char *url) { return sscanf(headers, "%15s %2047s", method, url) == 2; }

static bool send_all(int fd, const void *data, size_t length) {
    const uint8_t *bytes = data;
    for (size_t offset = 0; offset < length;) {
        ssize_t written = send(fd, bytes + offset, length - offset, 0);
        if (written <= 0) return false;
        offset += (size_t)written;
    }
    return true;
}

static bool send_metadata_preface(int fd, const lynx_relay_config *config) {
    char metadata[4096];
    int length = snprintf(metadata, sizeof(metadata),
        "{\"protocol_version\":1,\"capture_id\":\"%s\",\"capture_token\":\"%s\",\"device_serial\":\"%s\",\"package_name\":\"%s\",\"decision\":\"verified_target\",\"method\":\"android-proc-inode\"}",
        config->capture_id ? config->capture_id : "", config->capture_token ? config->capture_token : "", config->device_serial ? config->device_serial : "", config->package_name ? config->package_name : "");
    if (length <= 0 || (size_t)length >= sizeof(metadata) || (size_t)length > LYNX_RELAY_MAX_HEADER_BYTES) return false;
    uint8_t prefix[4] = {(uint8_t)((unsigned)length >> 24), (uint8_t)((unsigned)length >> 16), (uint8_t)((unsigned)length >> 8), (uint8_t)length};
    return send_all(fd, prefix, sizeof(prefix)) && send_all(fd, metadata, (size_t)length);
}

int lynx_relay_forward(int client, const lynx_relay_config *config) {
    char headers[LYNX_RELAY_MAX_HEADER_BYTES + 1], method[16] = {0}, url[2048] = {0};
    ssize_t length = read_proxy_headers(client, headers, sizeof(headers));
    if (length <= 0 || !line_method_url(headers, method, url)) return -1;
    char host[256] = {0}; uint16_t port = 0;
    if (!strcmp(method, "CONNECT")) { if (!parse_authority(url, host, sizeof(host), &port)) return -1; }
    else {
        const char *scheme = strstr(url, "://"); if (!scheme) return -1;
        const char *authority = scheme + 3, *path = strchr(authority, '/'); size_t authority_length = path ? (size_t)(path - authority) : strlen(authority);
        char authority_copy[512] = {0}; if (!authority_length || authority_length >= sizeof(authority_copy)) return -1;
        memcpy(authority_copy, authority, authority_length); authority_copy[authority_length] = '\0';
        if (!parse_authority(authority_copy, host, sizeof(host), &port)) return -1;
        if (!strchr(authority_copy, ':')) port = !strncmp(url, "https://", 8) ? 443 : 80;
    }
    unsigned long long inode = 0; bool owned = lynx_relay_socket_owned(config, &inode);
    int upstream = lynx_relay_connect(owned ? config->upstream_host : host, owned ? config->upstream_port : port);
    if (upstream < 0) return -1;
    if (owned) {
        if (!send_metadata_preface(upstream, config) || !send_all(upstream, headers, (size_t)length)) { close(upstream); return -1; }
    } else if (!strcmp(method, "CONNECT")) {
        if (send(client, "HTTP/1.1 200 Connection Established\r\n\r\n", 39, 0) <= 0) { close(upstream); return -1; }
    } else {
        const char *scheme = strstr(url, "://"), *authority = scheme + 3, *path = strchr(authority, '/');
        if (!path) path = "/";
        char first_line[2300]; const char *line_end = strstr(headers, "\r\n");
        int written = snprintf(first_line, sizeof(first_line), "%s %s HTTP/1.1\r\n", method, path);
        size_t remainder = line_end ? (size_t)(length - (line_end + 2 - headers)) : 0;
        if (written < 0 || !line_end || send(upstream, first_line, (size_t)written, 0) != written || send(upstream, line_end + 2, remainder, 0) != (ssize_t)remainder) { close(upstream); return -1; }
    }
    pump(client, upstream); close(upstream); return owned ? 1 : 0;
}

static bool parse_number(const char *value, long min, long max, long *out) {
    if (!value || !*value) return false; char *end = NULL; errno = 0; long parsed = strtol(value, &end, 10);
    if (errno || !end || *end || parsed < min || parsed > max) return false; *out = parsed; return true;
}

static void usage(const char *program) { fprintf(stderr, "Usage: %s --package <id> --pid <pid> --listen-port <port> --upstream-host <host> --upstream-port <port> --capture-id <id> --device <serial> --token <token>\n", program); }

int main(int argc, char **argv) {
    lynx_relay_config config = {0}; long pid = 0, listen_port = 0, upstream_port = 0;
    for (int index = 1; index < argc; index += 2) {
        if (index + 1 >= argc) { usage(argv[0]); return 2; }
        if (!strcmp(argv[index], "--package")) config.package_name = argv[index + 1];
        else if (!strcmp(argv[index], "--pid") && parse_number(argv[index + 1], 1, INT32_MAX, &pid)) config.pid = (pid_t)pid;
        else if (!strcmp(argv[index], "--listen-port") && parse_number(argv[index + 1], 1, UINT16_MAX, &listen_port)) config.listen_port = (uint16_t)listen_port;
        else if (!strcmp(argv[index], "--upstream-host")) config.upstream_host = argv[index + 1];
        else if (!strcmp(argv[index], "--upstream-port") && parse_number(argv[index + 1], 1, UINT16_MAX, &upstream_port)) config.upstream_port = (uint16_t)upstream_port;
        else if (!strcmp(argv[index], "--token")) config.capture_token = argv[index + 1];
        else if (!strcmp(argv[index], "--capture-id")) config.capture_id = argv[index + 1];
        else if (!strcmp(argv[index], "--device")) config.device_serial = argv[index + 1];
        else { usage(argv[0]); return 2; }
    }
    if (!lynx_relay_valid_package(config.package_name) || config.pid <= 0 || !config.listen_port || !config.upstream_host || !config.upstream_port || !config.capture_id || !config.device_serial || !config.capture_token) { usage(argv[0]); return 2; }
    int server = socket(AF_INET, SOCK_STREAM, 0); if (server < 0) return 1;
    int reuse = 1; (void)setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    struct sockaddr_in address = {0}; address.sin_family = AF_INET; address.sin_port = htons(config.listen_port); address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(server, (struct sockaddr *)&address, sizeof(address)) != 0 || listen(server, 32) != 0) { close(server); return 1; }
    signal(SIGINT, stop_relay); signal(SIGTERM, stop_relay); signal(SIGCHLD, SIG_IGN);
    printf("{\"schema_version\":\"lynx.relay.v1\",\"type\":\"relay_ready\",\"protocol_version\":%u,\"listen_port\":%u}\n", LYNX_RELAY_PROTOCOL_VERSION, config.listen_port); fflush(stdout);
    while (running) { int client = accept(server, NULL, NULL); if (client < 0) { if (errno == EINTR) continue; break; } (void)lynx_relay_forward(client, &config); close(client); }
    close(server); return 0;
}
