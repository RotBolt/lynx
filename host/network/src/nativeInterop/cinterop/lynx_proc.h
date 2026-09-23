#include <arpa/inet.h>
#include <libproc.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/proc_info.h>

/* Small stable bridge over libproc. Kotlin never consumes libproc structs. */
static inline uint64_t lynx_proc_start_identity(int pid) {
  struct proc_bsdinfo info = {0};
  if (proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, sizeof(info)) != sizeof(info)) return 0;
  return ((uint64_t)info.pbi_start_tvsec << 32) | (uint32_t)info.pbi_start_tvusec;
}

static inline int lynx_proc_socket_owner(
    int pid,
    const char *local_address,
    uint16_t local_port,
    const char *remote_address,
    uint16_t remote_port
) {
  int count = proc_pidinfo(pid, PROC_PIDLISTFDS, 0, NULL, 0);
  if (count <= 0 || count % (int)sizeof(struct proc_fdinfo) != 0) return -1;
  struct proc_fdinfo *fds = calloc(1, (size_t)count);
  if (!fds) return -1;
  int populated = proc_pidinfo(pid, PROC_PIDLISTFDS, 0, fds, count);
  if (populated < 0) { free(fds); return -1; }
  int result = 0;
  for (int offset = 0; offset + (int)sizeof(struct proc_fdinfo) <= populated; offset += (int)sizeof(struct proc_fdinfo)) {
    struct proc_fdinfo *fd = (struct proc_fdinfo *)((char *)fds + offset);
    if (fd->proc_fdtype != PROX_FDTYPE_SOCKET) continue;
    struct socket_fdinfo socket_info = {0};
    if (proc_pidfdinfo(pid, fd->proc_fd, PROC_PIDFDSOCKETINFO, &socket_info, sizeof(socket_info)) != sizeof(socket_info)) continue;
    if (socket_info.psi.soi_kind != SOCKINFO_TCP) continue;
    const struct in_sockinfo *socket = &socket_info.psi.soi_proto.pri_tcp.tcpsi_ini;
    char observed_local[INET6_ADDRSTRLEN] = {0};
    char observed_remote[INET6_ADDRSTRLEN] = {0};
    int family = 0;
    if (socket->insi_vflag == INI_IPV4) {
      family = AF_INET;
      if (!inet_ntop(family, &socket->insi_laddr.ina_46.i46a_addr4, observed_local, sizeof(observed_local))) continue;
      if (!inet_ntop(family, &socket->insi_faddr.ina_46.i46a_addr4, observed_remote, sizeof(observed_remote))) continue;
    } else if (socket->insi_vflag == INI_IPV6) {
      family = AF_INET6;
      if (!inet_ntop(family, &socket->insi_laddr.ina_6, observed_local, sizeof(observed_local))) continue;
      if (!inet_ntop(family, &socket->insi_faddr.ina_6, observed_remote, sizeof(observed_remote))) continue;
    } else continue;
    if (strcmp(observed_local, local_address) == 0 && ntohs((uint16_t)socket->insi_lport) == local_port &&
        strcmp(observed_remote, remote_address) == 0 && ntohs((uint16_t)socket->insi_fport) == remote_port) {
      result = 1;
      break;
    }
  }
  free(fds);
  return result;
}

static inline int lynx_socket_local_endpoint(int fd, char *address, size_t address_size, uint16_t *port) {
  struct sockaddr_storage storage = {0};
  socklen_t length = sizeof(storage);
  if (getsockname(fd, (struct sockaddr *)&storage, &length) != 0) return -1;
  if (storage.ss_family == AF_INET) {
    struct sockaddr_in *socket = (struct sockaddr_in *)&storage;
    if (!inet_ntop(AF_INET, &socket->sin_addr, address, address_size)) return -1;
    if (port) *port = ntohs(socket->sin_port);
    return 0;
  }
  if (storage.ss_family == AF_INET6) {
    struct sockaddr_in6 *socket = (struct sockaddr_in6 *)&storage;
    if (!inet_ntop(AF_INET6, &socket->sin6_addr, address, address_size)) return -1;
    if (port) *port = ntohs(socket->sin6_port);
    return 0;
  }
  return -1;
}

static inline int lynx_socket_local_port(int fd) {
  struct sockaddr_storage storage = {0};
  socklen_t length = sizeof(storage);
  if (getsockname(fd, (struct sockaddr *)&storage, &length) != 0) return -1;
  if (storage.ss_family == AF_INET) return (int)ntohs(((struct sockaddr_in *)&storage)->sin_port);
  if (storage.ss_family == AF_INET6) return (int)ntohs(((struct sockaddr_in6 *)&storage)->sin6_port);
  return -1;
}

static inline int lynx_socket_peer_endpoint(int fd, char *address, size_t address_size) {
  struct sockaddr_storage storage = {0};
  socklen_t length = sizeof(storage);
  if (getpeername(fd, (struct sockaddr *)&storage, &length) != 0) return -1;
  if (storage.ss_family == AF_INET) {
    struct sockaddr_in *socket = (struct sockaddr_in *)&storage;
    if (!inet_ntop(AF_INET, &socket->sin_addr, address, address_size)) return -1;
    return 0;
  }
  if (storage.ss_family == AF_INET6) {
    struct sockaddr_in6 *socket = (struct sockaddr_in6 *)&storage;
    if (!inet_ntop(AF_INET6, &socket->sin6_addr, address, address_size)) return -1;
    return 0;
  }
  return -1;
}

static inline int lynx_socket_peer_port(int fd) {
  struct sockaddr_storage storage = {0};
  socklen_t length = sizeof(storage);
  if (getpeername(fd, (struct sockaddr *)&storage, &length) != 0) return -1;
  if (storage.ss_family == AF_INET) return (int)ntohs(((struct sockaddr_in *)&storage)->sin_port);
  if (storage.ss_family == AF_INET6) return (int)ntohs(((struct sockaddr_in6 *)&storage)->sin6_port);
  return -1;
}
