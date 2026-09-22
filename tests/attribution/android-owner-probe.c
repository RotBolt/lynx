/*
 * Diagnostic relay-side socket resolver. It is executed through adb shell
 * run-as for the attached package and therefore only produces ownership
 * metadata; it never opens, forwards, decrypts, or retains payload bytes.
 */
#include <arpa/inet.h>
#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <unistd.h>

struct input { pid_t pid; unsigned long long inode; bool has_inode; const char *package_name; const char *host; uint16_t port; int family; };

static void usage(const char *program) {
    fprintf(stderr, "Usage: %s (--inode <socket-inode> | --pid <pid> --package <package>) --peer-host <ipv4-or-ipv6> --peer-port <port>\n", program);
}

static bool number(const char *text, unsigned long max, unsigned long *out) {
    char *end = NULL; errno = 0; unsigned long value = strtoul(text, &end, 10);
    if (errno || !*text || !end || *end || value > max) return false;
    *out = value; return true;
}

static bool valid_package_name(const char *value) {
    if (!value || !*value) return false;
    for (const char *cursor = value; *cursor; cursor++) {
        if (!( (*cursor >= 'a' && *cursor <= 'z') || (*cursor >= 'A' && *cursor <= 'Z') ||
               (*cursor >= '0' && *cursor <= '9') || *cursor == '.' || *cursor == '_' )) return false;
    }
    return true;
}

static bool parse(int argc, char **argv, struct input *out) {
    unsigned long pid = 0, port = 0;
    if (argc != 7 && argc != 9) return false;
    for (int i = 1; i < argc; i += 2) {
        if (!strcmp(argv[i], "--pid")) { if (!number(argv[i + 1], INT32_MAX, &pid)) return false; }
        else if (!strcmp(argv[i], "--inode")) {
            char *end = NULL; errno = 0; out->inode = strtoull(argv[i + 1], &end, 10);
            if (errno || !*argv[i + 1] || !end || *end) return false;
            out->has_inode = true;
        }
        else if (!strcmp(argv[i], "--package")) out->package_name = argv[i + 1];
        else if (!strcmp(argv[i], "--peer-host")) out->host = argv[i + 1];
        else if (!strcmp(argv[i], "--peer-port")) { if (!number(argv[i + 1], UINT16_MAX, &port) || !port) return false; }
        else return false;
    }
    struct in_addr v4; struct in6_addr v6;
    if ((!pid && !out->has_inode) || !out->host || (pid && !out->has_inode && !valid_package_name(out->package_name))) return false;
    if (inet_pton(AF_INET, out->host, &v4) == 1) out->family = AF_INET;
    else if (inet_pton(AF_INET6, out->host, &v6) == 1) out->family = AF_INET6;
    else return false;
    out->pid = (pid_t)pid; out->port = (uint16_t)port; return true;
}

static bool package_owns_inode(const char *package_name, pid_t pid, unsigned long long wanted) {
    char command[256];
    snprintf(command, sizeof(command), "/system/bin/run-as %s ls -l /proc/%d/fd 2>/dev/null", package_name, pid);
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

static bool remote_ipv4(const char *hex, char *result, size_t size) {
    if (strlen(hex) != 8) return false;
    unsigned long raw = strtoul(hex, NULL, 16);
    uint8_t octets[4] = { (uint8_t)raw, (uint8_t)(raw >> 8), (uint8_t)(raw >> 16), (uint8_t)(raw >> 24) };
    return inet_ntop(AF_INET, octets, result, size) != NULL;
}

static bool remote_ipv6(const char *hex, char *result, size_t size) {
    if (strlen(hex) != 32) return false;
    uint8_t octets[16] = {0};
    for (int word = 0; word < 4; word++) {
        char part[9] = {0}; memcpy(part, hex + word * 8, 8);
        unsigned long raw = strtoul(part, NULL, 16);
        for (int byte = 0; byte < 4; byte++) octets[word * 4 + byte] = (uint8_t)(raw >> (byte * 8));
    }
    return inet_ntop(AF_INET6, octets, result, size) != NULL;
}

static bool resolve_table(const char *path, bool table_is_ipv6, const struct input *input, unsigned long long *matched_inode) {
    FILE *table = fopen(path, "r");
    if (!table) return false;
    char line[768]; (void)fgets(line, sizeof(line), table);
    bool matched = false;
    while (!matched && fgets(line, sizeof(line), table)) {
        char local[65] = {0}, remote[65] = {0}, host[INET6_ADDRSTRLEN] = {0};
        unsigned remote_port = 0, inode_fields = 0; unsigned long long inode = 0;
        int fields = sscanf(line, " %*u: %64[^:]:%*x %64[^:]:%x %*x %*s %*s %*s %*u %*u %llu",
                            local, remote, &remote_port, &inode);
        (void)local; (void)inode_fields;
        if (fields != 4 || remote_port != input->port) continue;
        bool decoded = false;
        if (table_is_ipv6 && input->family == AF_INET) decoded = strlen(remote) == 32 && remote_ipv4(remote + 24, host, sizeof(host));
        else if (table_is_ipv6) decoded = remote_ipv6(remote, host, sizeof(host));
        else if (input->family == AF_INET) decoded = remote_ipv4(remote, host, sizeof(host));
        bool owner_matches = input->has_inode ? inode == input->inode : package_owns_inode(input->package_name, input->pid, inode);
        if (decoded && !strcmp(host, input->host) && owner_matches) {
            *matched_inode = inode; matched = true;
        }
    }
    fclose(table); return matched;
}

static bool resolve(const struct input *input, unsigned long long *matched_inode) {
    return resolve_table("/proc/net/tcp", false, input, matched_inode) ||
        resolve_table("/proc/net/tcp6", true, input, matched_inode);
}

static bool process_identity(pid_t pid, unsigned long long *start_ticks, unsigned long *uid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    FILE *stat_file = fopen(path, "r");
    if (!stat_file) return false;
    char stat[2048] = {0};
    bool read = fgets(stat, sizeof(stat), stat_file) != NULL;
    fclose(stat_file);
    char *after_name = read ? strrchr(stat, ')') : NULL;
    if (!after_name) return false;
    char *cursor = after_name + 2; /* process state, field 3 */
    if (!*cursor) return false;
    cursor++;
    for (int field = 4; field <= 22; field++) {
        while (*cursor == ' ') cursor++;
        char *end = NULL;
        errno = 0;
        unsigned long long value = strtoull(cursor, &end, 10);
        if (errno || end == cursor) return false;
        if (field == 22) *start_ticks = value;
        cursor = end;
    }
    snprintf(path, sizeof(path), "/proc/%d/status", pid);
    FILE *status_file = fopen(path, "r");
    if (!status_file) return false;
    char line[256]; bool found_uid = false;
    while (fgets(line, sizeof(line), status_file)) {
        if (sscanf(line, "Uid:\t%lu", uid) == 1) { found_uid = true; break; }
    }
    fclose(status_file);
    return found_uid;
}

int main(int argc, char **argv) {
    struct input input = {0}; if (!parse(argc, argv, &input)) { usage(argv[0]); return 2; }
    struct timeval start, end; gettimeofday(&start, NULL);
    unsigned long long inode = 0, start_ticks = 0; unsigned long uid = 0;
    bool identity_known = input.has_inode || process_identity(input.pid, &start_ticks, &uid);
    bool found = identity_known && resolve(&input, &inode);
    gettimeofday(&end, NULL);
    long long duration = (end.tv_sec - start.tv_sec) * 1000LL + (end.tv_usec - start.tv_usec) / 1000LL;
    if (found) {
        if (input.has_inode) {
            printf("{\"schema_version\":\"lynx.owner-probe.v1\",\"platform\":\"android\",\"pid\":null,\"process_start_identity\":null,\"uid\":null,\"peer\":{\"host\":\"%s\",\"port\":%u},\"socket_inode\":%llu,\"target_match\":\"verified_target\",\"duration_millis\":%lld,\"payload_accessed\":false}\n", input.host, input.port, inode, duration);
        } else {
            printf("{\"schema_version\":\"lynx.owner-probe.v1\",\"platform\":\"android\",\"pid\":%d,\"process_start_identity\":%llu,\"uid\":%lu,\"peer\":{\"host\":\"%s\",\"port\":%u},\"socket_inode\":%llu,\"target_match\":\"verified_target\",\"duration_millis\":%lld,\"payload_accessed\":false}\n", input.pid, start_ticks, uid, input.host, input.port, inode, duration);
        }
    } else if (identity_known) {
        if (input.has_inode) {
            printf("{\"schema_version\":\"lynx.owner-probe.v1\",\"platform\":\"android\",\"pid\":null,\"process_start_identity\":null,\"uid\":null,\"peer\":{\"host\":\"%s\",\"port\":%u},\"socket_inode\":null,\"target_match\":\"unknown\",\"duration_millis\":%lld,\"payload_accessed\":false}\n", input.host, input.port, duration);
        } else {
            printf("{\"schema_version\":\"lynx.owner-probe.v1\",\"platform\":\"android\",\"pid\":%d,\"process_start_identity\":%llu,\"uid\":%lu,\"peer\":{\"host\":\"%s\",\"port\":%u},\"socket_inode\":null,\"target_match\":\"unknown\",\"duration_millis\":%lld,\"payload_accessed\":false}\n", input.pid, start_ticks, uid, input.host, input.port, duration);
        }
    } else {
        printf("{\"schema_version\":\"lynx.owner-probe.v1\",\"platform\":\"android\",\"pid\":%d,\"process_start_identity\":null,\"uid\":null,\"peer\":{\"host\":\"%s\",\"port\":%u},\"socket_inode\":null,\"target_match\":\"unknown\",\"duration_millis\":%lld,\"payload_accessed\":false}\n", input.pid, input.host, input.port, duration);
    }
    return found ? 0 : 3;
}
