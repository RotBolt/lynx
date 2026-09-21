typedef struct ssl_ctx_st LYNSslCtx;
typedef struct ssl_st LYNSsl;
typedef struct ssl_method_st LYNSslMethod;

const LYNSslMethod *TLS_server_method(void);
const LYNSslMethod *TLS_client_method(void);
LYNSslCtx *SSL_CTX_new(const LYNSslMethod *method);
void SSL_CTX_free(LYNSslCtx *ctx);
int SSL_CTX_use_certificate_file(LYNSslCtx *ctx, const char *file, int type);
int SSL_CTX_use_PrivateKey_file(LYNSslCtx *ctx, const char *file, int type);
LYNSsl *SSL_new(LYNSslCtx *ctx);
void SSL_free(LYNSsl *ssl);
int SSL_set_fd(LYNSsl *ssl, int fd);
int SSL_accept(LYNSsl *ssl);
int SSL_connect(LYNSsl *ssl);
int SSL_read(LYNSsl *ssl, void *buffer, int length);
int SSL_write(LYNSsl *ssl, const void *buffer, int length);
int SSL_shutdown(LYNSsl *ssl);
int SSL_get_error(const LYNSsl *ssl, int returnCode);

int OPENSSL_init_ssl(unsigned long opts, const void *settings);
unsigned long ERR_get_error(void);
void ERR_error_string_n(unsigned long e, char *buf, unsigned long len);
