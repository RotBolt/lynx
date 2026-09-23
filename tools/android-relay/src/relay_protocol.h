#ifndef LYNX_RELAY_PROTOCOL_H
#define LYNX_RELAY_PROTOCOL_H
#include <stdbool.h>
#include <stdint.h>
#include <sys/types.h>
#define LYNX_RELAY_PROTOCOL_VERSION 1u
#define LYNX_RELAY_MAX_HEADER_BYTES (64u * 1024u)
typedef struct {
    const char *package_name;
    pid_t pid;
    uint16_t listen_port;
    const char *upstream_host;
    uint16_t upstream_port;
    const char *capture_token;
} lynx_relay_config;
bool lynx_relay_valid_package(const char *value);
bool lynx_relay_socket_owned(const lynx_relay_config *config, unsigned long long *inode);
int lynx_relay_connect(const char *host, uint16_t port);
int lynx_relay_forward(int client, const lynx_relay_config *config);
#endif
