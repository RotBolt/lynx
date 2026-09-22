#include <openssl/ssl.h>
#include <openssl/err.h>
typedef SSL_CTX LYNSslCtx;
typedef SSL LYNSsl;
typedef SSL_METHOD LYNSslMethod;

static int lynx_alpn_select(LYNSsl *ssl, const unsigned char **out,
                            unsigned char *outlen, const unsigned char *in,
                            unsigned int inlen, void *arg) {
  (void)ssl; (void)arg;
  const unsigned char *http1 = NULL;
  for (unsigned int i = 0; i + 2 < inlen;) {
    unsigned int length = in[i++];
    if (i + length > inlen) break;
    if (length == 2 && in[i] == 'h' && in[i + 1] == '2') {
      *out = in + i; *outlen = 2; return SSL_TLSEXT_ERR_OK;
    }
    if (length == 8 && in[i] == 'h' && in[i + 1] == 't' && in[i + 2] == 't' &&
        in[i + 3] == 'p' && in[i + 4] == '/' && in[i + 5] == '1' &&
        in[i + 6] == '.' && in[i + 7] == '1') {
      http1 = in + i;
    }
    i += length;
  }
  if (http1 != NULL) {
    *out = http1; *outlen = 8; return SSL_TLSEXT_ERR_OK;
  }
  return SSL_TLSEXT_ERR_NOACK;
}

static inline void lynx_ssl_enable_h2_server(LYNSslCtx *ctx) {
  SSL_CTX_set_alpn_select_cb(ctx, lynx_alpn_select, NULL);
}

static int lynx_alpn_select_http1(LYNSsl *ssl, const unsigned char **out,
                                  unsigned char *outlen, const unsigned char *in,
                                  unsigned int inlen, void *arg) {
  (void)ssl; (void)arg;
  for (unsigned int i = 0; i + 2 < inlen;) {
    unsigned int length = in[i++];
    if (i + length > inlen) break;
    if (length == 8 && in[i] == 'h' && in[i + 1] == 't' && in[i + 2] == 't' &&
        in[i + 3] == 'p' && in[i + 4] == '/' && in[i + 5] == '1' &&
        in[i + 6] == '.' && in[i + 7] == '1') {
      *out = in + i; *outlen = 8; return SSL_TLSEXT_ERR_OK;
    }
    i += length;
  }
  return SSL_TLSEXT_ERR_NOACK;
}

static inline void lynx_ssl_enable_http1_server(LYNSslCtx *ctx) {
  SSL_CTX_set_alpn_select_cb(ctx, lynx_alpn_select_http1, NULL);
}

static inline int lynx_ssl_enable_h2_client(LYNSsl *ssl) {
  // Advertise both supported HTTPS protocols. Offering only h2 makes a proxy
  // unusable against valid HTTP/1.1-only TLS origins that reject unknown ALPN.
  static const unsigned char protocols[] = {
    2, 'h', '2',
    8, 'h', 't', 't', 'p', '/', '1', '.', '1'
  };
  return SSL_set_alpn_protos(ssl, protocols, sizeof(protocols));
}

static inline int lynx_ssl_enable_http1_client(LYNSsl *ssl) {
  static const unsigned char protocols[] = {
    8, 'h', 't', 't', 'p', '/', '1', '.', '1'
  };
  return SSL_set_alpn_protos(ssl, protocols, sizeof(protocols));
}

static inline int lynx_ssl_set_sni(LYNSsl *ssl, const char *hostname) {
  return SSL_set_tlsext_host_name(ssl, hostname);
}

static inline int lynx_ssl_is_h2(const LYNSsl *ssl) {
  const unsigned char *selected = NULL; unsigned int length = 0;
  SSL_get0_alpn_selected(ssl, &selected, &length);
  return selected != NULL && length == 2 && selected[0] == 'h' && selected[1] == '2';
}
