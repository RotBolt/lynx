#include <arpa/inet.h>
#include <errno.h>
#include <libproc.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/proc_info.h>
#include <sys/time.h>
#include <sys/types.h>

/*
 * Diagnostic-only ownership probe for the iOS Simulator host. It reads socket
 * tuples through libproc and intentionally never reads application payloads.
 * It proves tuple-to-process correlation; container/executable validation is
 * a separate admission requirement before this becomes a production adapter.
 */

static void usage(const char *program) {
    fprintf(stderr, "Usage: %s --pid <pid> --peer-host <ipv4-or-ipv6> --peer-port <port>\n", program);
}

static bool parse_uint(const char *value, unsigned long max, unsigned long *result) {
    char *end = NULL;
    errno = 0;
    unsigned long parsed = strtoul(value, &end, 10);
    if (errno || !value[0] || !end || *end || parsed > max) return false;
    *result = parsed;
    return true;
}

static bool normalize_ip(const struct in_sockinfo *socket, char *output, size_t output_size) {
    if (socket->insi_vflag == INI_IPV4) {
        return inet_ntop(AF_INET, &socket->insi_faddr.ina_46.i46a_addr4, output, output_size) != NULL;
    }
    if (socket->insi_vflag == INI_IPV6) {
        return inet_ntop(AF_INET6, &socket->insi_faddr.ina_6, output, output_size) != NULL;
    }
    return false;
}

static int self_test(void) {
    puts("{\"schema_version\":\"lynx.owner-probe.v1\",\"platform\":\"macos\",\"self_test\":true,\"payload_accessed\":false}");
    return 0;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--self-test") == 0) return self_test();
    if (argc != 7) {
        usage(argv[0]);
        return 2;
    }

    unsigned long raw_pid = 0;
    unsigned long raw_port = 0;
    const char *peer_host = NULL;
    for (int index = 1; index < argc; index += 2) {
        if (strcmp(argv[index], "--pid") == 0) {
            if (!parse_uint(argv[index + 1], INT32_MAX, &raw_pid)) {
                usage(argv[0]);
                return 2;
            }
        } else if (strcmp(argv[index], "--peer-host") == 0) {
            peer_host = argv[index + 1];
        } else if (strcmp(argv[index], "--peer-port") == 0) {
            if (!parse_uint(argv[index + 1], UINT16_MAX, &raw_port) || raw_port == 0) {
                usage(argv[0]);
                return 2;
            }
        } else {
            usage(argv[0]);
            return 2;
        }
    }
    if (!raw_pid || !peer_host) {
        usage(argv[0]);
        return 2;
    }

    struct in_addr ipv4;
    struct in6_addr ipv6;
    if (inet_pton(AF_INET, peer_host, &ipv4) != 1 && inet_pton(AF_INET6, peer_host, &ipv6) != 1) {
        fprintf(stderr, "peer host must be a numeric IPv4 or IPv6 address\n");
        return 2;
    }

    struct timeval started;
    gettimeofday(&started, NULL);
    pid_t pid = (pid_t)raw_pid;
    struct proc_bsdinfo process = {0};
    if (proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &process, sizeof(process)) != sizeof(process)) {
        fprintf(stderr, "could not read process identity for pid %d\n", pid);
        return 1;
    }

    int byte_count = proc_pidinfo(pid, PROC_PIDLISTFDS, 0, NULL, 0);
    if (byte_count <= 0 || byte_count % (int)sizeof(struct proc_fdinfo) != 0) {
        fprintf(stderr, "could not list file descriptors for pid %d\n", pid);
        return 1;
    }
    struct proc_fdinfo *fds = calloc(1, (size_t)byte_count);
    if (!fds) return 1;
    int populated = proc_pidinfo(pid, PROC_PIDLISTFDS, 0, fds, byte_count);
    if (populated < 0) {
        free(fds);
        fprintf(stderr, "could not read file descriptors for pid %d\n", pid);
        return 1;
    }

    bool matched = false;
    int socket_count = 0;
    for (int offset = 0; offset + (int)sizeof(struct proc_fdinfo) <= populated; offset += (int)sizeof(struct proc_fdinfo)) {
        struct proc_fdinfo *fd = (struct proc_fdinfo *)((char *)fds + offset);
        if (fd->proc_fdtype != PROX_FDTYPE_SOCKET) continue;
        struct socket_fdinfo socket_info = {0};
        if (proc_pidfdinfo(pid, fd->proc_fd, PROC_PIDFDSOCKETINFO, &socket_info, sizeof(socket_info)) != sizeof(socket_info)) continue;
        if (socket_info.psi.soi_kind != SOCKINFO_TCP) continue;
        char observed_host[INET6_ADDRSTRLEN] = {0};
        const struct in_sockinfo *tuple = &socket_info.psi.soi_proto.pri_tcp.tcpsi_ini;
        if (!normalize_ip(tuple, observed_host, sizeof(observed_host))) continue;
        socket_count++;
        if (strcmp(observed_host, peer_host) == 0 && ntohs((uint16_t)tuple->insi_fport) == (uint16_t)raw_port) matched = true;
    }
    free(fds);

    struct timeval completed;
    gettimeofday(&completed, NULL);
    long long duration_millis = (completed.tv_sec - started.tv_sec) * 1000LL +
        (completed.tv_usec - started.tv_usec) / 1000LL;
    printf("{\"schema_version\":\"lynx.owner-probe.v1\",\"platform\":\"macos\",\"pid\":%d,\"process_start_identity\":{\"seconds\":%llu,\"microseconds\":%llu},\"uid\":%u,\"peer\":{\"host\":\"%s\",\"port\":%lu},\"socket_inode\":null,\"tcp_socket_count\":%d,\"target_match\":\"%s\",\"duration_millis\":%lld,\"payload_accessed\":false}\n",
        pid, (unsigned long long)process.pbi_start_tvsec, (unsigned long long)process.pbi_start_tvusec,
        process.pbi_uid, peer_host, raw_port, socket_count, matched ? "verified_target" : "unknown", duration_millis);
    return matched ? 0 : 3;
}
